package com.brycewg.asrkb.ime

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Vibrator
import android.view.LayoutInflater
import android.graphics.Color
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.material.color.MaterialColors
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.UiColors
import com.brycewg.asrkb.UiColorTokens
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * ASR 键盘服务
 *
 * 职责：
 * - 管理键盘视图的生命周期
 * - 绑定视图事件到 KeyboardActionHandler
 * - 响应 UI 更新通知
 * - 管理系统回调（onStartInputView, onFinishInputView 等）
 * - 协调剪贴板同步等辅助功能
 *
 * 复杂的业务逻辑已拆分到：
 * - KeyboardActionHandler: 键盘动作处理和状态管理
 * - AsrSessionManager: ASR 引擎生命周期管理
 * - InputConnectionHelper: 输入连接操作封装
 * - BackspaceGestureHandler: 退格手势处理
 */
class AsrKeyboardService : InputMethodService(), KeyboardActionHandler.UiListener {

    companion object {
        const val ACTION_REFRESH_IME_UI = "com.brycewg.asrkb.action.REFRESH_IME_UI"
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    // ========== 组件实例 ==========
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var inputHelper: InputConnectionHelper
    private lateinit var asrManager: AsrSessionManager
    private lateinit var actionHandler: KeyboardActionHandler
    private lateinit var backspaceGestureHandler: BackspaceGestureHandler

    // ========== 视图引用 ==========
    private var rootView: View? = null
    private var btnMic: FloatingActionButton? = null
    private var layoutMainKeyboard: View? = null
    private var layoutAiEditPanel: View? = null
    private var layoutNumpadPanel: View? = null
    private var btnAiEditPanelBack: ImageButton? = null
    private var btnAiPanelApplyPreset: ImageButton? = null
    private var btnAiPanelCursorLeft: ImageButton? = null
    private var btnAiPanelCursorRight: ImageButton? = null
    private var btnAiPanelMoveStart: ImageButton? = null
    private var btnAiPanelMoveEnd: ImageButton? = null
    private var btnAiPanelSelect: ImageButton? = null
    private var btnAiPanelSelectAll: ImageButton? = null
    private var btnAiPanelCopy: ImageButton? = null
    private var btnAiPanelPaste: ImageButton? = null
    private var btnAiPanelUndo: ImageButton? = null
    private var btnAiPanelNumpad: ImageButton? = null
    private var btnNumpadBack: ImageButton? = null
    private var btnNumpadEnter: ImageButton? = null
    private var btnNumpadBackspace: ImageButton? = null
    private var btnNumpadPunctToggle: ImageButton? = null
    private var isAiEditPanelVisible: Boolean = false
    private var isNumpadPanelVisible: Boolean = false
    private var btnSettings: ImageButton? = null
    private var btnEnter: ImageButton? = null
    private var btnPostproc: ImageButton? = null
    private var btnAiEdit: ImageButton? = null
    private var btnBackspace: ImageButton? = null
    private var btnPromptPicker: ImageButton? = null
    private var btnHide: ImageButton? = null
    private var btnImeSwitcher: ImageButton? = null
    private var btnPunct1: TextView? = null
    private var btnPunct2: TextView? = null
    private var btnPunct3: TextView? = null
    private var btnPunct4: TextView? = null
    private var txtStatus: TextView? = null
    private var groupMicStatus: View? = null
    // 记录麦克风按下的原始Y坐标，用于检测上滑手势
    private var micDownRawY: Float = 0f
    // AI编辑面板：选择模式与锚点
    private var aiSelectMode: Boolean = false
    private var aiSelectAnchor: Int? = null

    // ========== 剪贴板和其他辅助功能 ==========
    private var clipboardPreviewTimeout: Runnable? = null
    private var prefsReceiver: BroadcastReceiver? = null
    private var syncClipboardManager: SyncClipboardManager? = null
    private var svPreloadTriggered: Boolean = false
    private var suppressReturnPrevImeOnHideOnce: Boolean = false
    // 追踪宿主选区（用于精确控制选择扩展）
    private var lastSelStart: Int = -1
    private var lastSelEnd: Int = -1
    // 光标左右移动长按连发
    private var repeatLeftRunnable: Runnable? = null
    private var repeatRightRunnable: Runnable? = null
    // 系统导航栏底部高度（用于适配 Android 15 边缘到边缘显示）
    private var systemNavBarBottomInset: Int = 0

    // ========== 生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        // 初始化组件
        inputHelper = InputConnectionHelper("AsrKeyboardService")
        asrManager = AsrSessionManager(this, serviceScope, prefs)
        actionHandler = KeyboardActionHandler(
            this,
            serviceScope,
            prefs,
            asrManager,
            inputHelper,
            LlmPostProcessor()
        )
        backspaceGestureHandler = BackspaceGestureHandler(inputHelper)

        // 设置监听器
        asrManager.setListener(actionHandler)
        actionHandler.setUiListener(this)
        actionHandler.setInputConnectionProvider { currentInputConnection }

        // 构建初始 ASR 引擎
        asrManager.rebuildEngine()

        // 监听设置变化以即时刷新键盘 UI
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_REFRESH_IME_UI) {
                    val v = rootView
                    if (v != null) {
                        applyKeyboardHeightScale(v)
                        v.requestLayout()
                    }
                }
            }
        }
        prefsReceiver = r
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                /* context = */ this,
                /* receiver = */ r,
                /* filter = */ IntentFilter(ACTION_REFRESH_IME_UI),
                /* flags = */ androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to register prefsReceiver", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asrManager.cleanup()
        serviceScope.cancel()
        try {
            syncClipboardManager?.stop()
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to stop SyncClipboardManager", e)
        }
        try {
            prefsReceiver?.let { unregisterReceiver(it) }
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to unregister prefsReceiver", e)
        }
        prefsReceiver = null
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        // IME context often uses a framework theme; wrap with our theme and Material dynamic colors.
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
        val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themedContext)
        val view = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_view, null, false)
        rootView = view

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        applyKeyboardInsets(view)

        // 查找所有视图
        bindViews(view)

        // 设置监听器
        setupListeners()

        // 应用偏好设置
        applyKeyboardHeightScale(view)
        applyPunctuationLabels()

        // 更新初始 UI 状态
        refreshPermissionUi()
        onStateChanged(actionHandler.getCurrentState())

        // 同步系统导航栏颜色
        try {
            view.post { syncSystemBarsToKeyboardBackground(view) }
        } catch (_: Throwable) { }

        // Pro：注入 IME 侧额外功能
        try { com.brycewg.asrkb.ProUiInjector.injectIntoImeKeyboard(this, view) } catch (_: Throwable) { }

        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 每次键盘视图启动时应用一次高度/底部间距等缩放，
        try {
            applyKeyboardHeightScale(rootView)
            rootView?.requestLayout()
        } catch (_: Throwable) { }
        try {
            DebugLogManager.log(
                category = "ime",
                event = "start_input_view",
                data = mapOf(
                    "pkg" to (info?.packageName ?: ""),
                    "inputType" to (info?.inputType ?: 0),
                    "imeOptions" to (info?.imeOptions ?: 0),
                    "icNull" to (currentInputConnection == null),
                    "isMultiLine" to ((info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0),
                    "actionId" to ((info?.imeOptions ?: 0) and android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
                )
            )
        } catch (t: Throwable) {
            android.util.Log.w("AsrKeyboardService", "Failed to log start_input_view", t)
        }

        // 键盘面板首次出现时，按需异步预加载本地 SenseVoice
        tryPreloadSenseVoice()


        // 刷新 UI
        btnImeSwitcher?.visibility = View.VISIBLE
        applyPunctuationLabels()
        refreshPermissionUi()
        hideAiEditPanel()
        hideNumpadPanel()
        hideNumpadPanel()
        // 如果此时引擎仍在运行（键盘收起期间继续录音），需要把 UI 恢复为 Listening
        if (asrManager.isRunning()) {
            onStateChanged(actionHandler.getCurrentState())
        }

        // Pro：再次注入以覆盖可能被标点标签刷新影响的 UI
        try { rootView?.let { com.brycewg.asrkb.ProUiInjector.injectIntoImeKeyboard(this, it) } } catch (_: Throwable) { }

        // 同步系统栏颜色
        try {
            rootView?.post { syncSystemBarsToKeyboardBackground(rootView) }
        } catch (_: Throwable) { }

        // 若正在录音，恢复中间结果为 composing
        if (asrManager.isRunning()) {
            actionHandler.restorePartialAsComposing(currentInputConnection)
        }

        // 启动剪贴板同步
        startClipboardSync()

        // 预热耳机路由（键盘显示）
        try { BluetoothRouteManager.setImeActive(this, true) } catch (t: Throwable) { android.util.Log.w("AsrKeyboardService", "BluetoothRouteManager setImeActive(true)", t) }

        // 自动启动录音（如果开启了设置）
        if (prefs.autoStartRecordingOnShow) {
            // 与手动开始保持一致的就绪性校验，避免在缺少 Key/模型时进入 Listening 状态
            if (!checkAsrReady()) {
                // refreshPermissionUi() 已在校验中处理，这里直接返回
            } else {
                // 延迟一小段时间再启动，确保键盘 UI 已完全显示
                rootView?.postDelayed({
                    try {
                        // 再次确认仍然就绪（期间用户可能改了设置/权限）
                        if (!checkAsrReady()) return@postDelayed
                        if (!asrManager.isRunning()) {
                            actionHandler.startAutoRecording()
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("AsrKeyboardService", "Failed to auto start recording", t)
                    }
                }, 100)
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        lastSelStart = newSelStart
        lastSelEnd = newSelEnd
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        try {
            DebugLogManager.log("ime", "finish_input_view")
        } catch (_: Throwable) { }
        try {
            syncClipboardManager?.stop()
        } catch (_: Throwable) { }

        hideAiEditPanel()

        // 键盘收起，解除预热（若未在录音）
        try { BluetoothRouteManager.setImeActive(this, false) } catch (t: Throwable) { android.util.Log.w("AsrKeyboardService", "BluetoothRouteManager setImeActive(false)", t) }

        // 如开启：键盘收起后自动切回上一个输入法
        if (prefs.returnPrevImeOnHide) {
            if (suppressReturnPrevImeOnHideOnce) {
                // 清除一次性抑制标记，避免连环切换
                suppressReturnPrevImeOnHideOnce = false
            } else {
                try {
                    val ok = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                    if (!ok) {
                        // 若系统未允许切回，不做额外操作
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("AsrKeyboardService", "Auto return previous IME on hide failed", e)
                }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)

        // 若正在录音，同步中间结果为 composing
        if (asrManager.isRunning()) {
            actionHandler.restorePartialAsComposing(currentInputConnection)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // 避免全屏候选，保持紧凑的麦克风键盘
        return false
    }

    // ========== KeyboardActionHandler.UiListener 实现 ==========

    override fun onStateChanged(state: KeyboardState) {
        when (state) {
            is KeyboardState.Idle -> updateUiIdle()
            is KeyboardState.Listening -> updateUiListening()
            is KeyboardState.Processing -> updateUiProcessing()
            is KeyboardState.AiProcessing -> updateUiAiProcessing()
            is KeyboardState.AiEditListening -> updateUiAiEditListening()
            is KeyboardState.AiEditProcessing -> updateUiAiEditProcessing()
        }

        // 更新中间结果到 composing
        if (state is KeyboardState.Listening && state.partialText != null) {
            currentInputConnection?.let { ic ->
                inputHelper.setComposingText(ic, state.partialText)
            }
        }
    }

    override fun onStatusMessage(message: String) {
        clearStatusTextStyle()
        txtStatus?.text = message
    }

    override fun onVibrate() {
        vibrateTick()
    }

    override fun onShowClipboardPreview(preview: ClipboardPreview) {
        val tv = txtStatus ?: return
        tv.text = preview.displaySnippet

        // 限制粘贴板内容为单行显示,避免破坏 UI 布局
        try {
            tv.maxLines = 1
            tv.isSingleLine = true
        } catch (_: Throwable) { }

        // 显示圆角遮罩
        try {
            tv.setBackgroundResource(R.drawable.bg_status_chip)
        } catch (_: Throwable) { }

        // 设置内边距
        try {
            val d = tv.resources.displayMetrics.density
            val ph = (12f * d + 0.5f).toInt()
            val pv = (4f * d + 0.5f).toInt()
            tv.setPaddingRelative(ph, pv, ph, pv)
        } catch (_: Throwable) { }

        // 启用点击粘贴
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleClipboardPreviewClick(currentInputConnection)
        }

        // 超时自动恢复
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        val r = Runnable { actionHandler.hideClipboardPreview() }
        clipboardPreviewTimeout = r
        try {
            tv.postDelayed(r, 10_000)
        } catch (_: Throwable) { }
    }

    override fun onHideClipboardPreview() {
        val tv = txtStatus ?: return
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        clipboardPreviewTimeout = null

        try {
            tv.isClickable = false
            tv.isFocusable = false
            tv.setOnClickListener(null)
            tv.background = null
            tv.setPaddingRelative(0, 0, 0, 0)
            tv.maxLines = 3
            tv.isSingleLine = false
        } catch (_: Throwable) { }

        // 恢复默认状态文案
        try {
            if (asrManager.isRunning()) {
                updateUiListening()
            } else {
                updateUiIdle()
            }
        } catch (_: Throwable) { }
    }

    // ========== 视图绑定和监听器设置 ==========

    private fun bindViews(view: View) {
        layoutMainKeyboard = view.findViewById(R.id.layoutMainKeyboard)
        layoutAiEditPanel = view.findViewById(R.id.layoutAiEditPanel)
        layoutNumpadPanel = view.findViewById(R.id.layoutNumpadPanel)
        btnAiEditPanelBack = view.findViewById(R.id.btnAiPanelBack)
        btnAiPanelApplyPreset = view.findViewById(R.id.btnAiPanelApplyPreset)
        btnAiPanelCursorLeft = view.findViewById(R.id.btnAiPanelCursorLeft)
        btnAiPanelCursorRight = view.findViewById(R.id.btnAiPanelCursorRight)
        btnAiPanelMoveStart = view.findViewById(R.id.btnAiPanelMoveStart)
        btnAiPanelMoveEnd = view.findViewById(R.id.btnAiPanelMoveEnd)
        btnAiPanelSelect = view.findViewById(R.id.btnAiPanelSelect)
        btnAiPanelSelectAll = view.findViewById(R.id.btnAiPanelSelectAll)
        btnAiPanelCopy = view.findViewById(R.id.btnAiPanelCopy)
        btnAiPanelPaste = view.findViewById(R.id.btnAiPanelPaste)
        btnAiPanelUndo = view.findViewById(R.id.btnAiPanelUndo)
        btnAiPanelNumpad = view.findViewById(R.id.btnAiPanelNumpad)
        btnNumpadBack = view.findViewById(R.id.np_btnBack)
        btnNumpadEnter = view.findViewById(R.id.np_btnEnter)
        btnNumpadBackspace = view.findViewById(R.id.np_btnBackspace)
        btnNumpadPunctToggle = view.findViewById(R.id.np_btnPunctToggle)
        isAiEditPanelVisible = layoutAiEditPanel?.visibility == View.VISIBLE
        isNumpadPanelVisible = layoutNumpadPanel?.visibility == View.VISIBLE
        btnMic = view.findViewById(R.id.btnMic)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnEnter = view.findViewById(R.id.btnEnter)
        btnPostproc = view.findViewById(R.id.btnPostproc)
        btnAiEdit = view.findViewById(R.id.btnAiEdit)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnPromptPicker = view.findViewById(R.id.btnPromptPicker)
        btnHide = view.findViewById(R.id.btnHide)
        btnImeSwitcher = view.findViewById(R.id.btnImeSwitcher)
        btnPunct1 = view.findViewById(R.id.btnPunct1)
        btnPunct2 = view.findViewById(R.id.btnPunct2)
        btnPunct3 = view.findViewById(R.id.btnPunct3)
        btnPunct4 = view.findViewById(R.id.btnPunct4)
        txtStatus = view.findViewById(R.id.txtStatus)
        groupMicStatus = view.findViewById(R.id.groupMicStatus)

        // 修复麦克风垂直位置
        var micBaseGroupHeight = -1
        groupMicStatus?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h <= 0) return@addOnLayoutChangeListener
            if (micBaseGroupHeight < 0) {
                micBaseGroupHeight = h
                btnMic?.translationY = 0f
            } else {
                val delta = h - micBaseGroupHeight
                btnMic?.translationY = (delta / 2f)
            }
        }
    }

    private fun setupListeners() {
        // AI 编辑面板返回按钮
        btnAiEditPanelBack?.setOnClickListener { v ->
            performKeyHaptic(v)
            hideAiEditPanel()
        }

        // AI 编辑面板：应用预设 Prompt 并处理文本
        btnAiPanelApplyPreset?.setOnClickListener { v ->
            performKeyHaptic(v)
            showPromptPickerForApply(v)
        }

        // AI 编辑面板：光标/选择移动
        btnAiPanelCursorLeft?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorBy(-1)
        }
        // 右移：点击一次移动一位，长按连发
        btnAiPanelCursorRight?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorBy(1)
        }
        btnAiPanelMoveStart?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorToEdge(true)
        }
        btnAiPanelMoveEnd?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorToEdge(false)
        }

        // AI 编辑面板：选择开关/全选
        btnAiPanelSelect?.setOnClickListener { v ->
            performKeyHaptic(v)
            toggleSelectionMode()
        }
        btnAiPanelSelectAll?.setOnClickListener { v ->
            performKeyHaptic(v)
            selectAllText()
        }

        // AI 编辑面板：复制/粘贴/退格（带主键盘同款手势）
        btnAiPanelCopy?.setOnClickListener { v ->
            performKeyHaptic(v)
            handleCopyAction()
        }
        btnAiPanelPaste?.setOnClickListener { v ->
            performKeyHaptic(v)
            handlePasteAction()
        }
        // 点按退格（注：手势由 onTouch 托管，onClick 多为兜底）
        btnAiPanelUndo?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendBackspace(currentInputConnection)
        }
        // 退格手势复用主键盘逻辑：
        btnAiPanelUndo?.setOnTouchListener { v, event ->
            backspaceGestureHandler.handleTouchEvent(v, event, currentInputConnection)
        }

        // AI 编辑面板：数字小键盘（占位）
        btnAiPanelNumpad?.setOnClickListener { v ->
            performKeyHaptic(v)
            showNumpadPanel()
        }

        // 数字小键盘：返回编辑面板
        btnNumpadBack?.setOnClickListener { v ->
            performKeyHaptic(v)
            hideNumpadPanel()
            showAiEditPanel()
        }

        // 数字小键盘：回车
        btnNumpadEnter?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendEnter(currentInputConnection)
        }

        // 数字小键盘：退格（位于回车上方）
        btnNumpadBackspace?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.saveUndoSnapshot(currentInputConnection)
            inputHelper.sendBackspace(currentInputConnection)
        }
        btnNumpadBackspace?.setOnTouchListener { v, ev ->
            backspaceGestureHandler.handleTouchEvent(v, ev, currentInputConnection)
        }

        // 绑定小键盘按键
        bindNumpadKeys()

        // 数字小键盘：标点中英文切换
        btnNumpadPunctToggle?.setOnClickListener { v ->
            performKeyHaptic(v)
            val newState = !prefs.numpadCnPunctEnabled
            prefs.numpadCnPunctEnabled = newState
            applyNumpadPunctMode()
        }

        // 设置光标左右移动的长按连发
        setupCursorRepeatHandlers()

        // 麦克风按钮
        btnMic?.setOnClickListener { v ->
            if (!prefs.micTapToggleEnabled) return@setOnClickListener
            performKeyHaptic(v)
            if (!checkAsrReady()) return@setOnClickListener
            try {
                DebugLogManager.log(
                    category = "ime",
                    event = "mic_click",
                    data = mapOf(
                        "tapToggle" to true,
                        "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        "running" to asrManager.isRunning()
                    )
                )
            } catch (_: Throwable) { }
            actionHandler.handleMicTapToggle()
        }

        btnMic?.setOnTouchListener { v, event ->
            if (prefs.micTapToggleEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    if (!checkAsrReady()) {
                        try {
                            DebugLogManager.log(
                                category = "ime",
                                event = "mic_down_blocked",
                                data = mapOf(
                                    "tapToggle" to false,
                                    "state" to actionHandler.getCurrentState()::class.java.simpleName
                                )
                            )
                        } catch (_: Throwable) { }
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    micDownRawY = try { event.rawY } catch (_: Throwable) { event.y }
                    try {
                        DebugLogManager.log(
                            category = "ime",
                            event = "mic_down",
                            data = mapOf(
                                "tapToggle" to false,
                                "y" to micDownRawY,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName,
                                "running" to asrManager.isRunning()
                            )
                        )
                    } catch (_: Throwable) { }
                    actionHandler.handleMicPressDown()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val slop = try { ViewConfiguration.get(v.context).scaledTouchSlop } catch (_: Throwable) { 16 }
                    val upY = try { event.rawY } catch (_: Throwable) { event.y }
                    val dy = (micDownRawY - upY)
                    val autoEnter = prefs.micSwipeUpAutoEnterEnabled && dy > slop
                    try {
                        DebugLogManager.log(
                            category = "ime",
                            event = "mic_up",
                            data = mapOf(
                                "tapToggle" to false,
                                "dy" to dy,
                                "slop" to slop,
                                "autoEnter" to autoEnter,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName,
                                "running" to asrManager.isRunning()
                            )
                        )
                    } catch (_: Throwable) { }
                    actionHandler.handleMicPressUp(autoEnter)
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    try {
                        DebugLogManager.log(
                            category = "ime",
                            event = "mic_cancel",
                            data = mapOf(
                                "tapToggle" to false,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName,
                                "running" to asrManager.isRunning()
                            )
                        )
                    } catch (_: Throwable) { }
                    actionHandler.handleMicPressUp(false)
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        // 顶部左侧按钮（原 Prompt 切换）改为：进入 AI 编辑面板
        btnPromptPicker?.setOnClickListener { v ->
            performKeyHaptic(v)
            if (!hasRecordAudioPermission()) {
                refreshPermissionUi()
                return@setOnClickListener
            }
            if (!prefs.hasAsrKeys()) {
                clearStatusTextStyle()
                txtStatus?.text = getString(R.string.hint_need_keys)
                return@setOnClickListener
            }
            if (!prefs.hasLlmKeys()) {
                clearStatusTextStyle()
                txtStatus?.text = getString(R.string.hint_need_llm_keys)
                return@setOnClickListener
            }
            showAiEditPanel()
        }

        // 顶部行：后处理开关（魔杖）
        btnPostproc?.apply {
            try {
                setImageResource(if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand)
            } catch (e: Throwable) {
                android.util.Log.w("AsrKeyboardService", "Failed to set postproc icon (init)", e)
            }
            setOnClickListener { v ->
                performKeyHaptic(v)
                actionHandler.handlePostprocessToggle()
                try {
                    setImageResource(if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand)
                } catch (e: Throwable) {
                    android.util.Log.w("AsrKeyboardService", "Failed to set postproc icon (toggle)", e)
                }
            }
        }

        // 退格按钮（委托给手势处理器）
        btnBackspace?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendBackspace(currentInputConnection)
        }

        btnBackspace?.setOnTouchListener { v, event ->
            backspaceGestureHandler.handleTouchEvent(v, event, currentInputConnection)
        }

        // 设置退格手势监听器
        backspaceGestureHandler.setListener(object : BackspaceGestureHandler.Listener {
            override fun onSingleDelete() {
                actionHandler.saveUndoSnapshot(currentInputConnection)
                inputHelper.sendBackspace(currentInputConnection)
            }

            override fun onClearAll() {
                // 强制以清空前的文本作为撤销快照
                actionHandler.saveUndoSnapshot(currentInputConnection, force = true)
            }

            override fun onUndo() {
                actionHandler.handleUndo(currentInputConnection)
            }

            override fun onVibrateRequest() {
                vibrateTick()
            }
        })

        // 其他按钮
        btnSettings?.setOnClickListener { v ->
            performKeyHaptic(v)
            openSettings()
        }

        btnEnter?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendEnter(currentInputConnection)
        }

        btnHide?.setOnClickListener { v ->
            performKeyHaptic(v)
            hideKeyboardPanel()
        }

        // 覆盖行按钮：Prompt 选择（article）
        btnImeSwitcher?.setOnClickListener { v ->
            performKeyHaptic(v)
            showPromptPicker(v)
        }

        // 中间功能行按钮（现为键盘切换）
        btnAiEdit?.setOnClickListener { v ->
            performKeyHaptic(v)
            if (prefs.fcitx5ReturnOnImeSwitch) {
                try {
                    if (asrManager.isRunning()) asrManager.stopRecording()
                } catch (_: Throwable) { }
                suppressReturnPrevImeOnHideOnce = true
                val switched = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                if (!switched) {
                    showImePicker()
                }
            } else {
                showImePicker()
            }
        }

        // 标点按钮
        btnPunct1?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct1)
        }
        btnPunct2?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct2)
        }
        btnPunct3?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct3)
        }
        btnPunct4?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct4)
        }
    }

    private fun showAiEditPanel() {
        if (isAiEditPanelVisible) return
        // 如果数字小键盘当前展示，先隐藏它
        layoutNumpadPanel?.visibility = View.GONE
        isNumpadPanelVisible = false
        layoutMainKeyboard?.visibility = View.GONE
        layoutAiEditPanel?.visibility = View.VISIBLE
        isAiEditPanelVisible = true
        // 进入面板时重置选择模式
        aiSelectMode = false
        aiSelectAnchor = null
        try {
            btnAiPanelSelect?.isSelected = false
            btnAiPanelSelect?.setImageResource(R.drawable.selection_toggle)
        } catch (_: Throwable) { }
    }

    private fun hideAiEditPanel() {
        layoutAiEditPanel?.visibility = View.GONE
        layoutMainKeyboard?.visibility = View.VISIBLE
        isAiEditPanelVisible = false
        // 离开面板时重置选择模式
        aiSelectMode = false
        aiSelectAnchor = null
        try {
            btnAiPanelSelect?.isSelected = false
            btnAiPanelSelect?.setImageResource(R.drawable.selection_toggle)
        } catch (_: Throwable) { }
        // 释放可能仍在队列中的光标连发回调，避免隐藏后仍触发
        releaseCursorRepeatCallbacks()
    }

    private fun showNumpadPanel() {
        if (isNumpadPanelVisible) return
        // 隐藏编辑面板但不显示主键盘
        layoutAiEditPanel?.visibility = View.GONE
        isAiEditPanelVisible = false
        // 取消编辑面板可能仍在的光标连续回调
        releaseCursorRepeatCallbacks()
        layoutNumpadPanel?.visibility = View.VISIBLE
        isNumpadPanelVisible = true
        // 数字/符号面板不需要显示麦克风悬浮按钮，避免遮挡
        try { groupMicStatus?.visibility = View.GONE } catch (_: Throwable) { }
        applyNumpadPunctMode()
    }

    private fun hideNumpadPanel() {
        layoutNumpadPanel?.visibility = View.GONE
        isNumpadPanelVisible = false
        // 还原麦克风悬浮按钮可见性
        try { groupMicStatus?.visibility = View.VISIBLE } catch (_: Throwable) { }
    }

    private fun releaseCursorRepeatCallbacks() {
        try {
            repeatLeftRunnable?.let { btnAiPanelCursorLeft?.removeCallbacks(it) }
            repeatLeftRunnable = null
            repeatRightRunnable?.let { btnAiPanelCursorRight?.removeCallbacks(it) }
            repeatRightRunnable = null
        } catch (_: Throwable) { }
    }

    // ========== UI 更新方法 ==========

    private fun updateUiIdle() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_idle)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (idle)", e) }
        try { btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (idle)", e) }
        currentInputConnection?.let { inputHelper.finishComposingText(it) }
    }

    private fun updateUiListening() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_listening)
        btnMic?.isSelected = true
        try { btnMic?.setImageResource(R.drawable.microphone_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (listening)", e) }
        try { btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (listening)", e) }
    }

    private fun updateUiProcessing() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_recognizing)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (processing)", e) }
        try { btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (processing)", e) }
    }

    private fun updateUiAiProcessing() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_ai_processing)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (ai processing)", e) }
        try { btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (ai processing)", e) }
    }

    private fun updateUiAiEditListening() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_ai_edit_listening)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (ai edit listening)", e) }
        try { btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (ai edit listening)", e) }
    }

    private fun updateUiAiEditProcessing() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_ai_editing)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (ai edit processing)", e) }
        try { btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (ai edit processing)", e) }
    }

    // ========== 辅助方法 ==========

    /**
     * 清除状态文本的粘贴板预览样式（背景遮罩、内边距、点击监听器、单行限制）
     * 确保普通状态文本不会显示粘贴板预览的样式
     */
    private fun clearStatusTextStyle() {
        val tv = txtStatus ?: return
        try {
            tv.isClickable = false
            tv.isFocusable = false
            tv.setOnClickListener(null)
            tv.background = null
            tv.setPaddingRelative(0, 0, 0, 0)
            // 恢复多行显示
            tv.maxLines = 3
            tv.isSingleLine = false
        } catch (_: Throwable) { }
    }

    // ========== AI 编辑面板：辅助方法 ==========

    private fun currentCursorPosition(): Int? {
        val ic = currentInputConnection ?: return null
        // 优先使用宿主回调的选区，选择模式下返回“活动端”（非锚点）
        val selStart = lastSelStart
        val selEnd = lastSelEnd
        if (selStart >= 0 && selEnd >= 0) {
            val anchor = aiSelectAnchor
            if (aiSelectMode && anchor != null && selStart != selEnd) {
                return if (anchor == selStart) selEnd else selStart
            }
            // 无选择或未进入选择模式：按光标位置（等同于 selEnd）
            return selEnd
        }
        // 兜底：通过 beforeCursor 长度推断
        return try { inputHelper.getTextBeforeCursor(ic, 10000)?.length } catch (_: Throwable) { null }
    }

    private fun totalTextLength(): Int? {
        val ic = currentInputConnection ?: return null
        return try {
            val before = inputHelper.getTextBeforeCursor(ic, 10000)?.length ?: 0
            val after = inputHelper.getTextAfterCursor(ic, 10000)?.length ?: 0
            before + after
        } catch (_: Throwable) { null }
    }

    private fun ensureAnchorForSelection() {
        if (!aiSelectMode) return
        if (aiSelectAnchor != null) return
        val ic = currentInputConnection ?: return
        // 若当前已有选区，优先使用宿主通知的起点作为锚点；否则以当前光标位置为锚点
        if (lastSelStart >= 0 && lastSelEnd >= 0 && lastSelStart != lastSelEnd) {
            aiSelectAnchor = minOf(lastSelStart, lastSelEnd)
            return
        }
        val beforeLen = try { inputHelper.getTextBeforeCursor(ic, 10000)?.length ?: 0 } catch (_: Throwable) { 0 }
        aiSelectAnchor = beforeLen
    }

    private fun moveCursorBy(delta: Int) {
        val ic = currentInputConnection ?: return
        val pos = currentCursorPosition() ?: return
        val newPos = (pos + delta).coerceAtLeast(0)

        if (aiSelectMode) {
            ensureAnchorForSelection()
            val anchor = aiSelectAnchor ?: 0
            val start = minOf(anchor, newPos)
            val end = maxOf(anchor, newPos)
            inputHelper.setSelection(ic, start, end)
        } else {
            inputHelper.setSelection(ic, newPos, newPos)
        }
    }

    private fun moveCursorToEdge(toStart: Boolean) {
        val ic = currentInputConnection ?: return
        val newPos = if (toStart) 0 else (totalTextLength() ?: Int.MAX_VALUE)
        if (aiSelectMode) {
            ensureAnchorForSelection()
            val anchor = aiSelectAnchor ?: 0
            val start = minOf(anchor, newPos)
            val end = maxOf(anchor, newPos)
            inputHelper.setSelection(ic, start, end)
        } else {
            inputHelper.setSelection(ic, newPos, newPos)
        }
    }

    private fun toggleSelectionMode() {
        aiSelectMode = !aiSelectMode
        btnAiPanelSelect?.isSelected = aiSelectMode
        try {
            btnAiPanelSelect?.setImageResource(if (aiSelectMode) R.drawable.selection_fill else R.drawable.selection_toggle)
        } catch (_: Throwable) { }
        if (aiSelectMode) {
            // 进入选择模式立即固定锚点
            aiSelectAnchor = null
            ensureAnchorForSelection()
        } else {
            // 退出选择模式清除锚点
            aiSelectAnchor = null
        }
    }

    private fun selectAllText() {
        val ic = currentInputConnection ?: return
        inputHelper.selectAll(ic)
        // 关闭选择模式（避免后续移动混淆）
        aiSelectMode = false
        btnAiPanelSelect?.isSelected = false
    }

    private fun handleCopyAction() {
        val ic = currentInputConnection
        if (ic == null) return
        // 优先用宿主提供的 ContextMenu Action
        val ok = try { ic.performContextMenuAction(android.R.id.copy) } catch (t: Throwable) {
            android.util.Log.w("AsrKeyboardService", "performContextMenuAction(COPY) failed", t)
            false
        }
        if (!ok) {
            // 回退到直接写剪贴板
            try {
                val selected = inputHelper.getSelectedText(ic, 0)?.toString()
                if (!selected.isNullOrEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ASR Copy", selected)
                    cm.setPrimaryClip(clip)
                }
            } catch (t: Throwable) {
                android.util.Log.e("AsrKeyboardService", "Fallback copy failed", t)
            }
        }
    }

    private fun handlePasteAction() {
        val ic = currentInputConnection
        if (ic == null) return
        // 变更前记录撤销快照
        actionHandler.saveUndoSnapshot(ic)
        val ok = try { ic.performContextMenuAction(android.R.id.paste) } catch (t: Throwable) {
            android.util.Log.w("AsrKeyboardService", "performContextMenuAction(PASTE) failed", t)
            false
        }
        if (!ok) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrEmpty()) {
                    inputHelper.commitText(ic, text)
                }
            } catch (t: Throwable) {
                android.util.Log.e("AsrKeyboardService", "Fallback paste failed", t)
            }
        }
    }

    private fun showPromptPickerForApply(anchor: View) {
        try {
            val presets = prefs.getPromptPresets()
            if (presets.isEmpty()) return
            val popup = androidx.appcompat.widget.PopupMenu(anchor.context, anchor)
            presets.forEachIndexed { idx, p ->
                popup.menu.add(0, idx, idx, p.title)
            }
            popup.setOnMenuItemClickListener { mi ->
                val position = mi.itemId
                val preset = presets.getOrNull(position) ?: return@setOnMenuItemClickListener false
                // 直接应用选中的预设内容，不更改全局激活项
                actionHandler.applyPromptToSelectionOrAll(currentInputConnection, promptContent = preset.content)
                true
            }
            popup.show()
        } catch (_: Throwable) { }
    }

    private fun setupCursorRepeatHandlers() {
        val initialDelay = 350L
        val repeatInterval = 50L

        btnAiPanelCursorLeft?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    moveCursorBy(-1)
                    repeatLeftRunnable?.let { v.removeCallbacks(it) }
                    val r = Runnable {
                        moveCursorBy(-1)
                        repeatLeftRunnable?.let { v.postDelayed(it, repeatInterval) }
                    }
                    repeatLeftRunnable = r
                    v.postDelayed(r, initialDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatLeftRunnable?.let { v.removeCallbacks(it) }
                    repeatLeftRunnable = null
                    true
                }
                else -> false
            }
        }

        btnAiPanelCursorRight?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    moveCursorBy(1)
                    repeatRightRunnable?.let { v.removeCallbacks(it) }
                    val r = Runnable {
                        moveCursorBy(1)
                        repeatRightRunnable?.let { v.postDelayed(it, repeatInterval) }
                    }
                    repeatRightRunnable = r
                    v.postDelayed(r, initialDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatRightRunnable?.let { v.removeCallbacks(it) }
                    repeatRightRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    private fun bindNumpadKeys() {
        val root = layoutNumpadPanel ?: return
        fun bindView(v: View) {
            val tag = try { v.tag as? String } catch (_: Throwable) { null }
            if (tag == "key40" && v is TextView) {
                v.setOnClickListener { btn ->
                    performKeyHaptic(btn)
                    val ic = currentInputConnection
                    if (ic == null) return@setOnClickListener
                    val text = when (v.id) {
                        R.id.np_key_space -> " "
                        else -> v.text?.toString() ?: ""
                    }
                    if (text.isNotEmpty()) {
                        actionHandler.commitText(ic, text)
                    }
                }
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    bindView(v.getChildAt(i))
                }
            }
        }
        bindView(root)
    }

    private fun applyNumpadPunctMode() {
        val root = layoutNumpadPanel ?: return
        val cn = try { prefs.numpadCnPunctEnabled } catch (_: Throwable) { true }
        // 更新底栏按钮图标
        try {
            btnNumpadPunctToggle?.setImageResource(if (cn) R.drawable.translate_fill else R.drawable.translate)
        } catch (_: Throwable) { }
        // 行1：10 个按钮
        val row1 = try { root.findViewById<android.view.View>(R.id.rowPunct1) as? android.view.ViewGroup } catch (_: Throwable) { null }
        val row2 = try { root.findViewById<android.view.View>(R.id.rowPunct2) as? android.view.ViewGroup } catch (_: Throwable) { null }
        val cn1 = arrayOf("，","。","、","！","？","：","；","“","”","@")
        val en1 = arrayOf(",",".",",","!","?",":",";","\"","\"","@")
        if (row1 != null) {
            val arr = if (cn) cn1 else en1
            val count = minOf(row1.childCount, arr.size)
            var idx = 0
            for (i in 0 until count) {
                val tv = row1.getChildAt(i)
                if (tv is TextView) {
                    tv.text = arr[idx]
                    idx++
                }
            }
        }
        // 行2：前 8 个为标点，后面为退格
        val cn2 = arrayOf("（","）","[","]","{","}","/","`")
        val en2 = arrayOf("(",")","[","]","{","}","/","`")
        if (row2 != null) {
            val arr = if (cn) cn2 else en2
            val count = minOf(row2.childCount, arr.size)
            var idx = 0
            for (i in 0 until count) {
                val v = row2.getChildAt(i)
                if (v is TextView) {
                    v.text = arr[idx]
                    idx++
                }
            }
        }
    }

    override fun onShowRetryChip(label: String) {
        val tv = txtStatus ?: return
        tv.text = label
        // 使用与剪贴板预览相同的芯片样式
        try { tv.setBackgroundResource(R.drawable.bg_status_chip) } catch (_: Throwable) { }
        try {
            val d = tv.resources.displayMetrics.density
            val ph = (12f * d + 0.5f).toInt()
            val pv = (4f * d + 0.5f).toInt()
            tv.setPaddingRelative(ph, pv, ph, pv)
        } catch (_: Throwable) { }
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleRetryClick()
        }
    }

    override fun onHideRetryChip() {
        clearStatusTextStyle()
    }

    private fun checkAsrReady(): Boolean {
        if (!hasRecordAudioPermission()) {
            refreshPermissionUi()
            try { DebugLogManager.log("ime", "asr_not_ready", mapOf("reason" to "perm")) } catch (_: Throwable) { }
            return false
        }
        if (!prefs.hasAsrKeys()) {
            refreshPermissionUi()
            try { DebugLogManager.log("ime", "asr_not_ready", mapOf("reason" to "keys")) } catch (_: Throwable) { }
            return false
        }
        if (prefs.asrVendor == AsrVendor.SenseVoice) {
            val prepared = try {
                com.brycewg.asrkb.asr.isSenseVoicePrepared()
            } catch (_: Throwable) {
                false
            }
            if (!prepared) {
                val base = try {
                    getExternalFilesDir(null)
                } catch (_: Throwable) {
                    null
                } ?: filesDir
                val probeRoot = java.io.File(base, "sensevoice")
                val variant = try {
                    prefs.svModelVariant
                } catch (_: Throwable) {
                    "small-int8"
                }
                val variantDir = if (variant == "small-full") {
                    java.io.File(probeRoot, "small-full")
                } else {
                    java.io.File(probeRoot, "small-int8")
                }
                val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir)
                    ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
                if (found == null) {
                    clearStatusTextStyle()
                    txtStatus?.text = getString(R.string.error_sensevoice_model_missing)
                    return false
                }
            }
        }
        // 确保引擎匹配当前模式
        asrManager.ensureEngineMatchesMode()
        return true
    }

    private fun refreshPermissionUi() {
        clearStatusTextStyle()
        val granted = hasRecordAudioPermission()
        val hasKeys = prefs.hasAsrKeys()
        if (!granted) {
            btnMic?.isEnabled = false
            txtStatus?.text = getString(R.string.hint_need_permission)
        } else if (!hasKeys) {
            btnMic?.isEnabled = false
            txtStatus?.text = getString(R.string.hint_need_keys)
        } else {
            btnMic?.isEnabled = true
            txtStatus?.text = getString(R.string.status_idle)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }


    private fun applyPunctuationLabels() {
        btnPunct1?.text = prefs.punct1
        btnPunct2?.text = prefs.punct2
        btnPunct3?.text = prefs.punct3
        btnPunct4?.text = prefs.punct4
    }

    private fun vibrateTick() {
        if (!prefs.micHapticEnabled) return
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(android.os.VibrationEffect.createOneShot(20, 50))
        } catch (_: Exception) { }
    }

    private fun performKeyHaptic(view: View?) {
        if (!prefs.micHapticEnabled) return
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) { }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun hideKeyboardPanel() {
        if (asrManager.isRunning()) {
            asrManager.stopRecording()
        }
        updateUiIdle()
        try {
            requestHideSelf(0)
        } catch (_: Exception) { }
    }

    private fun showImePicker() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        } catch (_: Exception) { }
    }

    private fun showPromptPicker(anchor: View) {
        try {
            val presets = prefs.getPromptPresets()
            if (presets.isEmpty()) return
            val popup = androidx.appcompat.widget.PopupMenu(anchor.context, anchor)
            presets.forEachIndexed { idx, p ->
                val item = popup.menu.add(0, idx, idx, p.title)
                item.isCheckable = true
                if (p.id == prefs.activePromptId) item.isChecked = true
            }
            popup.menu.setGroupCheckable(0, true, true)
            popup.setOnMenuItemClickListener { mi ->
                val position = mi.itemId
                val preset = presets.getOrNull(position) ?: return@setOnMenuItemClickListener false
                prefs.activePromptId = preset.id
                clearStatusTextStyle()
                txtStatus?.text = getString(R.string.switched_preset, preset.title)
                true
            }
            popup.show()
        } catch (_: Throwable) { }
    }

    private fun tryPreloadSenseVoice() {
        try {
            if (!svPreloadTriggered) {
                val p = prefs
                if (p.asrVendor == AsrVendor.SenseVoice && p.svPreloadEnabled) {
                    val prepared = try {
                        com.brycewg.asrkb.asr.isSenseVoicePrepared()
                    } catch (_: Throwable) {
                        false
                    }
                    if (!prepared) {
                        try {
                            rootView?.post {
                                clearStatusTextStyle()
                                txtStatus?.text = getString(R.string.sv_loading_model)
                            }
                        } catch (_: Throwable) { }
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                                    this@AsrKeyboardService,
                                    p,
                                    onLoadStart = null,
                                    onLoadDone = {
                                        try {
                                            rootView?.post {
                                                clearStatusTextStyle()
                                                txtStatus?.text = getString(R.string.sv_model_ready)
                                                rootView?.postDelayed({
                                                    clearStatusTextStyle()
                                                    if (asrManager.isRunning()) {
                                                        txtStatus?.text = getString(R.string.status_listening)
                                                    } else {
                                                        txtStatus?.text = getString(R.string.status_idle)
                                                    }
                                                }, 1200)
                                            }
                                        } catch (_: Throwable) { }
                                    },
                                    suppressToastOnStart = true
                                )
                            } catch (_: Throwable) { }
                        }
                    }
                }
                svPreloadTriggered = true
            }
        } catch (_: Throwable) { }
    }

    private fun startClipboardSync() {
        try {
            if (prefs.syncClipboardEnabled) {
                if (syncClipboardManager == null) {
                    syncClipboardManager = SyncClipboardManager(
                        this,
                        prefs,
                        serviceScope,
                        object : SyncClipboardManager.Listener {
                            override fun onPulledNewContent(text: String) {
                                try {
                                    rootView?.post { actionHandler.showClipboardPreview(text) }
                                } catch (_: Throwable) { }
                            }

                            override fun onUploadSuccess() {
                                try {
                                    rootView?.post {
                                        clearStatusTextStyle()
                                        txtStatus?.text = getString(R.string.sc_status_uploaded)
                                    }
                                } catch (_: Throwable) { }
                            }
                        }
                    )
                }
                syncClipboardManager?.start()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        syncClipboardManager?.proactiveUploadIfChanged()
                    } catch (_: Throwable) { }
                    try {
                        syncClipboardManager?.pullNow(true)
                    } catch (_: Throwable) { }
                }
            } else {
                syncClipboardManager?.stop()
            }
        } catch (_: Throwable) { }
    }

    /**
     * 应用 Window Insets 以适配 Android 15 边缘到边缘显示
     */
    private fun applyKeyboardInsets(view: View) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemNavBarBottomInset = insets.bottom
            // 重新应用键盘高度缩放以更新底部 padding
            applyKeyboardHeightScale(v)
            windowInsets
        }
    }

    private fun applyKeyboardHeightScale(view: View?) {
        if (view == null) return
        val tier = try {
            prefs.keyboardHeightTier
        } catch (_: Throwable) {
            1
        }
        val scale = when (tier) {
            2 -> 1.15f
            3 -> 1.30f
            else -> 1.0f
        }

        fun dp(v: Float): Int {
            val d = view.resources.displayMetrics.density
            return (v * d + 0.5f).toInt()
        }

        // 应用底部间距（无论是否缩放都需要）
        try {
            val fl = view as? android.widget.FrameLayout
            if (fl != null) {
                val ps = fl.paddingStart
                val pe = fl.paddingEnd
                val pt = dp(8f * scale)
                val basePb = dp(12f * scale)
                // 添加用户设置的底部间距
                val extraPadding = try {
                    dp(prefs.keyboardBottomPaddingDp.toFloat())
                } catch (_: Throwable) {
                    0
                }
                // 添加系统导航栏高度以适配 Android 15 边缘到边缘显示
                val pb = basePb + extraPadding + systemNavBarBottomInset
                fl.setPaddingRelative(ps, pt, pe, pb)
            }
        } catch (_: Throwable) { }

        // 如果没有缩放，不需要调整按钮大小
        if (scale == 1.0f) return

        try {
            val topRow = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rowTop)
            if (topRow != null) {
                val lp = topRow.layoutParams
                lp.height = dp(80f * scale)
                topRow.layoutParams = lp
            }
        } catch (_: Throwable) { }

        // 使主键盘功能行（overlay）从顶部锚定，避免垂直居中导致的像素舍入抖动
        // 计算规则：将原本居中位置换算为等效的顶部边距
        // 总高约为 80s(top) + 12(margin) + 40s(punct)，中心到顶部距离为 (总高/2 - overlayHalf)
        // overlayHalf=20s，化简后顶边距=40s + 6
        try {
            val overlay = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rowOverlay)
            if (overlay != null) {
                val lp = overlay.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    lp.topMargin = dp(40f * scale + 6f)
                    lp.gravity = android.view.Gravity.TOP
                    overlay.layoutParams = lp
                }
            }
        } catch (_: Throwable) { }

        fun scaleSquareButton(id: Int) {
            try {
                val v = view.findViewById<View>(id) ?: return
                val lp = v.layoutParams
                lp.width = dp(40f * scale)
                lp.height = dp(40f * scale)
                v.layoutParams = lp
            } catch (_: Throwable) { }
        }
        fun scaleChildrenByTag(root: View?, tag: String) {
            if (root == null) return
            if (root is android.view.ViewGroup) {
                for (i in 0 until root.childCount) {
                    scaleChildrenByTag(root.getChildAt(i), tag)
                }
            }
            val t = try { root.tag as? String } catch (_: Throwable) { null }
            if (t == tag) {
                try {
                    val lp = root.layoutParams
                    lp.height = dp(40f * scale)
                    // 宽度可能由权重控制，不强制写入
                    root.layoutParams = lp
                } catch (_: Throwable) { }
            }
        }

        val ids40 = intArrayOf(
            // 主键盘按钮
            R.id.btnHide, R.id.btnPostproc, R.id.btnBackspace, R.id.btnPromptPicker,
            R.id.btnSettings, R.id.btnImeSwitcher, R.id.btnEnter, R.id.btnAiEdit,
            R.id.btnPunct1, R.id.btnPunct2, R.id.btnPunct3, R.id.btnPunct4,
            // AI 编辑面板按钮
            R.id.btnAiPanelBack, R.id.btnAiPanelApplyPreset,
            R.id.btnAiPanelCursorLeft, R.id.btnAiPanelCursorRight,
            R.id.btnAiPanelNumpad, R.id.btnAiPanelSelect,
            R.id.btnAiPanelSelectAll, R.id.btnAiPanelCopy,
            R.id.btnAiPanelUndo, R.id.btnAiPanelPaste,
            R.id.btnAiPanelMoveStart, R.id.btnAiPanelMoveEnd
        )
        ids40.forEach { scaleSquareButton(it) }

        // 数字/标点小键盘的方形按键（通过 tag="key40" 统一缩放高度）
        try {
            scaleChildrenByTag(layoutNumpadPanel, "key40")
        } catch (_: Throwable) { }

        try {
            btnMic?.customSize = dp(72f * scale)
        } catch (_: Throwable) { }

        try {
            val tv = view.findViewById<TextView>(R.id.txtStatus)
            val lp = tv?.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (lp != null) {
                lp.marginStart = dp(90f * scale)
                lp.marginEnd = dp(90f * scale)
                tv.layoutParams = lp
            }
        } catch (_: Throwable) { }
    }

    private fun resolveKeyboardSurfaceColor(from: View? = null): Int {
        val ctx = from?.context ?: this
        return try {
            UiColors.get(ctx, UiColorTokens.kbdContainerBg)
        } catch (_: Throwable) {
            // 使用 Material3 标准浅色 Surface 作为最终回退
            0xFFFFFBFE.toInt()
        }
    }

    @Suppress("DEPRECATION")
    private fun syncSystemBarsToKeyboardBackground(anchorView: View? = null) {
        val w = window?.window ?: return
        val color = resolveKeyboardSurfaceColor(anchorView)
        try {
            w.navigationBarColor = color
        } catch (_: Throwable) { }
        val isLight = try {
            ColorUtils.calculateLuminance(color) > 0.5
        } catch (_: Throwable) {
            false
        }
        val controller = WindowInsetsControllerCompat(w, anchorView ?: w.decorView)
        controller.isAppearanceLightNavigationBars = isLight
    }
}
