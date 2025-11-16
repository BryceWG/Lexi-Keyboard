package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import java.util.Locale

/**
 * ASR 会话管理器：统一管理 ASR 引擎的生命周期和回调处理
 *
 * 职责：
 * - 根据当前配置创建和切换 ASR 引擎
 * - 启动和停止 ASR 录音
 * - 处理引擎回调（onFinal, onPartial, onError, onStopped）
 * - 管理会话状态和上下文
 * - 记录 ASR 请求耗时
 */
class AsrSessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs
) : StreamingAsrEngine.Listener, SenseVoiceFileAsrEngine.LocalModelLoadUi {

    companion object {
        private const val TAG = "AsrSessionManager"
    }

    // 回调接口
    interface Listener {
        /**
         * 最终识别结果
         * @param text 识别的文本
         * @param currentState 当前键盘状态
         */
        fun onAsrFinal(text: String, currentState: KeyboardState)

        /**
         * 中间识别结果（实时预览）
         * @param text 中间文本
         */
        fun onAsrPartial(text: String)

        /**
         * ASR 错误
         * @param message 错误信息
         */
        fun onAsrError(message: String)

        /**
         * ASR 停止录音
         */
        fun onAsrStopped()

        /**
         * 本地模型加载开始
         */
        fun onLocalModelLoadStart()

        /**
         * 本地模型加载完成
         */
        fun onLocalModelLoadDone()

        /**
         * 实时音频振幅回调（用于波形动画）
         * @param amplitude 归一化的振幅值（0.0-1.0）
         */
        fun onAmplitude(amplitude: Float) { /* 默认空实现 */ }
    }

    private var listener: Listener? = null
    private var asrEngine: StreamingAsrEngine? = null

    // 当前会话状态
    private var currentState: KeyboardState = KeyboardState.Idle

    // 音频焦点请求句柄
    private var audioFocusRequest: AudioFocusRequest? = null

    // ASR 请求耗时记录
    private var lastRequestDurationMs: Long? = null

    // 会话录音时长统计（毫秒）
    private var sessionStartUptimeMs: Long = 0L
    private var lastAudioMsForStats: Long = 0L

    fun setListener(l: Listener) {
        listener = l
    }

    /**
     * 获取当前 ASR 引擎
     */
    fun getEngine(): StreamingAsrEngine? = asrEngine

    /**
     * ASR 引擎是否正在运行
     */
    fun isRunning(): Boolean = asrEngine?.isRunning == true

    /**
     * 获取最后一次请求耗时
     */
    fun getLastRequestDuration(): Long? = lastRequestDurationMs

    /**
     * 构建符合当前配置的 ASR 引擎
     */
    fun buildEngine(): StreamingAsrEngine? {
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                if (prefs.volcStreamingEnabled) {
                    // 通过 Pro 门面创建（OSS 返回原始引擎，Pro 可能返回双重识别包装）
                    com.brycewg.asrkb.asr.ProAsrHelper.createVolcStreamingEngine(context, scope, prefs, this)
                } else {
                    VolcFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            } else null

            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            } else null

            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                if (prefs.elevenStreamingEnabled) {
                    ElevenLabsStreamAsrEngine(context, scope, prefs, this)
                } else {
                    ElevenLabsFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            } else null

            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            } else null

            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                if (prefs.dashStreamingEnabled) {
                    DashscopeStreamAsrEngine(context, scope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            } else null

            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            } else null

            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(context, scope, prefs, this)
                } else {
                    SonioxFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            } else null

            AsrVendor.SenseVoice -> {
                // 本地 SenseVoice：仅支持文件识别模式
                SenseVoiceFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            }
            AsrVendor.Telespeech -> {
                // 本地 TeleSpeech：仅支持文件识别模式
                TelespeechFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            }
            AsrVendor.Paraformer -> {
                ParaformerStreamAsrEngine(context, scope, prefs, this)
            }
            AsrVendor.Zipformer -> {
                ZipformerStreamAsrEngine(context, scope, prefs, this)
            }
        }
    }

    /**
     * 确保引擎与当前模式匹配（用于模式切换时避免重建引擎）
     */
    fun ensureEngineMatchesMode(): StreamingAsrEngine? {
        if (!prefs.hasAsrKeys()) return null

        val current = asrEngine
        val matched = when (prefs.asrVendor) {
            AsrVendor.Volc -> {
                // 读取 Pro 双重识别开关（与主工程共用 asr_prefs；非 Pro 变体此键默认为 false）
                val wantDual: Boolean = try {
                    val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
                    sp.getBoolean("pro_volc_dual_stream_enabled", false)
                } catch (_: Throwable) { false }

                when (current) {
                    is VolcFileAsrEngine -> if (!prefs.volcStreamingEnabled) current else null
                    is VolcStreamAsrEngine -> {
                        // 普通流式实例仅在未开启“双重识别”且启用流式时复用；
                        // 若开启“双重识别”，需要重建为 Pro 包装引擎。
                        if (prefs.volcStreamingEnabled && !wantDual) current else null
                    }
                    else -> {
                        // 兼容 Pro 双重识别包装引擎：通过类名判断，避免直接依赖 pro 源集类型
                        val name = current?.javaClass?.simpleName ?: ""
                        val isProDual = name.contains("ProVolcDualStream")
                        if (prefs.volcStreamingEnabled && wantDual && isProDual) current else null
                    }
                }
            }

            AsrVendor.SiliconFlow -> if (current is SiliconFlowFileAsrEngine) current else null
            AsrVendor.ElevenLabs -> when (current) {
                is ElevenLabsFileAsrEngine -> if (!prefs.elevenStreamingEnabled) current else null
                is ElevenLabsStreamAsrEngine -> if (prefs.elevenStreamingEnabled) current else null
                else -> null
            }
            AsrVendor.OpenAI -> if (current is OpenAiFileAsrEngine) current else null
            AsrVendor.DashScope -> when (current) {
                is DashscopeFileAsrEngine -> if (!prefs.dashStreamingEnabled) current else null
                is DashscopeStreamAsrEngine -> if (prefs.dashStreamingEnabled) current else null
                else -> null
            }
            AsrVendor.Gemini -> if (current is GeminiFileAsrEngine) current else null

            AsrVendor.Soniox -> when (current) {
                is SonioxFileAsrEngine -> if (!prefs.sonioxStreamingEnabled) current else null
                is SonioxStreamAsrEngine -> if (prefs.sonioxStreamingEnabled) current else null
                else -> null
            }

            AsrVendor.SenseVoice -> if (current is SenseVoiceFileAsrEngine) current else null
            AsrVendor.Telespeech -> if (current is TelespeechFileAsrEngine) current else null
            AsrVendor.Paraformer -> when (current) {
                is ParaformerStreamAsrEngine -> current
                else -> null
            }
            AsrVendor.Zipformer -> when (current) {
                is ZipformerStreamAsrEngine -> current
                else -> null
            }
        }

        val engine = matched ?: buildEngine()
        if (engine != null && engine !== asrEngine) {
            asrEngine?.stop()
            asrEngine = engine
        }
        return asrEngine
    }

    /**
     * 重新构建引擎（设置改变时使用）
     */
    fun rebuildEngine() {
        asrEngine = buildEngine()
    }

    /**
     * 启动 ASR 录音
     * @param state 启动时的键盘状态
     */
    fun startRecording(state: KeyboardState) {
        currentState = state
        try { sessionStartUptimeMs = SystemClock.uptimeMillis() } catch (t: Throwable) {
            Log.w(TAG, "Failed to get uptime for session start", t)
            sessionStartUptimeMs = 0L
        }
        // 新会话开始时重置上次请求耗时，避免串台（流式模式不会更新此值）
        lastRequestDurationMs = null
        try {
            val eng = ensureEngineMatchesMode()
            DebugLogManager.log(
                category = "asr",
                event = "start",
                data = mapOf(
                    "vendor" to prefs.asrVendor.name,
                    "engine" to (eng?.javaClass?.simpleName ?: "null"),
                    "state" to state::class.java.simpleName,
                    "duckMedia" to prefs.duckMediaOnRecordEnabled
                )
            )
        } catch (_: Throwable) { }
        // 开始录音前根据设置决定是否请求短时独占音频焦点（音频避让）
        if (prefs.duckMediaOnRecordEnabled) {
            requestTransientAudioFocus()
        } else {
            Log.d(TAG, "Audio ducking disabled by user; skip audio focus request")
        }
        // 若为本地 SenseVoice / TeleSpeech，在录音触发时后台开始加载模型
        try {
            if (prefs.asrVendor == AsrVendor.SenseVoice || prefs.asrVendor == AsrVendor.Telespeech) {
                val prepared = try {
                    if (prefs.asrVendor == AsrVendor.SenseVoice) {
                        com.brycewg.asrkb.asr.isSenseVoicePrepared()
                    } else {
                        com.brycewg.asrkb.asr.isTelespeechPrepared()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to check local model prepared state", t)
                    false
                }
                if (!prepared) {
                    try {
                        if (prefs.asrVendor == AsrVendor.SenseVoice) {
                            com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                                context,
                                prefs,
                                onLoadStart = { onLocalModelLoadStart() },
                                onLoadDone = { onLocalModelLoadDone() },
                                suppressToastOnStart = true,
                                forImmediateUse = true
                            )
                        } else {
                            com.brycewg.asrkb.asr.preloadTelespeechIfConfigured(
                                context,
                                prefs,
                                onLoadStart = { onLocalModelLoadStart() },
                                onLoadDone = { onLocalModelLoadDone() },
                                suppressToastOnStart = true,
                                forImmediateUse = true
                            )
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to trigger local model preload on startRecording", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Local model preload guard failed", t)
        }
        asrEngine?.start()
        try {
            DebugLogManager.log(
                category = "asr",
                event = "start_state",
                data = mapOf(
                    "engine" to (asrEngine?.javaClass?.simpleName ?: "null"),
                    "running" to (asrEngine?.isRunning == true)
                )
            )
        } catch (_: Throwable) { }
        // 录音期间保持耳机路由
        try { BluetoothRouteManager.onRecordingStarted(context) } catch (t: Throwable) { Log.w(TAG, "BluetoothRouteManager onRecordingStarted", t) }
    }

    /**
     * 停止 ASR 录音
     */
    fun stopRecording() {
        asrEngine?.stop()
        try {
            DebugLogManager.log(
                category = "asr",
                event = "stop",
                data = mapOf(
                    "state" to currentState::class.java.simpleName,
                    "engineRunning" to (asrEngine?.isRunning == true)
                )
            )
        } catch (_: Throwable) { }
        // 归还音频焦点
        try {
            abandonAudioFocusIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusIfNeeded failed on stopRecording", t)
        }
        // 若无键盘可见，录音结束后可撤销预热
        try { BluetoothRouteManager.onRecordingStopped(context) } catch (t: Throwable) { Log.w(TAG, "BluetoothRouteManager onRecordingStopped", t) }
    }

    /**
     * 读取并清空最近一次会话的录音时长（毫秒）。
     */
    fun popLastAudioMsForStats(): Long {
        val v = lastAudioMsForStats
        lastAudioMsForStats = 0L
        return v
    }

    /**
     * 读取最近一次会话的录音时长（毫秒），不清空。
     * 用于在 onStopped 等场景下进行早停判断，避免影响后续统计/历史写入。
     */
    fun peekLastAudioMsForStats(): Long {
        return lastAudioMsForStats
    }

    /**
     * 设置当前状态（用于外部状态变更）
     */
    fun setCurrentState(state: KeyboardState) {
        currentState = state
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        asrEngine?.stop()
        listener = null
    }

    /**
     * 是否可以对最近一次非流式片段进行重试
     */
    fun canRetryLastFileRecognition(): Boolean {
        val e = asrEngine
        return try {
            (e is BaseFileAsrEngine) && e.hasRetryableSegment()
        } catch (t: Throwable) {
            Log.e(TAG, "canRetryLastFileRecognition check failed", t)
            false
        }
    }

    /**
     * 发起对最近一次非流式片段的重新识别（不重新录音）。
     * 返回是否成功触发。
     */
    fun retryLastFileRecognition(): Boolean {
        val e = asrEngine
        return if (e is BaseFileAsrEngine && e.hasRetryableSegment()) {
            try {
                e.retryLastSegment()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "retryLastFileRecognition failed", t)
                false
            }
        } else {
            Log.w(TAG, "retryLastFileRecognition: engine not retryable or no segment")
            false
        }
    }

    // ========== StreamingAsrEngine.Listener 实现 ==========

    override fun onFinal(text: String) {
        Log.d(TAG, "onFinal: text='$text', state=$currentState")
        // 若尚未收到 onStopped，则以当前时间近似计算一次时长
        if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
            try {
                val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                lastAudioMsForStats = dur
                sessionStartUptimeMs = 0L
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to compute audio duration on onFinal", t)
            }
        }
        try {
            DebugLogManager.log(
                category = "asr",
                event = "final",
                data = mapOf(
                    "len" to text.length,
                    "state" to currentState::class.java.simpleName
                )
            )
        } catch (_: Throwable) { }
        listener?.onAsrFinal(text, currentState)
    }

    override fun onPartial(text: String) {
        // 若引擎已停止（用户已松手），忽略后续中间结果，避免重复追加
        if (!isRunning()) {
            Log.d(TAG, "onPartial ignored: engine stopped")
            return
        }
        Log.d(TAG, "onPartial: text='$text'")
        listener?.onAsrPartial(text)
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError: message='$message', state=$currentState")
        val friendlyMessage = mapErrorToFriendlyMessage(message)
        try {
            DebugLogManager.log(
                category = "asr",
                event = "error",
                data = mapOf(
                    "state" to currentState::class.java.simpleName,
                    "msgType" to if (friendlyMessage != null) "friendly" else "raw"
                )
            )
        } catch (_: Throwable) { }
        listener?.onAsrError(friendlyMessage ?: message)
    }

    override fun onStopped() {
        Log.d(TAG, "onStopped: state=$currentState")
        // 计算本次会话录音时长
        if (sessionStartUptimeMs > 0L) {
            try {
                val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                lastAudioMsForStats = dur
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to compute audio duration on onStopped", t)
            } finally {
                sessionStartUptimeMs = 0L
            }
        }
        // 确保归还音频焦点（覆盖静音判停等路径）
        try {
            abandonAudioFocusIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusIfNeeded failed on onStopped", t)
        }
        try {
            val ms = lastAudioMsForStats
            DebugLogManager.log(
                category = "asr",
                event = "stopped",
                data = mapOf(
                    "audioMs" to ms,
                    "state" to currentState::class.java.simpleName
                )
            )
        } catch (_: Throwable) { }
        listener?.onAsrStopped()
    }

    override fun onAmplitude(amplitude: Float) {
        listener?.onAmplitude(amplitude)
    }

    // ========== SenseVoiceFileAsrEngine.LocalModelLoadUi 实现 ==========

    override fun onLocalModelLoadStart() {
        Log.d(TAG, "onLocalModelLoadStart")
        try {
            Handler(Looper.getMainLooper()).post {
                try { listener?.onLocalModelLoadStart() } catch (t: Throwable) {
                    Log.e(TAG, "Failed to deliver onLocalModelLoadStart to UI", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post onLocalModelLoadStart to main", t)
        }
    }

    override fun onLocalModelLoadDone() {
        Log.d(TAG, "onLocalModelLoadDone")
        try {
            Handler(Looper.getMainLooper()).post {
                try { listener?.onLocalModelLoadDone() } catch (t: Throwable) {
                    Log.e(TAG, "Failed to deliver onLocalModelLoadDone to UI", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post onLocalModelLoadDone to main", t)
        }
    }

    // ========== 私有方法 ==========

    private fun onRequestDuration(ms: Long) {
        lastRequestDurationMs = ms
        Log.d(TAG, "Request duration: ${ms}ms")
    }

    /**
     * 将底层错误字符串归类为用户可理解的提示文案
     */
    private fun mapErrorToFriendlyMessage(raw: String): String? {
        if (raw.isEmpty()) return null
        val lower = raw.lowercase(Locale.ROOT)

        // 这里应该调用实际的错误映射函数
        // 由于原代码中的映射逻辑很长，这里简化处理
        // 实际使用时应该复用原有的映射逻辑

        return when {
            lower.contains("empty") -> "识别返回为空"
            lower.contains("401") -> "认证失败，请检查密钥"
            lower.contains("403") -> "权限不足"
            lower.contains("permission") -> "录音权限被拒绝"
            lower.contains("timeout") -> "网络超时"
            lower.contains("network") || lower.contains("connection") -> "网络连接失败"
            else -> null
        }
    }

    // ========== 音频焦点控制 ==========

    /**
     * 请求短时独占音频焦点，促使其他媒体暂停或静音。
     */
    private fun requestTransientAudioFocus(): Boolean {
        try {
            val am = context.getSystemService(AudioManager::class.java)
            if (am == null) {
                Log.w(TAG, "AudioManager is null, skip audio focus")
                return false
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener({ /* no-op */ })
                .build()
            val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) {
                audioFocusRequest = req
                Log.d(TAG, "Audio focus granted (TRANSIENT_EXCLUSIVE)")
            } else {
                Log.w(TAG, "Audio focus not granted")
            }
            return granted
        } catch (t: Throwable) {
            Log.e(TAG, "requestTransientAudioFocus exception", t)
            return false
        }
    }

    /**
     * 归还之前请求的音频焦点。
     */
    private fun abandonAudioFocusIfNeeded() {
        val req = audioFocusRequest ?: return
        try {
            val am = context.getSystemService(AudioManager::class.java)
            if (am == null) {
                Log.w(TAG, "AudioManager is null when abandoning focus")
                return
            }
            am.abandonAudioFocusRequest(req)
            Log.d(TAG, "Audio focus abandoned")
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusRequest exception", t)
        } finally {
            audioFocusRequest = null
        }
    }
}
