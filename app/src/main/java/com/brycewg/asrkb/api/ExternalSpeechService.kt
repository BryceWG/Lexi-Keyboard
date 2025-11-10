package com.brycewg.asrkb.api

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.aidl.SpeechConfig
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 对外导出的语音服务（Binder 手写协议，兼容 AIDL 生成的代理）。
 * - 接口描述符需与 AIDL 一致：com.brycewg.asrkb.aidl.IExternalSpeechService。
 * - 方法顺序与 AIDL 保持一致，以匹配事务码。
 */
class ExternalSpeechService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val sessions = ConcurrentHashMap<Int, Session>()
    @Volatile private var nextId: Int = 1

    override fun onBind(intent: Intent?): IBinder? = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR_SVC)
                    return true
                }
                TRANSACTION_startSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val cfg = if (data.readInt() != 0) SpeechConfig.CREATOR.createFromParcel(data) else null
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    // 开关与权限检查：仅要求开启外部联动
                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    // 联通测试：当 vendorId == "mock" 时，无需录音权限，直接回调固定内容并结束
                    if (cfg?.vendorId == "mock") {
                        val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                        safe { cb.onState(sid, STATE_RECORDING, "recording") }
                        safe { cb.onPartial(sid, "【联通测试中】……") }
                        safe { cb.onFinal(sid, "言犀外部AIDL联通成功（mock）") }
                        safe { cb.onState(sid, STATE_IDLE, "final") }
                        reply?.apply { writeNoException(); writeInt(sid) }
                        return true
                    }

                    val permOk = ContextCompat.checkSelfPermission(
                        this@ExternalSpeechService,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!permOk) {
                        safe { cb.onError(-1, 401, "record permission denied") }
                        reply?.apply { writeNoException(); writeInt(-4) }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply { writeNoException(); writeInt(-2) }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = Session(sid, this@ExternalSpeechService, prefs, cb, cfg)
                    if (!s.prepare()) {
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    sessions[sid] = s
                    s.start()
                    reply?.apply { writeNoException(); writeInt(sid) }
                    return true
                }
                TRANSACTION_stopSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_cancelSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.cancel()
                    sessions.remove(sid)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_isRecording -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val r = sessions[sid]?.engine?.isRunning == true
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }
                TRANSACTION_isAnyRecording -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val r = sessions.values.any { it.engine?.isRunning == true }
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }
                TRANSACTION_getVersion -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    reply?.apply { writeNoException(); writeString(com.brycewg.asrkb.BuildConfig.VERSION_NAME) }
                    return true
                }
                // ================= 推送 PCM 模式 =================
                TRANSACTION_startPcmSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val cfg = if (data.readInt() != 0) SpeechConfig.CREATOR.createFromParcel(data) else null
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply { writeNoException(); writeInt(-2) }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = Session(sid, this@ExternalSpeechService, prefs, cb, cfg)
                    if (!s.preparePushPcm()) {
                        reply?.apply { writeNoException(); writeInt(-5) }
                        return true
                    }
                    sessions[sid] = s
                    s.start()
                    reply?.apply { writeNoException(); writeInt(sid) }
                    return true
                }
                TRANSACTION_writePcm -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    val sr = data.readInt()
                    val ch = data.readInt()
                    try {
                        val e = sessions[sid]?.engine
                        if (e is com.brycewg.asrkb.asr.ExternalPcmConsumer) {
                            e.appendPcm(bytes, sr, ch)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "writePcm failed for sid=$sid", t)
                    }
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_finishPcm -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private class Session(
        private val id: Int,
        private val context: Context,
        private val prefs: Prefs,
        private val cb: CallbackProxy,
        private val cfg: SpeechConfig?
    ) : StreamingAsrEngine.Listener {
        var engine: StreamingAsrEngine? = null
        // 统计：录音起止与耗时（用于历史记录展示）
        private var sessionStartUptimeMs: Long = 0L
        private var lastAudioMsForStats: Long = 0L
        private var lastRequestDurationMs: Long? = null

        fun prepare(): Boolean {
            // 完全跟随应用内当前设置：供应商与是否流式均以 Prefs 为准
            val vendor = prefs.asrVendor
            val streamingPref = resolveStreamingBySettings(vendor)
            engine = buildEngine(vendor, streamingPref)
            return engine != null
        }

        fun preparePushPcm(): Boolean {
            val vendor = prefs.asrVendor
            val streamingPref = resolveStreamingBySettings(vendor)
            engine = buildPushPcmEngine(vendor, streamingPref)
            return engine != null
        }

        fun start() {
            safe { cb.onState(id, STATE_RECORDING, "recording") }
            try {
                sessionStartUptimeMs = SystemClock.uptimeMillis()
                // 新会话开始时重置上次请求耗时，避免串台（流式模式不会更新此值）
                lastRequestDurationMs = null
                lastAudioMsForStats = 0L
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to mark session start", t)
            }
            engine?.start()
        }

        fun stop() {
            engine?.stop()
            safe { cb.onState(id, STATE_PROCESSING, "processing") }
        }

        fun cancel() {
            try { engine?.stop() } catch (_: Throwable) {}
            safe { cb.onState(id, STATE_IDLE, "canceled") }
        }

        private fun resolveStreamingBySettings(vendor: AsrVendor): Boolean {
            return when (vendor) {
                AsrVendor.Volc -> prefs.volcStreamingEnabled
                AsrVendor.DashScope -> prefs.dashStreamingEnabled
                AsrVendor.Soniox -> prefs.sonioxStreamingEnabled
                // 本地 sherpa-onnx：Paraformer/Zipformer 仅流式；SenseVoice 仅非流式
                AsrVendor.Paraformer, AsrVendor.Zipformer -> true
                AsrVendor.SenseVoice -> false
                // 其他云厂商（OpenAI/Gemini/Eleven/SiliconFlow）仅非流式
                AsrVendor.OpenAI, AsrVendor.Gemini, AsrVendor.ElevenLabs, AsrVendor.SiliconFlow -> false
            }
        }

        private fun buildEngine(vendor: AsrVendor, streamingPreferred: Boolean): StreamingAsrEngine? {
            val scope = CoroutineScope(Dispatchers.Main)
            return when (vendor) {
                AsrVendor.Volc -> if (streamingPreferred) {
                    ProAsrHelper.createVolcStreamingEngine(context, scope, prefs, this)
                } else {
                    VolcFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                }
                AsrVendor.SiliconFlow -> SiliconFlowFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.ElevenLabs -> ElevenLabsFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.OpenAI -> OpenAiFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.DashScope -> if (streamingPreferred) {
                    DashscopeStreamAsrEngine(context, scope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                }
                AsrVendor.Gemini -> GeminiFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.Soniox -> if (streamingPreferred) {
                    SonioxStreamAsrEngine(context, scope, prefs, this)
                } else {
                    SonioxFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                }
                AsrVendor.SenseVoice -> SenseVoiceFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.Paraformer -> ParaformerStreamAsrEngine(context, scope, prefs, this)
                AsrVendor.Zipformer -> ZipformerStreamAsrEngine(context, scope, prefs, this)
            }
        }

        private fun buildPushPcmEngine(vendor: AsrVendor, streamingPreferred: Boolean): StreamingAsrEngine? {
            val scope = CoroutineScope(Dispatchers.Main)
            return when (vendor) {
                // 火山引擎：该流式就流式，该非流式就非流式
                AsrVendor.Volc -> if (streamingPreferred) {
                    // Pro: 走双重识别（推送PCM版本）；OSS: 回落到原始流式（externalPcmMode=true）
                    com.brycewg.asrkb.asr.ProAsrHelper.createVolcStreamingEngineForPushPcm(
                        context, scope, prefs, this
                    )
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context, scope, prefs, this,
                        com.brycewg.asrkb.asr.VolcFileAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    )
                }
                // 阿里 DashScope：依据设置走流式或非流式
                AsrVendor.DashScope -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.DashscopeStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context, scope, prefs, this,
                        com.brycewg.asrkb.asr.DashscopeFileAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    )
                }
                // Soniox：依据设置走流式或非流式
                AsrVendor.Soniox -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.SonioxStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context, scope, prefs, this,
                        com.brycewg.asrkb.asr.SonioxFileAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    )
                }
                // 其他云厂商：仅非流式
                AsrVendor.ElevenLabs -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                AsrVendor.OpenAI -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.OpenAiFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                AsrVendor.Gemini -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.GeminiFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                AsrVendor.SiliconFlow -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                // 本地：Paraformer/Zipformer 固定流式
                AsrVendor.Paraformer -> com.brycewg.asrkb.asr.ParaformerStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                AsrVendor.Zipformer -> com.brycewg.asrkb.asr.ZipformerStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                // SenseVoice：非流式 → 走文件引擎 + 通用适配器
                AsrVendor.SenseVoice -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
            }
        }

        override fun onFinal(text: String) {
            // 若尚未收到 onStopped，则以当前时间近似计算一次时长
            if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                    sessionStartUptimeMs = 0L
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration on final", t)
                }
            }
            val doAi = try { prefs.postProcessEnabled && prefs.hasLlmKeys() } catch (_: Throwable) { false }
            if (doAi) {
                // 执行带 AI 的完整后处理链（IO 在线程内切换）
                CoroutineScope(Dispatchers.Main).launch {
                    val out = try {
                        com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(context, prefs, text).text.ifBlank { text }
                    } catch (t: Throwable) {
                        Log.w(TAG, "applyWithAi failed, fallback to simple", t)
                        try { com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text) } catch (_: Throwable) { text }
                    }
                    // 记录使用统计与识别历史（来源标记为 ime；尊重开关）
                    try {
                        val audioMs = lastAudioMsForStats
                        val procMs = lastRequestDurationMs ?: 0L
                        val chars = try { com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(out) } catch (_: Throwable) { out.length }
                        if (!prefs.disableUsageStats) {
                            prefs.recordUsageCommit("ime", prefs.asrVendor, audioMs, chars, procMs)
                        }
                        if (!prefs.disableAsrHistory) {
                            val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                            store.add(
                                com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                    timestamp = System.currentTimeMillis(),
                                    text = out,
                                    vendorId = prefs.asrVendor.id,
                                    audioMs = audioMs,
                                    procMs = procMs,
                                    source = "ime",
                                    aiProcessed = true,
                                    charCount = chars
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add ASR history (external, ai)", e)
                    }
                    safe { cb.onFinal(id, out) }
                    safe { cb.onState(id, STATE_IDLE, "final") }
                    try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on final failed", t) }
                }
            } else {
                // 仅本地过滤：去尾标点 +（Pro）繁体
                val out = try {
                    com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
                } catch (t: Throwable) {
                    Log.w(TAG, "applySimple failed, fallback to raw text", t)
                    text
                }
                // 记录使用统计与识别历史（来源标记为 ime；尊重开关）
                try {
                    val audioMs = lastAudioMsForStats
                    val procMs = lastRequestDurationMs ?: 0L
                    val chars = try { com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(out) } catch (_: Throwable) { out.length }
                    if (!prefs.disableUsageStats) {
                        prefs.recordUsageCommit("ime", prefs.asrVendor, audioMs, chars, procMs)
                    }
                    if (!prefs.disableAsrHistory) {
                        val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                        store.add(
                            com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                timestamp = System.currentTimeMillis(),
                                text = out,
                                vendorId = prefs.asrVendor.id,
                                audioMs = audioMs,
                                procMs = procMs,
                                source = "ime",
                                aiProcessed = false,
                                charCount = chars
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add ASR history (external, simple)", e)
                }
                safe { cb.onFinal(id, out) }
                safe { cb.onState(id, STATE_IDLE, "final") }
                try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on final failed", t) }
            }
        }

        override fun onError(message: String) {
            safe {
                cb.onError(id, 500, message)
                cb.onState(id, STATE_ERROR, message)
            }
            try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on error failed", t) }
        }

        override fun onPartial(text: String) { if (text.isNotEmpty()) safe { cb.onPartial(id, text) } }

        override fun onStopped() {
            // 计算一次会话录音时长
            if (sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration on stop", t)
                } finally {
                    sessionStartUptimeMs = 0L
                }
            }
            safe { cb.onState(id, STATE_PROCESSING, "processing") }
        }

        override fun onAmplitude(amplitude: Float) { safe { cb.onAmplitude(id, amplitude) } }
    }

    private class CallbackProxy(private val remote: IBinder?) {
        fun onState(sessionId: Int, state: Int, msg: String) {
            transact(CB_onState) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(state)
                data.writeString(msg)
            }
        }
        fun onPartial(sessionId: Int, text: String) {
            transact(CB_onPartial) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onFinal(sessionId: Int, text: String) {
            transact(CB_onFinal) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onError(sessionId: Int, code: Int, message: String) {
            transact(CB_onError) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(code)
                data.writeString(message)
            }
        }
        fun onAmplitude(sessionId: Int, amp: Float) {
            transact(CB_onAmplitude) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeFloat(amp)
            }
        }

        private inline fun transact(code: Int, fill: (Parcel) -> Unit) {
            val b = remote ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                fill(data)
                b.transact(code, data, reply, 0)
                reply.readException()
            } catch (t: Throwable) {
                Log.w(TAG, "callback transact failed: code=$code", t)
            } finally {
                try { data.recycle() } catch (_: Throwable) {}
                try { reply.recycle() } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private const val TAG = "ExternalSpeechSvc"

        // 与 AIDL 生成的 Stub 保持一致的描述符与事务号
        private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
        private const val TRANSACTION_startSession = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_stopSession = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_cancelSession = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_isRecording = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_isAnyRecording = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_getVersion = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TRANSACTION_startPcmSession = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val TRANSACTION_writePcm = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val TRANSACTION_finishPcm = IBinder.FIRST_CALL_TRANSACTION + 8

        private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
        private const val CB_onState = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val CB_onPartial = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CB_onFinal = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CB_onError = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val CB_onAmplitude = IBinder.FIRST_CALL_TRANSACTION + 4

        private const val STATE_IDLE = 0
        private const val STATE_RECORDING = 1
        private const val STATE_PROCESSING = 2
        private const val STATE_ERROR = 3

        private inline fun safe(block: () -> Unit) { try { block() } catch (t: Throwable) { Log.w(TAG, "callback failed", t) } }
    }

    // 统一的会话清理入口：在 onFinal/onError 触发后移除，避免内存泄漏
    private fun onSessionDone(sessionId: Int) {
        try {
            sessions.remove(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "sessions.remove failed for id=$sessionId", t)
        }
    }
}
