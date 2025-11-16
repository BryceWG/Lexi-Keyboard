package com.brycewg.asrkb.ui.floatingball

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.os.SystemClock
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService.FocusContext
import com.brycewg.asrkb.util.TextSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ASR 会话管理器
 * 负责 ASR 引擎的生命周期管理、结果处理和超时兜底
 */
class AsrSessionManager(
    private val context: Context,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val listener: AsrSessionListener
) : StreamingAsrEngine.Listener {

    companion object {
        private const val TAG = "AsrSessionManager"
        private const val PROCESSING_TIMEOUT_MS = 8000L
    }

    interface AsrSessionListener {
        fun onSessionStateChanged(state: FloatingBallState)
        fun onResultCommitted(text: String, success: Boolean)
        fun onError(message: String)
    }

    private var asrEngine: StreamingAsrEngine? = null
    private val postproc = LlmPostProcessor()

    // 会话上下文
    private var focusContext: FocusContext? = null
    private var lastPartialForPreview: String? = null
    private var markerInserted: Boolean = false
    private var markerChar: String? = null

    // 超时控制
    private var processingTimeoutJob: Job? = null
    private var hasCommittedResult: Boolean = false

    // 统计：录音时长
    private var sessionStartUptimeMs: Long = 0L
    private var lastAudioMsForStats: Long = 0L
    // 统计：非流式请求处理耗时（毫秒）
    private var lastRequestDurationMs: Long? = null
    // 标记：最近一次提交是否实际使用了 AI 输出
    private var lastAiUsed: Boolean = false
    // 音频焦点请求句柄
    private var audioFocusRequest: AudioFocusRequest? = null

    /** 开始录音 */
    fun startRecording() {
        Log.d(TAG, "startRecording called")
        try { sessionStartUptimeMs = SystemClock.uptimeMillis() } catch (t: Throwable) {
            Log.w(TAG, "Failed to read uptime for session start", t)
            sessionStartUptimeMs = 0L
        }
        // 新会话开始：重置请求耗时，避免上一轮的值串台
        lastRequestDurationMs = null
        // 开始录音前根据设置决定是否请求短时独占音频焦点（音频避让）
        if (prefs.duckMediaOnRecordEnabled) {
            requestTransientAudioFocus()
        } else {
            Log.d(TAG, "Audio ducking disabled by user; skip audio focus request")
        }

        // 检查本地 SenseVoice 模型（如果需要）
        if (!checkSenseVoiceModel()) {
            listener.onError(context.getString(com.brycewg.asrkb.R.string.error_sensevoice_model_missing))
            return
        }

        // 清理上次会话
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        processingTimeoutJob = null
        hasCommittedResult = false

        // 写入兼容模式：为命中包名注入占位符（粘贴方式），屏蔽原文本干扰
        tryFixCompatPlaceholderIfNeeded()

        // 构建引擎
        asrEngine = buildEngineForCurrentMode()
        Log.d(TAG, "ASR engine created: ${asrEngine?.javaClass?.simpleName}")

        // 记录焦点上下文（占位后再取，保持与参考版本一致）
        focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to refresh focus context", e)
            }
        }, 120)
        lastPartialForPreview = null

        // 启动引擎
        listener.onSessionStateChanged(FloatingBallState.Recording)
        asrEngine?.start()
        try { BluetoothRouteManager.onRecordingStarted(context) } catch (t: Throwable) { Log.w(TAG, "BluetoothRouteManager onRecordingStarted", t) }
    }

    /** 停止录音 */
    fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        asrEngine?.stop()
        // 归还音频焦点
        try {
            abandonAudioFocusIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusIfNeeded failed on stopRecording", t)
        }
        try { BluetoothRouteManager.onRecordingStopped(context) } catch (t: Throwable) { Log.w(TAG, "BluetoothRouteManager onRecordingStopped", t) }

        // 进入处理阶段
        listener.onSessionStateChanged(FloatingBallState.Processing)

        // 启动超时兜底
        startProcessingTimeout()
    }

    /**
     * 读取并清空最近一次会话的录音时长（毫秒）。
     */
    fun popLastAudioMsForStats(): Long {
        val v = lastAudioMsForStats
        lastAudioMsForStats = 0L
        return v
    }

    /** 最近一次请求耗时（毫秒），仅非流式模式有效 */
    fun getLastRequestDuration(): Long? = lastRequestDurationMs

    /** 最近一次提交是否实际使用了 AI 输出 */
    fun wasLastAiUsed(): Boolean = lastAiUsed

    /** 清理会话 */
    fun cleanup() {
        try {
            asrEngine?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop ASR engine", e)
        }
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel timeout job", e)
        }
        try {
            abandonAudioFocusIfNeeded()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to abandon audio focus in cleanup", e)
        }
    }

    // ==================== StreamingAsrEngine.Listener ====================

    override fun onFinal(text: String) {
        Log.d(TAG, "onFinal called with text: $text")
        serviceScope.launch {
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onFinal", e)
            }
            processingTimeoutJob = null

            // 若已由兜底提交，忽略后续 onFinal
            if (hasCommittedResult && asrEngine?.isRunning != true) {
                Log.w(TAG, "Result already committed by fallback; ignoring residual onFinal")
                return@launch
            }

            var finalText = text
            lastAiUsed = false
            val stillRecording = (asrEngine?.isRunning == true)
            // 若未收到 onStopped，则在此近似计算录音时长
            if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration in onFinal", t)
                } finally {
                    sessionStartUptimeMs = 0L
                }
            }

            // 统一使用 AsrFinalFilters：含预修剪/LLM/后修剪/繁体转换
            if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                Log.d(TAG, "Starting AI post-processing (stillRecording=$stillRecording)")
                if (!stillRecording) {
                    listener.onSessionStateChanged(FloatingBallState.Processing)
                }
                val res = try { com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(context, prefs, text, postproc) } catch (t: Throwable) {
                    Log.e(TAG, "applyWithAi failed", t)
                    com.brycewg.asrkb.asr.LlmPostProcessor.LlmProcessResult(false, text)
                }
                if (!res.ok) Log.w(TAG, "Post-processing failed; using processed text anyway")
                finalText = res.text.ifBlank { text }
                lastAiUsed = (res.usedAi && res.ok)
                Log.d(TAG, "Post-processing completed: $finalText")
            } else {
                finalText = com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
                lastAiUsed = false
            }

            // 更新状态
            if (asrEngine?.isRunning == true) {
                listener.onSessionStateChanged(FloatingBallState.Recording)
            } else {
                listener.onSessionStateChanged(FloatingBallState.Idle)
            }

            // 插入文本
            if (finalText.isNotEmpty()) {
                val success = insertTextToFocus(finalText)
                listener.onResultCommitted(finalText, success)
            } else {
                Log.w(TAG, "Final text is empty")
                listener.onError(context.getString(com.brycewg.asrkb.R.string.asr_error_empty_result))
            }

            // 清理会话上下文
            focusContext = null
            lastPartialForPreview = null
            markerInserted = false
            markerChar = null
        }
    }

    override fun onStopped() {
        serviceScope.launch {
            listener.onSessionStateChanged(FloatingBallState.Processing)
            // 计算本次会话录音时长
            if (sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration in onStopped", t)
                } finally {
                    sessionStartUptimeMs = 0L
                }
            }
            // 确保归还音频焦点
            try {
                abandonAudioFocusIfNeeded()
            } catch (t: Throwable) {
                Log.w(TAG, "abandonAudioFocusIfNeeded failed in onStopped", t)
            }
            startProcessingTimeout()
        }
    }

    // ========== 音频焦点控制 ==========
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

    override fun onPartial(text: String) {
        if (text.isEmpty() || lastPartialForPreview == text) return
        val ctx = focusContext ?: return
        val toWrite = ctx.prefix + text + ctx.suffix
        Log.d(TAG, "onPartial preview: $text")

        serviceScope.launch {
            com.brycewg.asrkb.ui.AsrAccessibilityService.insertTextSilent(toWrite)
            val prefixLenForCursor = stripMarkersIfAny(ctx.prefix).length
            val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
            com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
        }
        lastPartialForPreview = text
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError called: $message")
        serviceScope.launch {
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onError", e)
            }
            processingTimeoutJob = null

            listener.onSessionStateChanged(FloatingBallState.Error(message))
            listener.onError(message)

            // 清理会话上下文
            focusContext = null
            lastPartialForPreview = null
            markerInserted = false
            markerChar = null
        }
    }

    // ==================== 私有辅助方法 ====================

    private fun checkSenseVoiceModel(): Boolean {
        if (prefs.asrVendor != AsrVendor.SenseVoice && prefs.asrVendor != AsrVendor.Telespeech) return true

        val prepared = try {
            when (prefs.asrVendor) {
                AsrVendor.SenseVoice -> com.brycewg.asrkb.asr.isSenseVoicePrepared()
                AsrVendor.Telespeech -> com.brycewg.asrkb.asr.isTelespeechPrepared()
                else -> true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check local model preparation", e)
            false
        }
        if (prepared) return true

        // 检查模型文件
        val base = try {
            context.getExternalFilesDir(null)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get external files dir", e)
            context.filesDir
        }
        return if (prefs.asrVendor == AsrVendor.SenseVoice) {
            val probeRoot = java.io.File(base, "sensevoice")
            val variant = try {
                prefs.svModelVariant
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get SenseVoice variant", e)
                "small-int8"
            }
            val variantDir = if (variant == "small-full") {
                java.io.File(probeRoot, "small-full")
            } else {
                java.io.File(probeRoot, "small-int8")
            }
            val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir)
                ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
            found != null
        } else {
            val probeRoot = java.io.File(base, "telespeech")
            val variant = try {
                prefs.tsModelVariant
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get TeleSpeech variant", e)
                "int8"
            }
            val variantDir = when (variant) {
                "full" -> java.io.File(probeRoot, "full")
                else -> java.io.File(probeRoot, "int8")
            }
            val found = com.brycewg.asrkb.asr.findTsModelDir(variantDir)
                ?: com.brycewg.asrkb.asr.findTsModelDir(probeRoot)
            found != null
        }
    }

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                if (prefs.volcStreamingEnabled) {
                    // 通过 Pro 门面创建（OSS 返回原始引擎，Pro 可能返回双重识别包装）
                    com.brycewg.asrkb.asr.ProAsrHelper.createVolcStreamingEngine(context, serviceScope, prefs, this)
                } else {
                    VolcFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            } else null
            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            } else null
            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                if (prefs.elevenStreamingEnabled) {
                    ElevenLabsStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    ElevenLabsFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            } else null
            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            } else null
            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                if (prefs.dashStreamingEnabled) {
                    DashscopeStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            } else null
            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            } else null
            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    SonioxFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            } else null
            AsrVendor.SenseVoice -> {
                // 本地 SenseVoice：仅支持文件识别模式
                SenseVoiceFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            }
            AsrVendor.Telespeech -> {
                // 本地 TeleSpeech：仅支持文件识别模式
                TelespeechFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            }
            AsrVendor.Paraformer -> {
                ParaformerStreamAsrEngine(context, serviceScope, prefs, this)
            }
            AsrVendor.Zipformer -> {
                ZipformerStreamAsrEngine(context, serviceScope, prefs, this)
            }
        }
    }

    private fun onRequestDuration(ms: Long) {
        lastRequestDurationMs = ms
        Log.d(TAG, "Request duration: ${ms}ms")
    }

    private fun startProcessingTimeout() {
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        processingTimeoutJob = serviceScope.launch {
            delay(PROCESSING_TIMEOUT_MS)
            if (!hasCommittedResult) {
                handleProcessingTimeout()
            }
        }
    }

    private suspend fun handleProcessingTimeout() {
        val candidate = lastPartialForPreview?.trim().orEmpty()
        Log.w(TAG, "Finalize timeout; fallback with preview='$candidate'")
        if (candidate.isEmpty()) {
            Log.w(TAG, "Fallback has no candidate text; only clear state")
            listener.onSessionStateChanged(FloatingBallState.Idle)
            return
        }

        val textOut = if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            try {
                val res = com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(context, prefs, candidate, postproc)
                lastAiUsed = (res.usedAi && res.ok)
                res.text
            } catch (t: Throwable) {
                Log.e(TAG, "applyWithAi failed in timeout fallback", t)
                lastAiUsed = false
                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate)
            }
        } else {
            lastAiUsed = false
            com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate)
        }

        val success = insertTextToFocus(textOut)
        Log.d(TAG, "Fallback inserted=$success text='$textOut'")
        listener.onResultCommitted(textOut, success)
        hasCommittedResult = true

        listener.onSessionStateChanged(FloatingBallState.Idle)
        focusContext = null
        lastPartialForPreview = null
        markerInserted = false
        markerChar = null
    }

    private fun insertTextToFocus(text: String): Boolean {
        val ctx = focusContext ?: com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
        var toWrite = if (ctx != null) ctx.prefix + text + ctx.suffix else text
        toWrite = stripMarkersIfAny(toWrite)
        Log.d(TAG, "Inserting text: $toWrite (previewCtx=${ctx != null})")

        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage()
        // 写入粘贴方案：命中规则则仅复制到剪贴板并提示
        val writePaste = try {
            prefs.floatingWriteTextPasteEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get write paste preference", e)
            false
        }
        val pasteTarget = pkg != null && isPackageInPasteTargets(pkg)
        if (writePaste && pasteTarget) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ASR Result", text)
                cm.setPrimaryClip(clip)
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.brycewg.asrkb.R.string.floating_asr_copied),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to copy to clipboard (writePaste)", e)
            }
            // 不尝试插入文本：返回 false 表示未写入
            return false
        }

        // 统一使用通用插入方法（兼容模式的区别仅在于占位符的注入与清理）
        val wrote: Boolean = com.brycewg.asrkb.ui.AsrAccessibilityService.insertText(context, toWrite)

        if (wrote) {
            try {
                if (!prefs.disableUsageStats) {
                    prefs.addAsrChars(TextSanitizer.countEffectiveChars(text))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to add ASR chars", e)
            }
            // 光标应定位到“前缀 + 新文本”的末尾；占位符已从前缀中移除
            val prefixLenForCursor = stripMarkersIfAny(ctx?.prefix ?: "").length
            val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
            com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
        }

        return wrote
    }

    private fun isPackageInPasteTargets(pkg: String): Boolean {
        val raw = try {
            prefs.floatingWritePastePackages
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get paste packages", e)
            ""
        }
        val rules = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (rules.any { it.equals("all", ignoreCase = true) }) return true
        // 前缀匹配（包名边界）
        return rules.any { rule -> pkg == rule || pkg.startsWith("$rule.") }
    }

    private fun tryFixCompatPlaceholderIfNeeded() {
        markerInserted = false
        markerChar = null
        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage() ?: return
        val compat = try {
            prefs.floatingWriteTextCompatEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get write compat preference", e)
            true
        }
        if (!compat || !isPackageInCompatTargets(pkg)) return

        val candidates = listOf("\u2060", "\u200B")
        for (m in candidates) {
            val ok = com.brycewg.asrkb.ui.AsrAccessibilityService.pasteTextSilent(m)
            if (ok) {
                markerInserted = true
                markerChar = m
                Log.d(TAG, "Compat fix: injected marker ${Integer.toHexString(m.codePointAt(0))}")
                break
            }
        }
    }

    private fun stripMarkersIfAny(s: String): String {
        var out = s
        markerChar?.let { if (it.isNotEmpty()) out = out.replace(it, "") }
        out = out.replace("\u2060", "")
        out = out.replace("\u200B", "")
        return out
    }


    private fun isPackageInCompatTargets(pkg: String): Boolean {
        val raw = try {
            prefs.floatingWriteCompatPackages
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get compat packages", e)
            ""
        }
        val rules = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        // 前缀匹配（包名边界）
        return rules.any { rule -> pkg == rule || pkg.startsWith("$rule.") }
    }
}
