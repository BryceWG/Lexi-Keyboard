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
import android.view.ContextThemeWrapper
import android.graphics.Color
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.ImageButton
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ProUiInjector
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.ui.AsrVendorUi
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
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.store.debug.DebugLogManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu

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
    // 数字/符号面板返回目标：true 表示返回到 AI 编辑面板；false 表示返回到主键盘
    private var numpadReturnToAiPanel: Boolean = false
    private var appliedCustomColorOverlay: String = ""
    private var pendingCustomColorRefresh: Boolean = false
    private var isRebuildingCustomColors: Boolean = false
    private var imeViewVisible: Boolean = false
    private var btnSettings: ImageButton? = null
    private var btnEnter: ImageButton? = null
    private var btnPostproc: ImageButton? = null
    private var btnAiEdit: ImageButton? = null
    private var btnBackspace: ImageButton? = null
    private var btnPromptPicker: ImageButton? = null
    private var btnHide: ImageButton? = null
    private var btnImeSwitcher: ImageButton? = null
    private var btnPunct1: ImageButton? = null
    private var btnPunct2: com.brycewg.asrkb.ui.widgets.PunctKeyView? = null
    private var btnPunct3: com.brycewg.asrkb.ui.widgets.PunctKeyView? = null
    private var btnPunct4: ImageButton? = null
    private var rowExtension: ConstraintLayout? = null
    private var btnExt1: ImageButton? = null
    private var btnExt2: ImageButton? = null
    private var btnExt3: ImageButton? = null
    private var btnExt4: ImageButton? = null
    private var btnExtCenter1: View? = null  // 容器（FrameLayout），包含文字和波形视图
    private var txtStatusText: TextView? = null  // 状态文字显示
    private var waveformView: com.brycewg.asrkb.ui.widgets.WaveformView? = null  // 实时波形动画
    private var btnExtCenter2: Button? = null
    private var txtStatus: TextView? = null  // 已隐藏，状态改用txtStatusText显示
    private var groupMicStatus: View? = null
    // 记录麦克风容器基线高度与上次应用的缩放，避免缩放后沿用旧高度造成偏移
    private var micBaseGroupHeight: Int = -1
    private var lastAppliedHeightScale: Float = 1.0f
    // 记录麦克风按下的原始Y坐标，用于检测上滑手势
    private var micDownRawY: Float = 0f
    // AI编辑面板：选择模式与锚点
    private var aiSelectMode: Boolean = false
    private var aiSelectAnchor: Int? = null

    // ========== 剪贴板和其他辅助功能 ==========
    private var clipboardPreviewTimeout: Runnable? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipboardChangeListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    @Volatile private var lastShownClipboardHash: String? = null
    private var prefsReceiver: BroadcastReceiver? = null
    private var syncClipboardManager: SyncClipboardManager? = null
    // 本地模型首次出现预热仅触发一次
    private var localPreloadTriggered: Boolean = false
    private var suppressReturnPrevImeOnHideOnce: Boolean = false
    // 追踪宿主选区（用于精确控制选择扩展）
    private var lastSelStart: Int = -1
    private var lastSelEnd: Int = -1
    // 光标左右移动长按连发
    private var repeatLeftRunnable: Runnable? = null
    private var repeatRightRunnable: Runnable? = null
    // 系统导航栏底部高度（用于适配 Android 15 边缘到边缘显示）
    private var systemNavBarBottomInset: Int = 0
    // 记录最近一次在 IME 内弹出菜单的时间，用于限制“防误收起”逻辑的作用窗口
    private var lastPopupMenuShownAt: Long = 0L

    // ========== 剪贴板面板 ==========
    private var layoutClipboardPanel: View? = null
    private var clipBtnBack: ImageButton? = null
    private var clipBtnDelete: ImageButton? = null
    private var clipTxtCount: TextView? = null
    private var clipList: RecyclerView? = null
    private var clipAdapter: ClipboardPanelAdapter? = null
    private var isClipboardPanelVisible = false
    private var clipStore: ClipboardHistoryStore? = null

    // ========== 生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        appliedCustomColorOverlay = prefs.proCustomColorOverlay

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
                when (intent?.action) {
                    ACTION_REFRESH_IME_UI -> {
                        val v = rootView
                        if (v != null) {
                            applyKeyboardHeightScale(v)
                            applyExtensionButtonConfig()
                            v.requestLayout()
                            // 第二次异步重算，确保尺寸变化与父容器测量完成后 padding/overlay 位置也被同步
                            v.post {
                                applyKeyboardHeightScale(v)
                                v.requestLayout()
                            }
                        }
                        ensureCustomColorsSynced()
                    }
                    ProUiInjector.ACTION_PRO_CUSTOM_COLORS_CHANGED -> {
                        pendingCustomColorRefresh = true
                        ensureCustomColorsSynced()
                    }
                }
            }
        }
        prefsReceiver = r
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                /* context = */ this,
                /* receiver = */ r,
                /* filter = */ IntentFilter().apply {
                    addAction(ACTION_REFRESH_IME_UI)
                    addAction(ProUiInjector.ACTION_PRO_CUSTOM_COLORS_CHANGED)
                },
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
        val view = createKeyboardView()
        appliedCustomColorOverlay = prefs.proCustomColorOverlay
        pendingCustomColorRefresh = false
        return view
    }

  private fun createKeyboardView(): View {
    val themedContext = ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
    val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themedContext)
    val coloredContext = ProUiInjector.wrapContextWithProColors(dynamicContext)
    val view = LayoutInflater.from(coloredContext).inflate(R.layout.keyboard_view, null, false)
    return setupKeyboardView(view)
  }

  private fun setupKeyboardView(view: View): View {
    rootView = view

    // 根据主题动态调整键盘背景色，使其略浅于当前容器色但仍明显深于普通按键与麦克风按钮
    applyKeyboardBackgroundColor(view)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        applyKeyboardInsets(view)

        // 查找所有视图
        bindViews(view)

        // 设置监听器
        setupListeners()

        // 应用偏好设置
        applyKeyboardHeightScale(view)
        applyPunctuationLabels()
        applyExtensionButtonConfig()

        // 更新初始 UI 状态
        refreshPermissionUi()
        onStateChanged(actionHandler.getCurrentState())

        // 同步系统导航栏颜色
        view.post { syncSystemBarsToKeyboardBackground(view) }

        // Pro：注入 IME 侧额外功能
        try { ProUiInjector.injectIntoImeKeyboard(this, view) } catch (_: Throwable) { }

        return view
    }

    private fun rebuildKeyboardForCustomColors() {
        val desiredOverlay = prefs.proCustomColorOverlay
        if (!pendingCustomColorRefresh && desiredOverlay == appliedCustomColorOverlay) {
            return
        }
        if (!imeViewVisible) {
            pendingCustomColorRefresh = true
            return
        }
        if (isRebuildingCustomColors) return
        try {
            isRebuildingCustomColors = true
            val newView = createKeyboardView()
            setInputView(newView)
            rootView = newView
            appliedCustomColorOverlay = desiredOverlay
            pendingCustomColorRefresh = false
        } catch (t: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to rebuild keyboard for custom colors", t)
        } finally {
            isRebuildingCustomColors = false
        }
    }

    private fun ensureCustomColorsSynced() {
        if (prefs.proCustomColorOverlay != appliedCustomColorOverlay) {
            pendingCustomColorRefresh = true
        }
        if (pendingCustomColorRefresh) {
            rebuildKeyboardForCustomColors()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        imeViewVisible = true
        ensureCustomColorsSynced()
        // 每次键盘视图启动时应用一次高度/底部间距等缩放
        applyKeyboardHeightScale(rootView)
        rootView?.requestLayout()
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

        // 键盘面板首次出现时，按需异步预加载本地模型（SenseVoice/Paraformer）
        tryPreloadLocalModel()


        // 刷新 UI
        btnImeSwitcher?.visibility = View.VISIBLE
        applyPunctuationLabels()
        applyExtensionButtonConfig()
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
        rootView?.post { syncSystemBarsToKeyboardBackground(rootView) }

        // 若正在录音，恢复中间结果为 composing
        if (asrManager.isRunning()) {
            actionHandler.restorePartialAsComposing(currentInputConnection)
        }

        // 启动剪贴板同步
        startClipboardSync()

        // 监听系统剪贴板变更，IME 可见期间弹出预览
        startClipboardPreviewListener()

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
                    // 再次确认仍然就绪（期间用户可能改了设置/权限）
                    if (!checkAsrReady()) return@postDelayed
                    if (!asrManager.isRunning()) {
                        actionHandler.startAutoRecording()
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
        imeViewVisible = false
        DebugLogManager.log("ime", "finish_input_view")
        try {
            syncClipboardManager?.stop()
        } catch (_: Throwable) { }

        // 停止剪贴板预览监听
        stopClipboardPreviewListener()

        hideAiEditPanel()

        // 键盘收起，解除预热（若未在录音）
        try { BluetoothRouteManager.setImeActive(this, false) } catch (t: Throwable) { android.util.Log.w("AsrKeyboardService", "BluetoothRouteManager setImeActive(false)", t) }

        // 如开启：键盘收起后自动切回上一个输入法
        if (prefs.returnPrevImeOnHide) {
            if (suppressReturnPrevImeOnHideOnce) {
                // 清除一次性抑制标记，避免连环切换
                suppressReturnPrevImeOnHideOnce = false
            } else {
                val ok = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                if (!ok) {
                    // 若系统未允许切回，不做额外操作
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
        txtStatusText?.text = message
        // 默认：状态文本可点击复制（便于调试提取报错信息）
        val tv = txtStatusText
        tv?.isClickable = true
        tv?.isFocusable = true
        tv?.setOnClickListener { v ->
            performKeyHaptic(v)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ASR Status", message))
            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onVibrate() {
        vibrateTick()
    }

    override fun onAmplitude(amplitude: Float) {
        waveformView?.updateAmplitude(amplitude)
    }

    override fun onShowClipboardPreview(preview: ClipboardPreview) {
        val tv = txtStatusText ?: return
        tv.text = preview.displaySnippet

        // 限制粘贴板内容为单行显示，避免破坏 UI 布局（txtStatusText 默认已单行，这里冗余保证）
        tv.maxLines = 1
        tv.isSingleLine = true

        // 取消圆角遮罩与额外内边距：使用中心按钮原生背景
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)

        // 启用点击：文本类型为粘贴，文件类型为拉取
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            if (preview.type == ClipboardPreviewType.FILE) {
                val entryId = preview.fileEntryId
                if (!entryId.isNullOrEmpty()) {
                    downloadClipboardFileById(entryId)
                }
            } else {
                actionHandler.handleClipboardPreviewClick(currentInputConnection)
            }
        }

        // 若当前处于录音波形显示，临时切换为文本以展示预览
        txtStatusText?.visibility = View.VISIBLE
        waveformView?.visibility = View.GONE
        waveformView?.stop()

        // 标记最近一次展示的剪贴板内容，避免重复触发
        lastShownClipboardHash = sha256Hex(preview.fullText)

        // 超时自动恢复
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        val r = Runnable { actionHandler.hideClipboardPreview() }
        clipboardPreviewTimeout = r
        tv.postDelayed(r, 10_000)
    }

    override fun onHideClipboardPreview() {
        val tv = txtStatusText ?: return
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        clipboardPreviewTimeout = null

        tv.isClickable = false
        tv.isFocusable = false
        tv.setOnClickListener(null)
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)
        // 保持单行显示以匹配中心信息栏设计
        tv.maxLines = 1
        tv.isSingleLine = true

        // 恢复默认状态文案
        if (asrManager.isRunning()) {
            updateUiListening()
        } else {
            updateUiIdle()
        }
    }

    // ========== 视图绑定和监听器设置 ==========

    private fun bindViews(view: View) {
        layoutMainKeyboard = view.findViewById(R.id.layoutMainKeyboard)
        layoutAiEditPanel = view.findViewById(R.id.layoutAiEditPanel)
        layoutNumpadPanel = view.findViewById(R.id.layoutNumpadPanel)
        layoutClipboardPanel = view.findViewById(R.id.layoutClipboardPanel)
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
        rowExtension = view.findViewById<ConstraintLayout>(R.id.rowExtension)
        btnExt1 = view.findViewById(R.id.btnExt1)
        btnExt2 = view.findViewById(R.id.btnExt2)
        btnExt3 = view.findViewById(R.id.btnExt3)
        btnExt4 = view.findViewById(R.id.btnExt4)
        btnExtCenter1 = view.findViewById(R.id.btnExtCenter1)
        txtStatusText = view.findViewById(R.id.txtStatusText)
        waveformView = view.findViewById(R.id.waveformView)
        btnExtCenter2 = view.findViewById(R.id.btnExtCenter2)
        txtStatus = view.findViewById(R.id.txtStatus)
        groupMicStatus = view.findViewById(R.id.groupMicStatus)

        // 剪贴板面板组件
        clipBtnBack = view.findViewById(R.id.clip_btnBack)
        clipBtnDelete = view.findViewById(R.id.clip_btnDelete)
        clipTxtCount = view.findViewById(R.id.clip_txtCount)
        clipList = view.findViewById(R.id.clip_list)
        isClipboardPanelVisible = layoutClipboardPanel?.visibility == View.VISIBLE
        clipStore = ClipboardHistoryStore(this, prefs)

        // 为波形视图应用动态颜色（通过 UiColors 统一获取主色）
        waveformView?.setWaveformColor(UiColors.primary(view))

        // 修复麦克风垂直位置
        micBaseGroupHeight = -1
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
            // 从 AI 编辑面板进入数字/符号面板，返回时应回到 AI 面板
            showNumpadPanel(returnToAiPanel = true)
        }

        // 数字小键盘：返回到来源面板
        btnNumpadBack?.setOnClickListener { v ->
            performKeyHaptic(v)
            hideNumpadPanel()
            if (numpadReturnToAiPanel) {
                showAiEditPanel()
            } else {
                // 直接回到主键盘
                layoutMainKeyboard?.visibility = View.VISIBLE
                isAiEditPanelVisible = false
                isNumpadPanelVisible = false
            }
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
            DebugLogManager.log(
                category = "ime",
                event = "mic_click",
                data = mapOf(
                    "tapToggle" to true,
                    "state" to actionHandler.getCurrentState()::class.java.simpleName,
                    "running" to asrManager.isRunning(),
                    "aiPanel" to isAiEditPanelVisible
                )
            )
            if (isAiEditPanelVisible) {
                // 在 AI 编辑面板中：点按触发 AI 编辑录音/停止
                actionHandler.handleAiEditClick(currentInputConnection)
            } else {
                // 主界面：点按切换普通听写
                actionHandler.handleMicTapToggle()
            }
        }

        btnMic?.setOnTouchListener { v, event ->
            if (prefs.micTapToggleEnabled) return@setOnTouchListener false
            if (isAiEditPanelVisible) {
                // AI 编辑面板：长按按下开始 AI 编辑录音，松开停止并进入 AI 编辑
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        performKeyHaptic(v)
                        if (!checkAsrReady()) {
                            DebugLogManager.log(
                                category = "ime",
                                event = "mic_down_blocked",
                                data = mapOf(
                                    "tapToggle" to false,
                                    "aiPanel" to true,
                                    "state" to actionHandler.getCurrentState()::class.java.simpleName
                                )
                            )
                            v.performClick()
                            return@setOnTouchListener true
                        }
                        DebugLogManager.log(
                            category = "ime",
                            event = "ai_mic_down",
                            data = mapOf(
                                "tapToggle" to false,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName,
                                "running" to asrManager.isRunning()
                            )
                        )
                        // 进入 AI 编辑录音
                        actionHandler.handleAiEditClick(currentInputConnection)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        DebugLogManager.log(
                            category = "ime",
                            event = "ai_mic_up",
                            data = mapOf(
                                "tapToggle" to false,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName,
                                "running" to asrManager.isRunning()
                            )
                        )
                        // 若仍处于 AI 编辑录音，则停止并进入处理；否则不重复触发开始
                        if (actionHandler.getCurrentState() is KeyboardState.AiEditListening) {
                            actionHandler.handleAiEditClick(currentInputConnection)
                        }
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        DebugLogManager.log(
                            category = "ime",
                            event = "ai_mic_cancel",
                            data = mapOf(
                                "tapToggle" to false,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName
                            )
                        )
                        if (actionHandler.getCurrentState() is KeyboardState.AiEditListening) {
                            actionHandler.handleAiEditClick(currentInputConnection)
                        }
                        v.performClick()
                        true
                    }
                    else -> false
                }
            } else {
                // 主界面：长按普通听写
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        performKeyHaptic(v)
                        if (!checkAsrReady()) {
                            DebugLogManager.log(
                                category = "ime",
                                event = "mic_down_blocked",
                                data = mapOf(
                                    "tapToggle" to false,
                                    "state" to actionHandler.getCurrentState()::class.java.simpleName
                                )
                            )
                            v.performClick()
                            return@setOnTouchListener true
                        }
                        micDownRawY = event.rawY
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
                        actionHandler.handleMicPressDown()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                        val upY = event.rawY
                        val dy = (micDownRawY - upY)
                        val autoEnter = prefs.micSwipeUpAutoEnterEnabled && dy > slop
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
                        actionHandler.handleMicPressUp(autoEnter)
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        DebugLogManager.log(
                            category = "ime",
                            event = "mic_cancel",
                            data = mapOf(
                                "tapToggle" to false,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName,
                                "running" to asrManager.isRunning()
                            )
                        )
                        actionHandler.handleMicPressUp(false)
                        v.performClick()
                        true
                    }
                    else -> false
                }
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
                txtStatusText?.text = getString(R.string.hint_need_keys)
                return@setOnClickListener
            }
            if (!prefs.hasLlmKeys()) {
                clearStatusTextStyle()
                txtStatusText?.text = getString(R.string.hint_need_llm_keys)
                return@setOnClickListener
            }
            showAiEditPanel()
        }

        // 顶部行：后处理开关（魔杖）
        btnPostproc?.apply {
            setImageResource(if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand)
            setOnClickListener { v ->
                performKeyHaptic(v)
                actionHandler.handlePostprocessToggle()
                setImageResource(if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand)
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
            showClipboardPanel()
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
                if (asrManager.isRunning()) asrManager.stopRecording()
                suppressReturnPrevImeOnHideOnce = true
                val switched = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                if (!switched) {
                    showImePicker()
                }
            } else {
                showImePicker()
            }
        }

        // 第一个标点按钮替换为数字/符号键盘入口（普通按钮）
        btnPunct1?.setOnClickListener { v ->
            performKeyHaptic(v)
            showNumpadPanel(returnToAiPanel = false)
        }

        // 自定义标点（合并为两个：btnPunct2 -> 第1/2，btnPunct3 -> 第3/4）
        // 点按：输入主符号；上滑：输入次符号
        // 左右两侧按钮（btnPunct1/btnPunct4）还原为常规按钮类型，本步骤不绑定标点功能

        // 左侧合并标点键（1/2）
        btnPunct2?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct1)
        }
        btnPunct2?.setOnTouchListener(createSwipeUpToAltListener(
            primary = { prefs.punct1 },
            secondary = { prefs.punct2 }
        ))
        // 右侧合并标点键（3/4）
        btnPunct3?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct3)
        }
        btnPunct3?.setOnTouchListener(createSwipeUpToAltListener(
            primary = { prefs.punct3 },
            secondary = { prefs.punct4 }
        ))

        // 第四个按键：供应商切换按钮（样式与 Prompt 选择类似）
        btnPunct4?.setOnClickListener { v ->
            performKeyHaptic(v)
            showVendorPicker(v)
        }

        // 扩展按钮（可自定义功能）
        setupExtensionButton(btnExt1, prefs.extBtn1)
        setupExtensionButton(btnExt2, prefs.extBtn2)
        setupExtensionButton(btnExt3, prefs.extBtn3)
        setupExtensionButton(btnExt4, prefs.extBtn4)

        // 中央扩展按钮（占位，暂无功能）
        btnExtCenter1?.setOnClickListener { v ->
            performKeyHaptic(v)
            // TODO: 添加具体功能
        }
        btnExtCenter2?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, " ")
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
        // 从 AI 面板进入数字面板时应返回到 AI 面板
        numpadReturnToAiPanel = true
        // 进入面板时重置选择模式
        aiSelectMode = false
        aiSelectAnchor = null
        btnAiPanelSelect?.isSelected = false
        btnAiPanelSelect?.setImageResource(R.drawable.selection_toggle)
        // 同步主界面扩展按钮的选择图标
        updateSelectExtButtonsUi()
    }

    private fun hideAiEditPanel() {
        layoutAiEditPanel?.visibility = View.GONE
        layoutMainKeyboard?.visibility = View.VISIBLE
        isAiEditPanelVisible = false
        // 离开面板时重置选择模式
        aiSelectMode = false
        aiSelectAnchor = null
        btnAiPanelSelect?.isSelected = false
        btnAiPanelSelect?.setImageResource(R.drawable.selection_toggle)
        // 释放可能仍在队列中的光标连发回调，避免隐藏后仍触发
        releaseCursorRepeatCallbacks()
        // 同步主界面扩展按钮的选择图标
        updateSelectExtButtonsUi()
    }

    private fun showNumpadPanel(returnToAiPanel: Boolean = false) {
        if (isNumpadPanelVisible) return
        // 记录返回目标
        numpadReturnToAiPanel = returnToAiPanel
        // 隐藏其他面板，避免叠盖
        layoutAiEditPanel?.visibility = View.GONE
        isAiEditPanelVisible = false
        layoutMainKeyboard?.visibility = View.GONE
        // 取消编辑面板可能仍在的光标连续回调
        releaseCursorRepeatCallbacks()
        layoutNumpadPanel?.visibility = View.VISIBLE
        isNumpadPanelVisible = true
        // 数字/符号面板不需要显示麦克风悬浮按钮，避免遮挡
        groupMicStatus?.visibility = View.GONE
        applyNumpadPunctMode()
    }

    private fun hideNumpadPanel() {
        layoutNumpadPanel?.visibility = View.GONE
        isNumpadPanelVisible = false
        // 还原麦克风悬浮按钮可见性
        groupMicStatus?.visibility = View.VISIBLE
    }

    private fun showClipboardPanel() {
        if (isClipboardPanelVisible) return
        // 记录主键盘当前高度，以便对齐面板高度
        val mainHeight = layoutMainKeyboard?.height
        // 隐藏其他面板
        layoutAiEditPanel?.visibility = View.GONE
        isAiEditPanelVisible = false
        layoutNumpadPanel?.visibility = View.GONE
        isNumpadPanelVisible = false
        layoutMainKeyboard?.visibility = View.GONE
        groupMicStatus?.visibility = View.GONE

        if (clipAdapter == null) {
            clipAdapter = ClipboardPanelAdapter { e ->
                performKeyHaptic(clipList)
                when (e.type) {
                    com.brycewg.asrkb.clipboard.EntryType.TEXT -> {
                        // 文本类型：粘贴到输入框
                        clipStore?.pasteInto(currentInputConnection, e.text)
                        hideClipboardPanel()
                    }
                    com.brycewg.asrkb.clipboard.EntryType.IMAGE,
                    com.brycewg.asrkb.clipboard.EntryType.FILE -> {
                        // 文件类型：未下载则先拉取，已下载则尝试打开
                        if (e.downloadStatus == com.brycewg.asrkb.clipboard.DownloadStatus.COMPLETED && e.localFilePath != null) {
                            openFile(e.localFilePath)
                        } else {
                            downloadClipboardFile(e)
                        }
                    }
                }
            }
            clipList?.layoutManager = LinearLayoutManager(this)
            clipList?.adapter = clipAdapter

            val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean = false

                override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    val pos = viewHolder.bindingAdapterPosition
                    val item = clipAdapter?.currentList?.getOrNull(pos)
                    return if (item != null && item.pinned) {
                        // 固定记录：仅允许右滑（取消固定），禁用左滑
                        ItemTouchHelper.RIGHT
                    } else {
                        // 非固定：允许左右滑（右滑固定，左滑删除）
                        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    }
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val pos = viewHolder.bindingAdapterPosition
                    val item = clipAdapter?.currentList?.getOrNull(pos)
                    if (item != null) {
                        if (direction == ItemTouchHelper.RIGHT) {
                            // 右滑：固定/取消固定
                            val pinnedNow = clipStore?.togglePin(item.id) ?: false
                            val msg = if (pinnedNow) getString(R.string.clip_pinned) else getString(R.string.clip_unpinned)
                            Toast.makeText(this@AsrKeyboardService, msg, Toast.LENGTH_SHORT).show()
                        } else if (direction == ItemTouchHelper.LEFT) {
                            // 左滑：删除（仅非固定）
                            if (item.pinned) {
                                Toast.makeText(this@AsrKeyboardService, getString(R.string.clip_cannot_delete_pinned), Toast.LENGTH_SHORT).show()
                                // 恢复可见状态
                                clipAdapter?.notifyItemChanged(pos)
                            } else {
                                val deleted = clipStore?.deleteHistoryById(item.id) ?: false
                                if (deleted) Toast.makeText(this@AsrKeyboardService, getString(R.string.clip_deleted), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    refreshClipboardList()
                }
            }
            ItemTouchHelper(callback).attachToRecyclerView(clipList)
        }

        // 顶部按钮
        clipBtnBack?.setOnClickListener { v ->
            performKeyHaptic(v)
            hideClipboardPanel()
        }
        clipBtnDelete?.setOnClickListener { v ->
            performKeyHaptic(v)
            showClipboardDeleteMenu()
        }

        refreshClipboardList()
        layoutClipboardPanel?.visibility = View.VISIBLE
        // 同步高度：与主键盘一致
        if (mainHeight != null && mainHeight > 0) {
            val lp = layoutClipboardPanel?.layoutParams
            if (lp != null) {
                lp.height = mainHeight
                layoutClipboardPanel?.layoutParams = lp
            }
        }
        isClipboardPanelVisible = true
    }

    private fun hideClipboardPanel() {
        layoutClipboardPanel?.visibility = View.GONE
        // 释放高度为包裹内容，避免后续计算异常
        val lp = layoutClipboardPanel?.layoutParams
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutClipboardPanel?.layoutParams = lp
        }
        layoutMainKeyboard?.visibility = View.VISIBLE
        groupMicStatus?.visibility = View.VISIBLE
        isClipboardPanelVisible = false
    }

    private fun refreshClipboardList() {
        val all = clipStore?.getAll().orEmpty()
        var fileSeen = false
        val filtered = all.filter { entry ->
            if (entry.type == com.brycewg.asrkb.clipboard.EntryType.TEXT) {
                true
            } else if (!fileSeen) {
                fileSeen = true
                true
            } else {
                false
            }
        }
        clipAdapter?.submitList(filtered)
        clipTxtCount?.text = getString(R.string.clip_count_format, filtered.size)
    }

    /**
     * 在 IME 窗口内展示 PopupMenu，并在异常情况下尝试保持键盘不被收起。
     *
     * 部分机型上，在输入法窗口里弹出菜单偶现触发系统收起软键盘；
     * 这里在菜单消失时检测输入视图是否已被隐藏，如已隐藏则请求重新显示。
     */
    private fun showPopupMenuKeepingIme(popup: PopupMenu) {
        popup.setOnDismissListener {
            // 仅在弹出后短时间内发生收起时尝试恢复，避免干扰用户主动收起键盘
            val now = System.currentTimeMillis()
            if (now - lastPopupMenuShownAt > 2000L) return@setOnDismissListener
            if (!isInputViewShown && currentInputEditorInfo != null) {
                try {
                    requestShowSelf(0)
                } catch (t: Throwable) {
                    android.util.Log.w("AsrKeyboardService", "Failed to re-show IME after popup dismiss", t)
                }
            }
        }
        lastPopupMenuShownAt = System.currentTimeMillis()
        popup.show()
    }

    private fun showClipboardDeleteMenu() {
        val anchor = clipBtnDelete ?: return
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, 0, 0, getString(R.string.clip_delete_before_1h))
        popup.menu.add(0, 1, 1, getString(R.string.clip_delete_before_24h))
        popup.menu.add(0, 2, 2, getString(R.string.clip_delete_before_7d))
        popup.menu.add(0, 3, 3, getString(R.string.clip_delete_all_non_pinned))
        popup.setOnMenuItemClickListener { mi ->
            val now = System.currentTimeMillis()
            val oneHour = 60 * 60 * 1000L
            val day = 24 * oneHour
            val week = 7 * day
            when (mi.itemId) {
                0 -> clipStore?.deleteHistoryBefore(now - oneHour)
                1 -> clipStore?.deleteHistoryBefore(now - day)
                2 -> clipStore?.deleteHistoryBefore(now - week)
                3 -> clipStore?.clearAllNonPinned()
            }
            refreshClipboardList()
            true
        }
        showPopupMenuKeepingIme(popup)
    }

    private fun releaseCursorRepeatCallbacks() {
        repeatLeftRunnable?.let { btnAiPanelCursorLeft?.removeCallbacks(it) }
        repeatLeftRunnable = null
        repeatRightRunnable?.let { btnAiPanelCursorRight?.removeCallbacks(it) }
        repeatRightRunnable = null
    }

    // ========== UI 更新方法 ==========

    private fun updateUiIdle() {
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        txtStatusText?.visibility = View.VISIBLE
        txtStatusText?.text = getString(R.string.status_idle)
        waveformView?.visibility = View.GONE
        waveformView?.stop()

        btnMic?.isSelected = false
        btnMic?.setImageResource(R.drawable.microphone)
        btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line)
        currentInputConnection?.let { inputHelper.finishComposingText(it) }
    }

    private fun updateUiListening() {
        clearStatusTextStyle()
        // 隐藏文字，显示波形动画
        txtStatusText?.visibility = View.GONE
        waveformView?.visibility = View.VISIBLE
        waveformView?.start()

        btnMic?.isSelected = true
        btnMic?.setImageResource(R.drawable.microphone_fill)
        btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line)
    }

    private fun updateUiProcessing() {
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        txtStatusText?.visibility = View.VISIBLE
        txtStatusText?.text = getString(R.string.status_recognizing)
        waveformView?.visibility = View.GONE
        waveformView?.stop()

        btnMic?.isSelected = false
        btnMic?.setImageResource(R.drawable.microphone)
        btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line)
    }

    private fun updateUiAiProcessing() {
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        txtStatusText?.visibility = View.VISIBLE
        txtStatusText?.text = getString(R.string.status_ai_processing)
        waveformView?.visibility = View.GONE
        waveformView?.stop()

        btnMic?.isSelected = false
        btnMic?.setImageResource(R.drawable.microphone)
        btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line)
    }

    private fun updateUiAiEditListening() {
        clearStatusTextStyle()
        // AI Edit 录音状态也使用文字显示（避免与普通录音混淆）
        txtStatusText?.visibility = View.VISIBLE
        txtStatusText?.text = getString(R.string.status_ai_edit_listening)
        waveformView?.visibility = View.GONE
        waveformView?.stop()

        btnMic?.isSelected = false
        btnMic?.setImageResource(R.drawable.microphone_fill)
        btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line_fill)
    }

    private fun updateUiAiEditProcessing() {
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        txtStatusText?.visibility = View.VISIBLE
        txtStatusText?.text = getString(R.string.status_ai_editing)
        waveformView?.visibility = View.GONE
        waveformView?.stop()

        btnMic?.isSelected = false
        btnMic?.setImageResource(R.drawable.microphone)
        btnPromptPicker?.setImageResource(R.drawable.pencil_simple_line_fill)
    }

    // ========== 辅助方法 ==========

    /**
     * 清除状态文本的粘贴板预览样式（背景遮罩、内边距、点击监听器、单行限制）
     * 确保普通状态文本不会显示粘贴板预览的样式
     */
    private fun clearStatusTextStyle() {
        val tv = txtStatusText ?: return
        tv.isClickable = false
        tv.isFocusable = false
        tv.setOnClickListener(null)
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)
        // 中心信息栏保持单行，以避免布局跳动
        tv.maxLines = 1
        tv.isSingleLine = true
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
        return inputHelper.getTextBeforeCursor(ic, 10000)?.length
    }

    private fun totalTextLength(): Int? {
        val ic = currentInputConnection ?: return null
        val before = inputHelper.getTextBeforeCursor(ic, 10000)?.length ?: 0
        val after = inputHelper.getTextAfterCursor(ic, 10000)?.length ?: 0
        return before + after
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
        val beforeLen = inputHelper.getTextBeforeCursor(ic, 10000)?.length ?: 0
        aiSelectAnchor = beforeLen
    }

    private fun moveCursorBy(delta: Int) {
        val ic = currentInputConnection ?: return
        if (delta == 0) return
        val maxLen = totalTextLength() ?: Int.MAX_VALUE

        // 非选择模式：直接移动光标
        if (!aiSelectMode) {
            val pos = currentCursorPosition() ?: return
            val newPos = (pos + delta).coerceIn(0, maxLen)
            inputHelper.setSelection(ic, newPos, newPos)
            return
        }

        // 选择模式：固定锚点，移动“活动端”。方向切换时，从当前活动端向相反方向移动一格，先逐步收缩到锚点，再向另一侧扩展。
        ensureAnchorForSelection()
        val anchor = aiSelectAnchor ?: 0
        val selStart = lastSelStart
        val selEnd = lastSelEnd

        // 活动端：若锚点在 start，活动端为 end；否则为 start。无选区时用当前光标。
        val activeNow: Int = if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            if (anchor == selStart) selEnd else selStart
        } else {
            currentCursorPosition() ?: anchor
        }

        val step = if (delta < 0) -1 else 1
        val newActive = (activeNow + step).coerceIn(0, maxLen)
        val start = minOf(anchor, newActive)
        val end = maxOf(anchor, newActive)
        inputHelper.setSelection(ic, start, end)
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
        btnAiPanelSelect?.setImageResource(if (aiSelectMode) R.drawable.selection_fill else R.drawable.selection_toggle)
        if (aiSelectMode) {
            // 进入选择模式立即固定锚点
            aiSelectAnchor = null
            ensureAnchorForSelection()
        } else {
            // 退出选择模式清除锚点
            aiSelectAnchor = null
        }
        // 主界面扩展按钮也需要反映选择模式的选中态
        updateSelectExtButtonsUi()
    }

    /**
     * 同步主界面扩展按钮（配置为 SELECT 的按钮）图标为选中/未选中态。
     */
    private fun updateSelectExtButtonsUi() {
        fun updateBtn(btn: ImageButton?, action: ExtensionButtonAction) {
            if (action == ExtensionButtonAction.SELECT) {
                btn?.setImageResource(if (aiSelectMode) R.drawable.selection_fill else R.drawable.selection_toggle)
                btn?.isSelected = aiSelectMode
            }
        }
        updateBtn(btnExt1, prefs.extBtn1)
        updateBtn(btnExt2, prefs.extBtn2)
        updateBtn(btnExt3, prefs.extBtn3)
        updateBtn(btnExt4, prefs.extBtn4)
    }

    /**
     * 同步主界面扩展按钮（配置为静音判停开关的按钮）图标为开启/关闭态。
     */
    private fun updateSilenceAutoStopExtButtonsUi() {
        val enabled = prefs.autoStopOnSilenceEnabled
        fun updateBtn(btn: ImageButton?, action: ExtensionButtonAction) {
            if (action == ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE) {
                btn?.setImageResource(if (enabled) R.drawable.hand_palm_fill else R.drawable.hand_palm)
                btn?.isSelected = enabled
            }
        }
        updateBtn(btnExt1, prefs.extBtn1)
        updateBtn(btnExt2, prefs.extBtn2)
        updateBtn(btnExt3, prefs.extBtn3)
        updateBtn(btnExt4, prefs.extBtn4)
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
        val ok = ic.performContextMenuAction(android.R.id.copy)
        if (!ok) {
            // 回退到直接写剪贴板
            val selected = inputHelper.getSelectedText(ic, 0)?.toString()
            if (!selected.isNullOrEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("ASR Copy", selected)
                cm.setPrimaryClip(clip)
            }
        }

        // 显示剪贴板预览：优先使用当前选中文本，否则读取系统剪贴板
        val selected = inputHelper.getSelectedText(ic, 0)?.toString()
        val text = if (!selected.isNullOrEmpty()) {
            selected
        } else {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }
        if (!text.isNullOrEmpty()) {
            actionHandler.showClipboardPreview(text)
        }
    }

    private fun handlePasteAction() {
        val ic = currentInputConnection
        if (ic == null) return
        // 变更前记录撤销快照
        actionHandler.saveUndoSnapshot(ic)
        val ok = ic.performContextMenuAction(android.R.id.paste)
        if (!ok) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) {
                inputHelper.commitText(ic, text)
            }
        }
    }

    private fun showPromptPickerForApply(anchor: View) {
        val presets = prefs.getPromptPresets()
        if (presets.isEmpty()) return
        val popup = PopupMenu(anchor.context, anchor)
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
        showPopupMenuKeepingIme(popup)
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
            val tag = v.tag as? String
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
        val cn = prefs.numpadCnPunctEnabled
        // 更新底栏按钮图标
        btnNumpadPunctToggle?.setImageResource(if (cn) R.drawable.translate_fill else R.drawable.translate)
        // 行1：10 个按钮
        val row1 = root.findViewById<android.view.View>(R.id.rowPunct1) as? android.view.ViewGroup
        val row2 = root.findViewById<android.view.View>(R.id.rowPunct2) as? android.view.ViewGroup
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
        val tv = txtStatusText ?: return
        tv.text = label
        // 移除芯片样式，仅保持可点击
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)
        // 在中心信息栏展示，并临时隐藏波形
        txtStatusText?.visibility = View.VISIBLE
        waveformView?.visibility = View.GONE
        waveformView?.stop()
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
            DebugLogManager.log("ime", "asr_not_ready", mapOf("reason" to "perm"))
            return false
        }
        if (!prefs.hasAsrKeys()) {
            refreshPermissionUi()
            DebugLogManager.log("ime", "asr_not_ready", mapOf("reason" to "keys"))
            return false
        }
        if (prefs.asrVendor == AsrVendor.SenseVoice) {
            val prepared = com.brycewg.asrkb.asr.isSenseVoicePrepared()
            if (!prepared) {
                val base = getExternalFilesDir(null) ?: filesDir
                val probeRoot = java.io.File(base, "sensevoice")
                val variant = prefs.svModelVariant
                val variantDir = if (variant == "small-full") {
                    java.io.File(probeRoot, "small-full")
                } else {
                    java.io.File(probeRoot, "small-int8")
                }
                val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir)
                    ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
                if (found == null) {
                    clearStatusTextStyle()
                    txtStatusText?.text = getString(R.string.error_sensevoice_model_missing)
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
            txtStatusText?.text = getString(R.string.hint_need_permission)
        } else if (!hasKeys) {
            btnMic?.isEnabled = false
            txtStatusText?.text = getString(R.string.hint_need_keys)
        } else {
            btnMic?.isEnabled = true
            txtStatusText?.text = getString(R.string.status_idle)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }


    private fun applyPunctuationLabels() {
        // 新布局：中间两个按键显示两行标签，由自定义视图绘制
        btnPunct2?.setTexts(prefs.punct1, prefs.punct2)
        btnPunct3?.setTexts(prefs.punct3, prefs.punct4)
    }

    /**
     * 创建“上滑触发次符号”的触摸监听：
     * - ACTION_UP 时根据位移决定输入主/次符号
     */
    private fun createSwipeUpToAltListener(
        primary: () -> String,
        secondary: () -> String
    ): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val thresholdPx = (24f * resources.displayMetrics.density).toInt().coerceAtLeast(touchSlop)
        var downY = 0f
        var consumedAlt = false
        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    consumedAlt = false
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dy = downY - ev.y
                    if (dy >= thresholdPx) {
                        // 上滑：输入次符号
                        performKeyHaptic(v)
                        actionHandler.commitText(currentInputConnection, secondary())
                        consumedAlt = true
                        true
                    } else {
                        // 非上滑：交给 onClick（输入主符号）
                        if (!consumedAlt) v.performClick()
                        consumedAlt = false
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> { consumedAlt = false; false }
                else -> false
            }
        }
    }

    /**
     * 设置单个扩展按钮的功能
     */
    private fun setupExtensionButton(btn: ImageButton?, action: ExtensionButtonAction) {
        if (btn == null) return

        // 设置图标
        btn.setImageResource(action.iconResId)

        // 清理旧监听，避免切换功能后残留触摸/点击逻辑导致误触发
        btn.setOnClickListener(null)
        btn.setOnTouchListener(null)

        // 根据动作类型设置行为
        when (action) {
            ExtensionButtonAction.NONE -> {
                btn.visibility = View.GONE
            }
            ExtensionButtonAction.CURSOR_LEFT, ExtensionButtonAction.CURSOR_RIGHT -> {
                // 光标移动需要长按连发
                btn.visibility = View.VISIBLE
                setupCursorButtonRepeat(btn, action)
            }
            else -> {
                // 普通按钮：点击即可
                btn.visibility = View.VISIBLE
                btn.setOnClickListener { v ->
                    performKeyHaptic(v)
                    handleExtensionButtonAction(action)
                }
            }
        }
    }

    /**
     * 处理扩展按钮动作
     */
    private fun handleExtensionButtonAction(action: ExtensionButtonAction) {
        val result = actionHandler.handleExtensionButtonClick(action, currentInputConnection)

        when (result) {
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS -> {
                // 成功，不需要额外处理
            }
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED -> {
                // 失败，已在 actionHandler 中处理
            }
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_TOGGLE_SELECTION -> {
                // 在主界面直接切换选择模式（不进入 AI 编辑面板）
                toggleSelectionMode()
                // 同步扩展按钮（若配置为 SELECT）
                updateSelectExtButtonsUi()
            }
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_NUMPAD -> {
                // 从主界面进入数字/符号面板
                showNumpadPanel(returnToAiPanel = false)
            }
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_CLIPBOARD -> {
                showClipboardPanel()
            }
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_LEFT,
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_RIGHT -> {
                // 光标移动已在长按处理中完成
            }
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_HIDE_KEYBOARD -> {
                hideKeyboardPanel()
            }
        }

        if (action == ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE &&
            result == KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ) {
            updateSilenceAutoStopExtButtonsUi()
        }
    }

    /**
     * 设置光标移动按钮的长按连发
     */
    private fun setupCursorButtonRepeat(btn: ImageButton, action: ExtensionButtonAction) {
        val initialDelay = 350L
        val repeatInterval = 50L
        var repeatRunnable: Runnable? = null

        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    // 立即移动一次
                    val delta = if (action == ExtensionButtonAction.CURSOR_LEFT) -1 else 1
                    moveCursorBy(delta)

                    // 设置连发
                    repeatRunnable?.let { v.removeCallbacks(it) }
                    val r = Runnable {
                        moveCursorBy(delta)
                        repeatRunnable?.let { v.postDelayed(it, repeatInterval) }
                    }
                    repeatRunnable = r
                    v.postDelayed(r, initialDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatRunnable?.let { v.removeCallbacks(it) }
                    repeatRunnable = null
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 应用扩展按钮配置（更新图标和可见性）
     */
    private fun applyExtensionButtonConfig() {
        setupExtensionButton(btnExt1, prefs.extBtn1)
        setupExtensionButton(btnExt2, prefs.extBtn2)
        setupExtensionButton(btnExt3, prefs.extBtn3)
        setupExtensionButton(btnExt4, prefs.extBtn4)
        // 初始应用时同步选择按钮与静音判停按钮的选中态
        updateSelectExtButtonsUi()
        updateSilenceAutoStopExtButtonsUi()
    }

    private fun vibrateTick() {
        if (!prefs.micHapticEnabled) return
        val v = getSystemService(Vibrator::class.java)
        v.vibrate(android.os.VibrationEffect.createOneShot(20, 50))
    }

    private fun performKeyHaptic(view: View?) {
        if (!prefs.micHapticEnabled) return
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    }

    private fun showPromptPicker(anchor: View) {
        val presets = prefs.getPromptPresets()
        if (presets.isEmpty()) return
        val popup = PopupMenu(anchor.context, anchor)
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
            txtStatusText?.text = getString(R.string.switched_preset, preset.title)
            true
        }
        showPopupMenuKeepingIme(popup)
    }

    private fun showVendorPicker(anchor: View) {
        val vendors = AsrVendorUi.ordered()
        val names = AsrVendorUi.names(this)
        val popup = PopupMenu(anchor.context, anchor)
        val cur = prefs.asrVendor
        vendors.forEachIndexed { idx, v ->
            val item = popup.menu.add(0, idx, idx, names[idx])
            item.isCheckable = true
            if (v == cur) item.isChecked = true
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { mi ->
            val position = mi.itemId
            val vendor = vendors.getOrNull(position)
            if (vendor != null && vendor != prefs.asrVendor) {
                val old = prefs.asrVendor
                prefs.asrVendor = vendor

                // 离开本地引擎时卸载缓存识别器，释放内存
                try {
                    if (old == com.brycewg.asrkb.asr.AsrVendor.SenseVoice && vendor != com.brycewg.asrkb.asr.AsrVendor.SenseVoice) {
                        com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                    }
                    if (old == com.brycewg.asrkb.asr.AsrVendor.Telespeech && vendor != com.brycewg.asrkb.asr.AsrVendor.Telespeech) {
                        com.brycewg.asrkb.asr.unloadTelespeechRecognizer()
                    }
                    if (old == com.brycewg.asrkb.asr.AsrVendor.Paraformer && vendor != com.brycewg.asrkb.asr.AsrVendor.Paraformer) {
                        com.brycewg.asrkb.asr.unloadParaformerRecognizer()
                    }
                    if (old == com.brycewg.asrkb.asr.AsrVendor.Zipformer && vendor != com.brycewg.asrkb.asr.AsrVendor.Zipformer) {
                        com.brycewg.asrkb.asr.unloadZipformerRecognizer()
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("AsrKeyboardService", "Failed to unload local recognizer", t)
                }

                // 空闲时立即重建引擎
                if (actionHandler.getCurrentState() is KeyboardState.Idle) {
                    asrManager.rebuildEngine()
                }

                // 切换到本地引擎且启用预加载时，尝试预加载
                try {
                    when (vendor) {
                        com.brycewg.asrkb.asr.AsrVendor.SenseVoice -> if (prefs.svPreloadEnabled) com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this, prefs)
                        com.brycewg.asrkb.asr.AsrVendor.Telespeech -> if (prefs.tsPreloadEnabled) com.brycewg.asrkb.asr.preloadTelespeechIfConfigured(this, prefs)
                        com.brycewg.asrkb.asr.AsrVendor.Paraformer -> if (prefs.pfPreloadEnabled) com.brycewg.asrkb.asr.preloadParaformerIfConfigured(this, prefs)
                        com.brycewg.asrkb.asr.AsrVendor.Zipformer -> if (prefs.zfPreloadEnabled) com.brycewg.asrkb.asr.preloadZipformerIfConfigured(this, prefs)
                        else -> {}
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("AsrKeyboardService", "Failed to preload local recognizer", t)
                }

                // 状态栏提示
                clearStatusTextStyle()
                val name = try { AsrVendorUi.name(this, vendor) } catch (_: Throwable) { "" }
                txtStatusText?.text = getString(R.string.switched_preset, name)
            }
            true
        }
        showPopupMenuKeepingIme(popup)
    }

    private fun tryPreloadLocalModel() {
        if (localPreloadTriggered) return
        val p = prefs
        val enabled = when (p.asrVendor) {
            AsrVendor.SenseVoice -> p.svPreloadEnabled
            AsrVendor.Telespeech -> p.tsPreloadEnabled
            AsrVendor.Paraformer -> p.pfPreloadEnabled
            AsrVendor.Zipformer -> p.zfPreloadEnabled
            else -> false
        }
        if (!enabled) return
        if (com.brycewg.asrkb.asr.isLocalAsrPrepared(p)) { localPreloadTriggered = true; return }

        // 信息栏显示"加载中…"，完成后回退状态
        rootView?.post {
            clearStatusTextStyle()
            txtStatusText?.text = getString(R.string.sv_loading_model)
        }
        localPreloadTriggered = true

        serviceScope.launch(Dispatchers.Default) {
            val t0 = android.os.SystemClock.uptimeMillis()
            com.brycewg.asrkb.asr.preloadLocalAsrIfConfigured(
                this@AsrKeyboardService,
                p,
                onLoadStart = null,
                onLoadDone = {
                    val dt = (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(0)
                    rootView?.post {
                        clearStatusTextStyle()
                        txtStatusText?.text = getString(R.string.sv_model_ready_with_ms, dt)
                        rootView?.postDelayed({
                            clearStatusTextStyle()
                            txtStatusText?.text = if (asrManager.isRunning()) getString(R.string.status_listening) else getString(R.string.status_idle)
                        }, 1200)
                    }
                },
                suppressToastOnStart = true
            )
        }
    }

    private fun startClipboardSync() {
        if (prefs.syncClipboardEnabled) {
            if (syncClipboardManager == null) {
                syncClipboardManager = SyncClipboardManager(
                    this,
                    prefs,
                    serviceScope,
                    object : SyncClipboardManager.Listener {
                        override fun onPulledNewContent(text: String) {
                            rootView?.post { actionHandler.showClipboardPreview(text) }
                        }

                        override fun onUploadSuccess() {
                            // 成功时不提示
                        }

                        override fun onUploadFailed(reason: String?) {
                            rootView?.post {
                                // 失败时短暂提示，然后恢复到剪贴板预览，方便点击粘贴
                                onStatusMessage(getString(R.string.sc_status_upload_failed))
                                txtStatusText?.postDelayed({ actionHandler.reShowClipboardPreviewIfAny() }, 900)
                            }
                        }

                        override fun onFilePulled(type: com.brycewg.asrkb.clipboard.EntryType, fileName: String, serverFileName: String) {
                            rootView?.post {
                                // 刷新剪贴板列表显示新文件
                                if (isClipboardPanelVisible) {
                                    refreshClipboardList()
                                }
                                // 在键盘信息栏展示文件预览（文件名 + 格式）
                                val store = clipStore
                                if (store != null) {
                                    val all = store.getAll()
                                    val entry = all.firstOrNull {
                                        it.type != com.brycewg.asrkb.clipboard.EntryType.TEXT &&
                                            (it.serverFileName == serverFileName || it.fileName == fileName)
                                    }
                                    if (entry != null) {
                                        actionHandler.showClipboardFilePreview(entry)
                                    }
                                }
                            }
                        }
                    },
                    clipStore
                )
            }
            syncClipboardManager?.start()
            serviceScope.launch(Dispatchers.IO) {
                syncClipboardManager?.proactiveUploadIfChanged()
                syncClipboardManager?.pullNow(true)
            }
        } else {
            syncClipboardManager?.stop()
        }
    }

    /**
     * 下载剪贴板文件（通过条目引用）
     */
    private fun downloadClipboardFile(entry: com.brycewg.asrkb.clipboard.ClipboardHistoryStore.Entry) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val success = syncClipboardManager?.downloadFile(entry.id) ?: false
                rootView?.post {
                    if (success) {
                        android.widget.Toast.makeText(
                            this@AsrKeyboardService,
                            getString(R.string.clip_file_download_success),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        // 刷新列表显示下载完成状态
                        if (isClipboardPanelVisible) {
                            refreshClipboardList()
                        }
                    } else {
                        android.widget.Toast.makeText(
                            this@AsrKeyboardService,
                            getString(R.string.clip_file_download_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        // 刷新列表显示失败状态
                        if (isClipboardPanelVisible) {
                            refreshClipboardList()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AsrKeyboardService", "Failed to download file", e)
                rootView?.post {
                    android.widget.Toast.makeText(
                        this@AsrKeyboardService,
                        getString(R.string.clip_file_download_error, e.message ?: ""),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 通过剪贴板条目 ID 下载文件（用于信息栏预览点击）。
     */
    private fun downloadClipboardFileById(entryId: String) {
        val store = clipStore ?: return
        val entry = store.getEntryById(entryId) ?: return
        downloadClipboardFile(entry)
    }

    /**
     * 打开已下载的文件
     */
    private fun openFile(filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.clip_file_not_found),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                // 如果没有应用可以打开，则使用系统分享
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = getMimeType(file)
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.clip_file_open_chooser_title)).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (e: Exception) {
            android.util.Log.e("AsrKeyboardService", "Failed to open file: $filePath", e)
            android.widget.Toast.makeText(
                this,
                getString(R.string.clip_file_open_failed, e.message ?: ""),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     */
    private fun getMimeType(file: java.io.File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "*/*"
        }
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
        val tier = prefs.keyboardHeightTier
        val scale = when (tier) {
            2 -> 1.15f
            3 -> 1.30f
            else -> 1.0f
        }

        fun dp(v: Float): Int {
            val d = view.resources.displayMetrics.density
            return (v * d + 0.5f).toInt()
        }

        // 若缩放等级发生变化，重置麦克风位移基线，避免基于旧高度的下移造成底部截断
        if (kotlin.math.abs(lastAppliedHeightScale - scale) > 1e-3f) {
            lastAppliedHeightScale = scale
            micBaseGroupHeight = -1
            btnMic?.translationY = 0f
        }

        // 同步一次当前 RootWindowInsets，避免首次缩放时 bottom inset 尚未写入导致底部裁剪
        run {
            try {
                val rw = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                val b = rw?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
                if (b > 0) systemNavBarBottomInset = b
            } catch (_: Throwable) { }
        }

        // 应用底部间距（无论是否缩放都需要）
        val fl = view as? android.widget.FrameLayout
        if (fl != null) {
            val ps = fl.paddingStart
            val pe = fl.paddingEnd
            val pt = dp(8f * scale)
            val basePb = dp(12f * scale)
            // 添加用户设置的底部间距
            val extraPadding = dp(prefs.keyboardBottomPaddingDp.toFloat())
            // 添加系统导航栏高度以适配 Android 15 边缘到边缘显示
            val pb = basePb + extraPadding + systemNavBarBottomInset
            fl.setPaddingRelative(ps, pt, pe, pb)
        }

        // 顶部主行高度（无论是否缩放都需要重设，避免从大/中切回小时残留）
        run {
            val topRow = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rowTop)
            if (topRow != null) {
                val lp = topRow.layoutParams
                lp.height = dp(80f * scale)
                topRow.layoutParams = lp
            }
        }

        // 扩展按钮行高度（同样需要在 scale==1 时恢复）
        run {
            val extRow = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rowExtension)
            if (extRow != null) {
                val lp = extRow.layoutParams
                lp.height = dp(50f * scale)
                extRow.layoutParams = lp
            }
        }

        // 使主键盘功能行（overlay）从顶部锚定，避免垂直居中导致的像素舍入抖动
        // 计算规则：rowExtension 完整高度 + rowTop 高度的一半 + 固定偏移
        // = 50s(rowExtension完整) + 40s(rowTop的一半) + 6 = 90s + 6
        run {
            val overlay = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rowOverlay)
            if (overlay != null) {
                val lp = overlay.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    lp.topMargin = dp(90f * scale + 6f)
                    lp.gravity = android.view.Gravity.TOP
                    overlay.layoutParams = lp
                }
            }
        }

        fun scaleSquareButton(id: Int) {
            val v = view.findViewById<View>(id) ?: return
            val lp = v.layoutParams
            lp.width = dp(40f * scale)
            lp.height = dp(40f * scale)
            v.layoutParams = lp
        }
        fun scaleRectButton(id: Int, widthDp: Float, heightDp: Float) {
            val v = view.findViewById<View>(id) ?: return
            val lp = v.layoutParams
            lp.width = dp(widthDp * scale)
            lp.height = dp(heightDp * scale)
            v.layoutParams = lp
        }
        fun scaleChildrenByTag(root: View?, tag: String) {
            if (root == null) return
            if (root is android.view.ViewGroup) {
                for (i in 0 until root.childCount) {
                    scaleChildrenByTag(root.getChildAt(i), tag)
                }
            }
            val t = root.tag as? String
            if (t == tag) {
                val lp = root.layoutParams
                lp.height = dp(40f * scale)
                // 宽度可能由权重控制，不强制写入
                root.layoutParams = lp
            }
        }

        val ids40 = intArrayOf(
            // 主键盘按钮
            R.id.btnHide, R.id.btnPostproc, R.id.btnBackspace, R.id.btnPromptPicker,
            R.id.btnSettings, R.id.btnImeSwitcher, R.id.btnEnter, R.id.btnAiEdit,
            R.id.btnPunct1, R.id.btnPunct2, R.id.btnPunct3, R.id.btnPunct4,
            // 扩展按钮
            R.id.btnExt1, R.id.btnExt2, R.id.btnExt3, R.id.btnExt4,
            // AI 编辑面板按钮
            R.id.btnAiPanelBack, R.id.btnAiPanelApplyPreset,
            R.id.btnAiPanelCursorLeft, R.id.btnAiPanelCursorRight,
            R.id.btnAiPanelNumpad, R.id.btnAiPanelSelect,
            R.id.btnAiPanelSelectAll, R.id.btnAiPanelCopy,
            R.id.btnAiPanelUndo, R.id.btnAiPanelPaste,
            R.id.btnAiPanelMoveStart, R.id.btnAiPanelMoveEnd,
            // 剪贴板面板按钮
            R.id.clip_btnBack, R.id.clip_btnDelete
        )
        ids40.forEach { scaleSquareButton(it) }

        // 缩放中央按钮（仅高度，宽度由约束控制）
        run {
            val v1 = view.findViewById<View>(R.id.btnExtCenter1)
            if (v1 != null) {
                val lp = v1.layoutParams
                lp.height = dp(40f * scale)
                // 宽度由约束控制，不设置
                v1.layoutParams = lp
            }
        }

        run {
            val v2 = view.findViewById<View>(R.id.btnExtCenter2)
            if (v2 != null) {
                val lp = v2.layoutParams
                lp.height = dp(40f * scale)
                // 宽度由约束控制，不设置
                v2.layoutParams = lp
            }
        }

        // 数字/标点小键盘的方形按键（通过 tag="key40" 统一缩放高度）
        scaleChildrenByTag(layoutNumpadPanel, "key40")

        btnMic?.customSize = dp(72f * scale)

        // 调整麦克风容器的 translationY：使用常量位移，避免大比例时向下偏移过多导致底部裁剪
        groupMicStatus?.translationY = dp(3f).toFloat()
        // 确保麦克风容器在最上层，避免被其它 overlay 遮挡
        groupMicStatus?.bringToFront()

        // txtStatus 已移除，状态文本现在显示在 btnExtCenter1 中
    }

    private fun resolveKeyboardSurfaceColor(from: View? = null): Int {
        val ctx = from?.context ?: this
        return try {
            resolveKeyboardBackgroundColor(ctx)
        } catch (_: Throwable) {
            // 使用 Material3 标准浅色 Surface 作为最终回退
            0xFFFFFBFE.toInt()
        }
    }

    private fun applyKeyboardBackgroundColor(root: View) {
        val ctx = root.context
        val bg = try {
            resolveKeyboardBackgroundColor(ctx)
        } catch (_: Throwable) {
            UiColors.panelBg(ctx)
        }
        root.setBackgroundColor(bg)
    }

    private fun resolveKeyboardBackgroundColor(ctx: Context): Int {
        val baseSurface = UiColors.panelBg(ctx)
        val micContainer = UiColors.get(ctx, UiColorTokens.secondaryContainer)
        // 先在按钮和麦克风之间插值，再统一向黑色略微偏一点
        val mixed = ColorUtils.blendARGB(baseSurface, micContainer, 0.08f)
        return ColorUtils.blendARGB(mixed, 0xFF000000.toInt(), 0.04f)
    }

    // ========== 剪贴板预览监听 ==========

    private fun startClipboardPreviewListener() {
        if (clipboardManager == null) {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        }
        if (clipboardChangeListener == null) {
            clipboardChangeListener = ClipboardManager.OnPrimaryClipChangedListener {
                val text = readClipboardText() ?: return@OnPrimaryClipChangedListener
                val h = sha256Hex(text)
                if (h == lastShownClipboardHash) return@OnPrimaryClipChangedListener
                lastShownClipboardHash = h
                // 写入历史
                clipStore?.addFromClipboard(text)
                // 若当前面板打开，同步刷新
                if (isClipboardPanelVisible) refreshClipboardList()
                rootView?.post { actionHandler.showClipboardPreview(text) }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardChangeListener!!)
    }

    private fun stopClipboardPreviewListener() {
        clipboardManager?.removePrimaryClipChangedListener(clipboardChangeListener)
    }

    private fun readClipboardText(): String? {
        val cm = clipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)
        return item.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun sha256Hex(s: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b))
            sb.toString()
        } catch (t: Throwable) {
            android.util.Log.w("AsrKeyboardService", "sha256 failed", t)
            s // fallback: use raw text as hash key
        }
    }

    @Suppress("DEPRECATION")
    private fun syncSystemBarsToKeyboardBackground(anchorView: View? = null) {
        val w = window?.window ?: return
        val color = resolveKeyboardSurfaceColor(anchorView)
        w.navigationBarColor = color
        val isLight = ColorUtils.calculateLuminance(color) > 0.5
        val controller = WindowInsetsControllerCompat(w, anchorView ?: w.decorView)
        controller.isAppearanceLightNavigationBars = isLight
    }
}
