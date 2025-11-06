package com.brycewg.asrkb.ui.floating

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floatingball.*
import com.brycewg.asrkb.ui.floating.FloatingImeHints
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 悬浮球语音识别服务（重构版）
 * 在其他输入法激活时也能通过悬浮球进行语音识别
 *
 * 重构改进：
 * 1. 使用状态机模式（FloatingBallState）替代多个布尔标志
 * 2. 职责分离到专门的管理器类：
 *    - FloatingBallViewManager: 视图和动画管理
 *    - AsrSessionManager: ASR 引擎生命周期和结果处理
 *    - FloatingBallTouchHandler: 触摸事件处理
 *    - FloatingMenuHelper: 菜单显示逻辑（与 FloatingImeSwitcherService 共享）
 * 3. 所有 catch 块都添加了日志
 */
class FloatingAsrService : Service(),
    AsrSessionManager.AsrSessionListener,
    FloatingBallTouchHandler.TouchEventListener {

    companion object {
        private const val TAG = "FloatingAsrService"
        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_ASR_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_ASR_HIDE"
    }

    // 核心组件
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var viewManager: FloatingBallViewManager
    private lateinit var asrSessionManager: AsrSessionManager
    private lateinit var touchHandler: FloatingBallTouchHandler
    private lateinit var menuHelper: FloatingMenuHelper

    // 状态机
    private val stateMachine = FloatingBallStateMachine()

    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI 状态
    private val handler = Handler(Looper.getMainLooper())
    private var imeVisible: Boolean = false
    private var currentToast: Toast? = null

    // 菜单状态
    private var radialMenuView: View? = null
    private var vendorMenuView: View? = null
    private var radialDragSession: FloatingMenuHelper.DragRadialMenuSession? = null
    private var touchActiveGuard: Boolean = false


    // 显示尝试去重
    private var lastShowAttemptAt: Long = 0L
    private var lastShowAttemptSig: String? = null

    // 广播接收器
    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingImeHints.ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    DebugLogManager.log("float", "hint", mapOf("action" to "VISIBLE"))
                    updateVisibilityByPref("hint_visible")
                    try { BluetoothRouteManager.setImeActive(this@FloatingAsrService, true) } catch (t: Throwable) { Log.w(TAG, "BluetoothRouteManager setImeActive(true)", t) }
                }
                FloatingImeHints.ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    DebugLogManager.log("float", "hint", mapOf("action" to "HIDDEN"))
                    updateVisibilityByPref("hint_hidden")
                    try { BluetoothRouteManager.setImeActive(this@FloatingAsrService, false) } catch (t: Throwable) { Log.w(TAG, "BluetoothRouteManager setImeActive(false)", t) }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        windowManager = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)

        // 初始化管理器
        viewManager = FloatingBallViewManager(this, prefs, windowManager)
        asrSessionManager = AsrSessionManager(this, prefs, serviceScope, this)
        touchHandler = FloatingBallTouchHandler(this, prefs, viewManager, windowManager, this)
        menuHelper = FloatingMenuHelper(this, windowManager)

        // 注册广播接收器（Android 13+ 要求显式导出标志；使用 ContextCompat 兼容处理）
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(FloatingImeHints.ACTION_HINT_IME_VISIBLE)
                addAction(FloatingImeHints.ACTION_HINT_IME_HIDDEN)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                /* context = */ this,
                /* receiver = */ hintReceiver,
                /* filter = */ filter,
                /* flags = */ androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register hint receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, floatingAsrEnabled=${prefs.floatingAsrEnabled}")
        when (intent?.action) {
            ACTION_SHOW -> updateVisibilityByPref("start_action_show")
            ACTION_HIDE -> hideBall()
            FloatingImeHints.ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                DebugLogManager.log("float", "hint", mapOf("action" to "VISIBLE"))
                updateVisibilityByPref("start_hint_visible")
                // 半隐策略启用时：键盘或输入焦点出现，浮现并保持显现
                if (!prefs.floatingSwitcherOnlyWhenImeVisible) {
                    try {
                        viewManager.animateRevealFromEdgeIfNeeded()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to reveal on IME visible (start)", e)
                    }
                }
            }
            FloatingImeHints.ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                DebugLogManager.log("float", "hint", mapOf("action" to "HIDDEN"))
                updateVisibilityByPref("start_hint_hidden")
                // 半隐策略启用时：键盘/焦点收起后（静息态）回到半隐
                if (!prefs.floatingSwitcherOnlyWhenImeVisible) {
                    val completionActive = try { viewManager.isCompletionTickActive() } catch (_: Throwable) { false }
                    if (stateMachine.isIdle && !completionActive) {
                        try {
                            viewManager.animateHideToEdgePartialIfNeeded()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to partial hide on IME hidden (start)", e)
                        }
                    }
                }
            }
            else -> showBall("start_default")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        viewManager.cleanup()
        asrSessionManager.cleanup()
        touchHandler.cleanup()
        hideBall()
        hideRadialMenu()
        hideVendorMenu()

        try {
            serviceScope.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cancel service scope", e)
        }
        try {
            unregisterReceiver(hintReceiver)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister receiver", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 视图管理 ====================

    private fun showBall(src: String = "update_visibility") {
        Log.d(TAG, "showBall called: floatingAsrEnabled=${prefs.floatingAsrEnabled}, hasOverlay=${hasOverlayPermission()}")
        // 去重 300ms 内相同签名
        val enabledPref = try { prefs.floatingAsrEnabled } catch (_: Throwable) { false }
        val onlyWhenImeVisible = try { prefs.floatingSwitcherOnlyWhenImeVisible } catch (_: Throwable) { false }
        val sig = "${src}|${enabledPref}|${hasOverlayPermission()}|${imeVisible}|${onlyWhenImeVisible}"
        val now = System.currentTimeMillis()
        if (!(now - lastShowAttemptAt < 300L && lastShowAttemptSig == sig)) {
            DebugLogManager.log(
            category = "float",
            event = "show_attempt",
            data = mapOf(
                "src" to src,
                "enabled" to enabledPref,
                "overlay" to hasOverlayPermission(),
                "imeVisible" to imeVisible,
                "onlyWhenImeVisible" to onlyWhenImeVisible
            )
        )
            lastShowAttemptAt = now
            lastShowAttemptSig = sig
        }
        if (!prefs.floatingAsrEnabled || !hasOverlayPermission()) {
            Log.w(TAG, "Cannot show ball: permission or setting issue")
            DebugLogManager.log("float", "show_skip", mapOf("reason" to "pref_or_permission"))
            hideBall()
            return
        }

        val completionActive = try {
            viewManager.isCompletionTickActive()
        } catch (_: Throwable) { false }
        // 交互保护：长按/拖拽选中/移动模式/打开面板期间，即使 IME 隐藏也保持可见
        val forceVisible = (radialMenuView != null || vendorMenuView != null ||
                radialDragSession != null || stateMachine.isMoveMode || touchActiveGuard)
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible &&
            !stateMachine.isRecording && !stateMachine.isProcessing && !completionActive && !forceVisible) {
            Log.d(TAG, "Pref requires IME visible; hiding for now")
            DebugLogManager.log("float", "show_skip", mapOf("reason" to "ime_not_visible"))
            hideBall()
            return
        }

        // 若上次处于错误态，此处复位为 Idle，避免新出现时重复播放错误动画
        if (stateMachine.isError) {
            try {
                stateMachine.transitionTo(FloatingBallState.Idle)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to reset state from Error to Idle before show", e)
            }
        }

        val ballView = viewManager.getBallView()
        if (ballView != null) {
            viewManager.applyBallAlpha()
            viewManager.applyBallSize()
            viewManager.updateStateVisual(stateMachine.state)
            return
        }

        // 创建触摸监听器
        val touchListener = touchHandler.createTouchListener { stateMachine.isMoveMode }

        // 显示悬浮球
        val success = viewManager.showBall(
            onClickListener = { hapticTapIfEnabled(it) },
            onTouchListener = touchListener,
            initialState = stateMachine.state
        )

        if (!success) {
            DebugLogManager.log("float", "show_failed")
            return
        }
        DebugLogManager.log("float", "show_success")

        // 悬浮球首次出现时，按需异步预加载本地模型
        tryPreloadLocalAsrOnce()

        // 初始为静息场景：若在左右边缘或就近吸附，则执行半隐
        // 仅当未开启“仅在键盘显示时显示悬浮球”时启用半隐
        if (stateMachine.isIdle && !prefs.floatingSwitcherOnlyWhenImeVisible) {
            try {
                viewManager.animateHideToEdgePartialIfNeeded()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to apply initial partial hide", e)
            }
        }
    }

    private fun hideBall() {
        viewManager.hideBall()
        DebugLogManager.log("float", "hide")
    }

    private fun updateVisibilityByPref(src: String = "update_visibility") {
        val forceVisible = (radialMenuView != null || vendorMenuView != null ||
                           radialDragSession != null || stateMachine.isMoveMode || touchActiveGuard)
        if (!prefs.floatingAsrEnabled) {
            hideBall()
            return
        }
        val completionActive2 = try {
            viewManager.isCompletionTickActive()
        } catch (_: Throwable) { false }
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible &&
            !stateMachine.isRecording && !stateMachine.isProcessing && !completionActive2 && !forceVisible) {
            hideBall()
            return
        }
        showBall(src)
    }

    // ==================== ASR 会话控制 ====================

    private fun startRecording() {
        Log.d(TAG, "startRecording called")

        // 检查权限
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "No record audio permission")
            showToast(getString(R.string.asr_error_mic_permission_denied))
            return
        }

        if (!prefs.hasAsrKeys()) {
            Log.w(TAG, "No ASR keys configured")
            showToast(getString(R.string.hint_need_keys))
            return
        }

        // 开始录音前切换为激活态图标
        try {
            viewManager.getBallView()?.findViewById<android.widget.ImageView>(R.id.ballIcon)
                ?.setImageResource(R.drawable.microphone_fill)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to reset icon to mic", e)
        }

        // 启动会话
        asrSessionManager.startRecording()
        updateVisibilityByPref()
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        asrSessionManager.stopRecording()
        updateVisibilityByPref()
    }

    // ==================== AsrSessionManager.AsrSessionListener ====================

    override fun onSessionStateChanged(state: FloatingBallState) {
        stateMachine.transitionTo(state)
        handler.post {
            viewManager.updateStateVisual(state)
            // 视觉与布局：激活态浮现、静息态半隐
            try {
                when (state) {
                    is FloatingBallState.Recording, is FloatingBallState.Processing ->
                        viewManager.animateRevealFromEdgeIfNeeded()
                    is FloatingBallState.Idle -> {
                        if (!viewManager.isCompletionTickActive() && !prefs.floatingSwitcherOnlyWhenImeVisible) {
                            // 仅当未开启“仅在键盘显示时显示悬浮球”时执行半隐
                            viewManager.animateHideToEdgePartialIfNeeded()
                        } else {
                            // 正在展示对勾：避免立刻半隐，交由 onResultCommitted 的延迟兜底处理
                        }
                    }
                    else -> {
                        // Error/MoveMode：不改动贴边半隐
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to apply edge reveal/hide on state change", e)
            }
            updateVisibilityByPref()
        }
    }

    override fun onResultCommitted(text: String, success: Boolean) {
        handler.post {
            if (success) {
                viewManager.showCompletionTick()
                try {
                    val audioMs = asrSessionManager.popLastAudioMsForStats()
                    val procMs = asrSessionManager.getLastRequestDuration() ?: 0L
                    prefs.recordUsageCommit("floating", prefs.asrVendor, audioMs, com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(text), procMs)
                    // 写入历史记录（根据设置判断是否经过 AI 处理）
                    try {
                        val store = com.brycewg.asrkb.store.AsrHistoryStore(this@FloatingAsrService)
                        val ai = try { prefs.postProcessEnabled && prefs.hasLlmKeys() } catch (_: Throwable) { false }
                        store.add(
                            com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                timestamp = System.currentTimeMillis(),
                                text = text,
                                vendorId = prefs.asrVendor.id,
                                audioMs = audioMs,
                                procMs = procMs,
                                source = "floating",
                                aiProcessed = ai,
                                charCount = com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(text)
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add ASR history (floating)", e)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to record usage stats (floating)", t)
                }

                // 在展示完成对勾后，稍作延迟再尝试半隐（仅左右边缘）。
                // 即使状态回调遗漏，这里也能保证一次隐入体验。
                try {
                    handler.postDelayed({
                        if (stateMachine.isIdle && !prefs.floatingSwitcherOnlyWhenImeVisible) {
                            // 仅当未开启“仅在键盘显示时显示悬浮球”时执行半隐
                            try {
                                viewManager.animateHideToEdgePartialIfNeeded()
                            } catch (e: Throwable) {
                                Log.w(TAG, "Failed to partial hide after commit", e)
                            }
                        }
                    }, 3000L)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to schedule post-commit partial hide", e)
                }
            }
        }
    }

    override fun onError(message: String) {
        handler.post {
            val mapped = mapErrorToFriendlyMessage(message)
            if (mapped != null) {
                showToast(mapped)
            } else {
                showToast(getString(R.string.floating_asr_error, message))
            }

            // 错误或空结果等同于一次会话结束：
            // 为了统一体验，在错误动画完成后，尝试执行一次边缘半隐。
            try {
                handler.postDelayed({
                    if (!prefs.floatingSwitcherOnlyWhenImeVisible) {
                        // 仅当未开启“仅在键盘显示时显示悬浮球”时执行半隐
                        try {
                            viewManager.animateHideToEdgePartialIfNeeded()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to partial hide after error", e)
                        }
                    }
                }, 3000L)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to schedule partial hide after error", e)
            }

            // 在错误动画结束后，自动将状态复位到 Idle，避免后续刷新/再次显示时重复触发错误动画
            // 错误抖动时长约 500ms，颜色滤镜在 1000ms 后清除，这里取 1500ms 以确保视觉恢复
            try {
                handler.postDelayed({
                    if (stateMachine.isError) {
                        try {
                            stateMachine.transitionTo(FloatingBallState.Idle)
                            viewManager.updateStateVisual(FloatingBallState.Idle)
                        } catch (ex: Throwable) {
                            Log.w(TAG, "Failed to reset state to Idle after error animation", ex)
                        }
                        updateVisibilityByPref()
                    }
                }, 1500L)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to schedule state reset after error", e)
            }
        }
    }

    // ==================== FloatingBallTouchHandler.TouchEventListener ====================

    override fun onSingleTap() {
        // 移动模式：点击退出
        if (stateMachine.isMoveMode) {
            stateMachine.transitionTo(FloatingBallState.Idle)
            viewManager.getBallView()?.let {
                try {
                    viewManager.animateSnapToEdge(it)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to animate snap, falling back", e)
                    viewManager.snapToEdge(it)
                }
            }
            hideRadialMenu()
            hideVendorMenu()
            return
        }

        // 处理中：忽略点击
        if (stateMachine.isProcessing) {
            return
        }

        // 检查无障碍权限
        if (!AsrAccessibilityService.isEnabled()) {
            Log.w(TAG, "Accessibility service not enabled")
            showToast(getString(R.string.toast_need_accessibility_perm))
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to open accessibility settings", e)
            }
            return
        }

        // 点击时若处于左右半隐，先执行浮现动画
        try {
            viewManager.animateRevealFromEdgeIfNeeded()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to reveal on tap", e)
        }

        // 切换录音状态
        if (stateMachine.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    override fun onLongPress() {
        // 新交互：不在长按瞬间弹出面板，等待左右滑动开始再弹出。
        // 但此时先设置可见保护，避免 IME 隐藏导致悬浮球被系统收起从而丢失触摸事件链。
        touchActiveGuard = true
        updateVisibilityByPref()
    }

    override fun onLongPressDragStart(initialRawX: Float, initialRawY: Float) {
        touchActiveGuard = true
        try { viewManager.animateRevealFromEdgeIfNeeded() } catch (_: Throwable) {}

        if (radialDragSession != null) return

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try { prefs.floatingSwitcherAlpha } catch (_: Throwable) { 1.0f }

        val items = listOf(
            FloatingMenuHelper.MenuItem(
                R.drawable.article,
                getString(R.string.label_radial_switch_prompt),
                getString(R.string.label_radial_switch_prompt)
            ) { onPickPromptPresetFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.waveform,
                getString(R.string.label_radial_switch_asr),
                getString(R.string.label_radial_switch_asr)
            ) { onPickAsrVendor() },
            FloatingMenuHelper.MenuItem(
                R.drawable.keyboard,
                getString(R.string.label_radial_switch_ime),
                getString(R.string.label_radial_switch_ime)
            ) { invokeImePickerFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.arrows_out_cardinal,
                getString(R.string.label_radial_move),
                getString(R.string.label_radial_move)
            ) { enableMoveModeFromMenu() },
            FloatingMenuHelper.MenuItem(
                if (try { prefs.autoStopOnSilenceEnabled } catch (_: Throwable) { false }) {
                    R.drawable.hand_palm_fill
                } else {
                    R.drawable.hand_palm
                },
                getString(R.string.label_radial_toggle_silence_autostop),
                getString(R.string.label_radial_toggle_silence_autostop)
            ) { toggleAutoStopSilenceFromMenu() },
            FloatingMenuHelper.MenuItem(
                if (try { prefs.postProcessEnabled } catch (_: Throwable) { false }) {
                    R.drawable.magic_wand_fill
                } else {
                    R.drawable.magic_wand
                },
                getString(R.string.label_radial_postproc),
                getString(R.string.label_radial_postproc)
            ) { togglePostprocFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.textbox,
                getString(R.string.label_radial_open_history),
                getString(R.string.label_radial_open_history)
            ) { showHistoryPanelFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.cloud_arrow_up,
                getString(R.string.label_radial_clipboard_upload),
                getString(R.string.label_radial_clipboard_upload)
            ) { uploadClipboardOnceFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.cloud_arrow_down,
                getString(R.string.label_radial_clipboard_pull),
                getString(R.string.label_radial_clipboard_pull)
            ) { pullClipboardOnceFromMenu() }
        )

        radialDragSession = menuHelper.showRadialMenuForDrag(center, alpha, items) {
            radialMenuView = null
            radialDragSession = null
            touchActiveGuard = false
            updateVisibilityByPref()
        }
        radialMenuView = radialDragSession?.root
        updateVisibilityByPref()
        // 初始位置也更新一次高亮（若用户已滑动）
        radialDragSession?.updateHover(initialRawX, initialRawY)
    }

    override fun onLongPressDragMove(rawX: Float, rawY: Float) {
        radialDragSession?.updateHover(rawX, rawY)
    }

    override fun onLongPressDragRelease(rawX: Float, rawY: Float) {
        radialDragSession?.performSelectionAt(rawX, rawY)
        radialDragSession = null
        // onDismiss 已重置 touchActiveGuard 和 radialMenuView
    }

    override fun onMoveStarted() {
        touchActiveGuard = true
    }

    override fun onMoveEnded() {
        touchActiveGuard = false
        // 移动结束：在静息场景执行贴边吸附+半隐（底部除外）
        try {
            if (stateMachine.isIdle && !prefs.floatingSwitcherOnlyWhenImeVisible) {
                // 仅当未开启“仅在键盘显示时显示悬浮球”时执行半隐
                viewManager.animateHideToEdgePartialIfNeeded()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to partial hide after move end", e)
        }
        updateVisibilityByPref()
    }

    override fun onDragCancelled() {
        radialDragSession?.dismiss()
        radialDragSession = null
        touchActiveGuard = false
        updateVisibilityByPref()
    }

    // ==================== 菜单管理 ====================

    private fun showRadialMenu() {
        if (radialMenuView != null) return

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha", e)
            1.0f
        }

        val items = listOf(
            FloatingMenuHelper.MenuItem(
                R.drawable.article,
                getString(R.string.label_radial_switch_prompt),
                getString(R.string.label_radial_switch_prompt)
            ) { onPickPromptPresetFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.waveform,
                getString(R.string.label_radial_switch_asr),
                getString(R.string.label_radial_switch_asr)
            ) { onPickAsrVendor() },
            FloatingMenuHelper.MenuItem(
                R.drawable.keyboard,
                getString(R.string.label_radial_switch_ime),
                getString(R.string.label_radial_switch_ime)
            ) { invokeImePickerFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.arrows_out_cardinal,
                getString(R.string.label_radial_move),
                getString(R.string.label_radial_move)
            ) { enableMoveModeFromMenu() },
            // 录音判停开关（与 ASR 设置一致）
            FloatingMenuHelper.MenuItem(
                if (try { prefs.autoStopOnSilenceEnabled } catch (_: Throwable) { false }) {
                    R.drawable.hand_palm_fill
                } else {
                    R.drawable.hand_palm
                },
                getString(R.string.label_radial_toggle_silence_autostop),
                getString(R.string.label_radial_toggle_silence_autostop)
            ) { toggleAutoStopSilenceFromMenu() },
            FloatingMenuHelper.MenuItem(
                if (try { prefs.postProcessEnabled } catch (_: Throwable) { false }) {
                    R.drawable.magic_wand_fill
                } else {
                    R.drawable.magic_wand
                },
                getString(R.string.label_radial_postproc),
                getString(R.string.label_radial_postproc)
            ) { togglePostprocFromMenu() },
            // 查看识别历史（简版面板）
            FloatingMenuHelper.MenuItem(
                R.drawable.textbox,
                getString(R.string.label_radial_open_history),
                getString(R.string.label_radial_open_history)
            ) { showHistoryPanelFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.cloud_arrow_up,
                getString(R.string.label_radial_clipboard_upload),
                getString(R.string.label_radial_clipboard_upload)
            ) { uploadClipboardOnceFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.cloud_arrow_down,
                getString(R.string.label_radial_clipboard_pull),
                getString(R.string.label_radial_clipboard_pull)
            ) { pullClipboardOnceFromMenu() }
        )

        radialMenuView = menuHelper.showRadialMenu(center, alpha, items) {
            radialMenuView = null
            updateVisibilityByPref()
        }
        updateVisibilityByPref()
    }

    private fun hideRadialMenu() {
        menuHelper.hideMenu(radialMenuView)
        radialMenuView = null
        updateVisibilityByPref()
    }

    private fun hideVendorMenu() {
        menuHelper.hideMenu(vendorMenuView)
        vendorMenuView = null
        updateVisibilityByPref()
    }

    private fun onPickPromptPresetFromMenu() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha", e)
            1.0f
        }

        val presets = try {
            prefs.getPromptPresets()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get prompt presets", e)
            emptyList()
        }
        val active = try {
            prefs.activePromptId
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get active prompt ID", e)
            ""
        }

        val entries = presets.map { p ->
            Triple(p.title, p.id == active) {
                try {
                    prefs.activePromptId = p.id
                    Toast.makeText(
                        this,
                        getString(R.string.switched_preset, p.title),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to switch prompt preset", e)
                }
                Unit
            }
        }

        vendorMenuView = menuHelper.showListPanel(
            center, alpha,
            getString(R.string.label_llm_prompt_presets),
            entries
        ) {
            vendorMenuView = null
            touchActiveGuard = false
            updateVisibilityByPref()
        }
    }

    private fun onPickAsrVendor() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha", e)
            1.0f
        }

        val vendors = AsrVendorUi.pairs(this)
        val cur = try {
            prefs.asrVendor
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get current vendor", e)
            AsrVendor.Volc
        }

        val entries = vendors.map { (v, name) ->
            Triple(name, v == cur) {
                try {
                    val old = try {
                        prefs.asrVendor
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to get old vendor", e)
                        AsrVendor.Volc
                    }
                    if (v != old) {
                        prefs.asrVendor = v
                        // 离开本地引擎时卸载缓存识别器，释放内存
                        if (old == AsrVendor.SenseVoice && v != AsrVendor.SenseVoice) {
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to unload SenseVoice", e)
                            }
                        }
                        if (old == AsrVendor.Paraformer && v != AsrVendor.Paraformer) {
                            try {
                                com.brycewg.asrkb.asr.unloadParaformerRecognizer()
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to unload Paraformer", e)
                            }
                        }
                        if (old == AsrVendor.Zipformer && v != AsrVendor.Zipformer) {
                            try {
                                com.brycewg.asrkb.asr.unloadZipformerRecognizer()
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to unload Zipformer", e)
                            }
                        }

                        // 切换到本地引擎且启用预加载时，触发预加载以降低首次等待
                        if (v == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this, prefs)
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to preload SenseVoice", e)
                            }
                        }
                        if (v == AsrVendor.Paraformer && prefs.pfPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadParaformerIfConfigured(this, prefs)
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to preload Paraformer", e)
                            }
                        }
                        if (v == AsrVendor.Zipformer && prefs.zfPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadZipformerIfConfigured(this, prefs)
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to preload Zipformer", e)
                            }
                        }
                    }
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to switch ASR vendor", e)
                }
                Unit
            }
        }

        vendorMenuView = menuHelper.showListPanel(
            center, alpha,
            getString(R.string.label_choose_asr_vendor),
            entries
        ) {
            vendorMenuView = null
            touchActiveGuard = false
            updateVisibilityByPref()
        }
    }

    private fun invokeImePickerFromMenu() {
        hideVendorMenu()
        invokeImePicker()
    }

    private fun enableMoveModeFromMenu() {
        stateMachine.transitionTo(FloatingBallState.MoveMode)
        hideVendorMenu()
        try {
            Toast.makeText(this, getString(R.string.toast_move_mode_on), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show move mode toast", e)
        }
    }

    private fun togglePostprocFromMenu() {
        try {
            val newVal = !prefs.postProcessEnabled
            prefs.postProcessEnabled = newVal
            val msg = getString(
                R.string.status_postproc,
                if (newVal) getString(R.string.toggle_on) else getString(R.string.toggle_off)
            )
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to toggle postproc", e)
        }
    }

    private fun showHistoryPanelFromMenu() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha", e)
            1.0f
        }

        // 读取历史文本（倒序，限制前 100 条以避免过长）
        val texts: List<String> = try {
            com.brycewg.asrkb.store.AsrHistoryStore(this)
                .listAll()
                .map { it.text }
                .filter { it.isNotBlank() }
                .take(100)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load ASR history for panel", e)
            emptyList()
        }

        vendorMenuView = menuHelper.showScrollableTextPanel(
            center,
            alpha,
            getString(R.string.btn_open_asr_history),
            if (texts.isEmpty()) listOf(getString(R.string.empty_history)) else texts,
            onItemClick = { text ->
                // 空态占位不复制
                if (text == getString(R.string.empty_history)) return@showScrollableTextPanel
                try {
                    val clipMgr = getSystemService(android.content.ClipboardManager::class.java)
                    val clip = android.content.ClipData.newPlainText("asr_history", text)
                    clipMgr?.setPrimaryClip(clip)
                    Toast.makeText(this, getString(R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to copy history text", e)
                }
            }
        ) {
            vendorMenuView = null
            touchActiveGuard = false
            updateVisibilityByPref()
        }
    }

    private fun toggleAutoStopSilenceFromMenu() {
        try {
            val newVal = !prefs.autoStopOnSilenceEnabled
            prefs.autoStopOnSilenceEnabled = newVal
            val msgRes = if (newVal) R.string.toast_silence_autostop_on else R.string.toast_silence_autostop_off
            Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to toggle silence auto-stop", e)
        }
    }

    private fun uploadClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = try {
                    mgr.uploadOnce()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to upload clipboard", t)
                    false
                }
                handler.post {
                    try {
                        Toast.makeText(
                            this@FloatingAsrService,
                            getString(if (ok) R.string.sc_status_uploaded else R.string.sc_test_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to show upload result toast", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init clipboard manager for upload", e)
        }
    }

    private fun pullClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = try {
                    mgr.pullNow(updateClipboard = true).first
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to pull clipboard", t)
                    false
                }
                handler.post {
                    try {
                        Toast.makeText(
                            this@FloatingAsrService,
                            getString(if (ok) R.string.sc_test_success else R.string.sc_test_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to show pull result toast", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init clipboard manager for pull", e)
        }
    }

    // ==================== 辅助方法 ====================

    private fun invokeImePicker() {
        try {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SettingsActivity.EXTRA_AUTO_SHOW_IME_PICKER, true)
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to invoke IME picker", e)
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Throwable) {
                Log.e(TAG, "Failed to open IME settings", e2)
            }
        }
    }

    private fun isOurImeEnabled(imm: android.view.inputmethod.InputMethodManager?): Boolean {
        val list = try {
            imm?.enabledInputMethodList
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get enabled IME list", e)
            null
        }
        if (list?.any { it.packageName == packageName } == true) return true
        return try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            val id = "$packageName/.ime.AsrKeyboardService"
            enabled?.contains(id) == true || (enabled?.split(':')?.any { it.startsWith(packageName) } == true)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check IME enabled via settings", e)
            false
        }
    }

    private fun showToast(message: String) {
        handler.post {
            try {
                currentToast?.cancel()
                val ctx = try {
                    LocaleHelper.wrap(this@FloatingAsrService)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to wrap context for toast", e)
                    this@FloatingAsrService
                }
                currentToast = Toast.makeText(ctx, message, Toast.LENGTH_SHORT)
                currentToast?.show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show toast: $message", e)
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (prefs.micHapticEnabled) {
                view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to perform haptic feedback", e)
        }
    }

    private var localPreloadTriggered: Boolean = false
    private fun tryPreloadLocalAsrOnce() {
        if (localPreloadTriggered) return
        val enabled = when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> prefs.svPreloadEnabled
            AsrVendor.Paraformer -> prefs.pfPreloadEnabled
            AsrVendor.Zipformer -> prefs.zfPreloadEnabled
            else -> false
        }
        if (!enabled) return
        if (com.brycewg.asrkb.asr.isLocalAsrPrepared(prefs)) { localPreloadTriggered = true; return }

        localPreloadTriggered = true
        serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            com.brycewg.asrkb.asr.preloadLocalAsrIfConfigured(this@FloatingAsrService, prefs)
        }
    }

    private fun mapErrorToFriendlyMessage(raw: String): String? {
        if (raw.isEmpty()) return null
        val lower = raw.lowercase(Locale.ROOT)

        // 空结果
        try {
            val emptyHints = listOf(
                getString(R.string.error_asr_empty_result),
                getString(R.string.error_audio_empty),
                "empty asr result",
                "empty audio",
                "识别返回为空",
                "空音频"
            )
            if (emptyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_empty_result)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check empty hints", e)
        }

        // HTTP 状态码
        try {
            val httpCode = Regex("HTTP\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (httpCode) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
                429 -> return getString(R.string.asr_error_auth_invalid)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse HTTP code", e)
        }

        // WebSocket code
        try {
            val code = Regex("(?:ASR\\s*Error|status|code)\\s*(\\d{3})")
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (code) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse WebSocket code", e)
        }

        // 录音权限
        try {
            val permHints = listOf(
                getString(R.string.error_record_permission_denied),
                getString(R.string.hint_need_permission),
                "record audio permission"
            )
            if (permHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_permission_denied)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check permission hints", e)
        }

        // 麦克风被占用
        try {
            val micBusyHints = listOf(
                getString(R.string.error_audio_init_failed),
                "audio recorder busy",
                "resource busy",
                "in use",
                "device busy"
            )
            if (micBusyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_in_use)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check mic busy hints", e)
        }

        // SSL/TLS 握手失败
        if (lower.contains("handshake") || lower.contains("sslhandshakeexception") ||
            lower.contains("trust anchor") || lower.contains("certificate")
        ) {
            return getString(R.string.asr_error_network_handshake)
        }

        // 网络不可用
        if (lower.contains("unable to resolve host") ||
            lower.contains("no address associated") ||
            lower.contains("failed to connect") ||
            lower.contains("connect exception") ||
            lower.contains("network is unreachable") ||
            lower.contains("software caused connection abort") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        ) {
            return getString(R.string.asr_error_network_unavailable)
        }

        return null
    }
}
