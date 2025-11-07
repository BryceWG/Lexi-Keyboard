package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎 WebSocket 流式 ASR 实现（bigmodel_async 二进制协议）。
 * - 启动后通过 WS 建连，先发送 full client request，再按 200ms 分包发送音频（gzip 压缩）。
 * - 接收服务端 JSON 结果，onPartial/onFinal 回调到 IME。
 */
class VolcStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "VolcStreamAsrEngine"
        private const val WS_ENDPOINT_BIDI_ASYNC = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        private const val WS_ENDPOINT_NOSTREAM = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
        private const val DEFAULT_STREAM_RESOURCE = "volc.bigasr.sauc.duration"

        private const val PROTOCOL_VERSION = 0x1
        private const val HEADER_SIZE_UNITS = 0x1 // 4 bytes

        // Message types
        private const val MSG_TYPE_FULL_CLIENT_REQ = 0x1
        private const val MSG_TYPE_AUDIO_ONLY_CLIENT_REQ = 0x2
        private const val MSG_TYPE_FULL_SERVER_RESP = 0x9
        private const val MSG_TYPE_ERROR_SERVER = 0xF

        // Serialization
        private const val SERIALIZE_NONE = 0x0
        private const val SERIALIZE_JSON = 0x1

        // Compression
        @Suppress("unused")
        private const val COMPRESS_NONE = 0x0
        private const val COMPRESS_GZIP = 0x1

        // Flags
        private const val FLAG_AUDIO_LAST = 0x2 // 客户端音频最后一包
        private const val FLAG_SERVER_FINAL_MASK = 0x3 // 服务端最终结果标志
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // 持久流式
        .build()

    private val running = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private val awaitingFinal = AtomicBoolean(false)
    private val audioLastSent = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private var audioJob: Job? = null
    private var closeJob: Job? = null
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        // 权限校验
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }
        running.set(true)
        synchronized(prebufferLock) { prebuffer.clear() }
        audioLastSent.set(false)
        openWebSocketAndStartAudio()
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        awaitingFinal.set(true)
        // 停止采集线程
        audioJob?.cancel()
        audioJob = null

        // 在关闭前，尽量将预缓冲音频冲刷出去，并发送“最后一包”标记，避免尾音丢失
        closeJob?.cancel()
        closeJob = scope.launch(Dispatchers.IO) {
            // 若 WS 尚未就绪，等待短暂时间以完成握手（避免清空预缓冲导致空音频）
            val wsReadyWaitMs = 500
            var waited = 0
            while (!wsReady.get() && waited < wsReadyWaitMs) {
                delay(50)
                waited += 50
            }
            // 就绪后冲刷预缓冲
            if (wsReady.get()) {
                var flushed: Array<ByteArray>? = null
                synchronized(prebufferLock) {
                    if (prebuffer.isNotEmpty()) {
                        flushed = prebuffer.toTypedArray()
                        prebuffer.clear()
                    }
                }
                flushed?.forEach { b ->
                    try { sendAudioFrame(b, last = false) } catch (_: Throwable) { }
                }
                // 发送最后一包标记（空载荷亦可作为结束信号）
                if (!audioLastSent.get()) {
                    try {
                        sendAudioFrame(byteArrayOf(), last = true)
                        audioLastSent.set(true)
                    } catch (_: Throwable) { }
                }
            }

            // 等待最终结果返回或超时再关闭连接：严格等待最终标志，兜底 10s 超时
            val finalWaitMs = 10_000L
            var left = finalWaitMs
            while (awaitingFinal.get() && left > 0) {
                delay(50)
                left -= 50
            }
            try { ws?.close(1000, "stop") } catch (_: Throwable) { }
            ws = null
            wsReady.set(false)
        }
    }

    private fun openWebSocketAndStartAudio() {
        val connectId = UUID.randomUUID().toString()
        startAudioStreaming()
        val wsUrl = if (prefs.volcBidiStreamingEnabled) WS_ENDPOINT_BIDI_ASYNC else WS_ENDPOINT_NOSTREAM
        val req = Request.Builder()
            .url(wsUrl)
            .headers(
                Headers.headersOf(
                    "X-Api-App-Key", prefs.appKey,
                    "X-Api-Access-Key", prefs.accessKey,
                    // 使用小时版资源（可根据需要切换并发版）
                    "X-Api-Resource-Id", DEFAULT_STREAM_RESOURCE,
                    "X-Api-Connect-Id", connectId
                )
            )
            .build()

        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened, sending full client request")
                // 发送 full client request
                try {
                    val full = buildFullClientRequestJson()
                    val payload = gzip(full.toByteArray(Charsets.UTF_8))
                    val frame = buildClientFrame(
                        messageType = MSG_TYPE_FULL_CLIENT_REQ,
                        flags = 0,
                        serialization = SERIALIZE_JSON,
                        compression = COMPRESS_GZIP,
                        payload = payload
                    )
                    webSocket.send(ByteString.of(*frame))
                    wsReady.set(true)
                    Log.d(TAG, "WebSocket ready, audio streaming can begin")
                    // 若用户在握手期间已 stop()，此处立即冲刷预缓冲并发送最后标记，避免尾段丢失
                    if (!running.get() && awaitingFinal.get()) {
                        flushPrebufferAndSendLast()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to send full client request", t)
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                    stop()
                    return
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleServerMessage(bytes)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to handle server message", t)
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing with code $code: $reason")
                try { webSocket.close(code, reason) } catch (t: Throwable) {
                    Log.e(TAG, "Failed to close WebSocket", t)
                }
                wsReady.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failed: ${t.message}", t)
                wsReady.set(false)
                if (running.get()) {
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                }
                running.set(false)
            }
        })
    }

    /**
     * 启动音频采集流并发送到 WebSocket
     *
     * 使用 AudioCaptureManager 简化音频采集逻辑，包括：
     * - 权限检查和 AudioRecord 初始化
     * - 音频源回退（VOICE_RECOGNITION -> MIC）
     * - 预热逻辑（两帧探测 + 坏源回退）
     */
    private fun startAudioStreaming() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = if (prefs.volcFirstCharAccelEnabled) 100 else 200
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            // 长按说话模式下由用户松手决定停止，绕过 VAD 自动判停
            val vadDetector = if (isVadAutoStopEnabled(context, prefs))
                VadDetector(context, sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            else null

            val maxFrames = (2000 / chunkMillis).coerceAtLeast(1) // 预缓冲上限≈2s

            try {
                Log.d(TAG, "Starting audio capture with chunk size ${chunkMillis}ms")
                audioManager.startCapture().collect { audioChunk ->
                    if (!isActive || !running.get()) return@collect

                    // 计算并发送音频振幅（用于波形动画）
                    try {
                        val amplitude = com.brycewg.asrkb.asr.calculateNormalizedAmplitude(audioChunk)
                        listener.onAmplitude(amplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

                    // VAD 自动判停
                    if (vadDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        try { listener.onStopped() } catch (t: Throwable) {
                            Log.e(TAG, "Failed to notify stopped", t)
                        }
                        stop()
                        return@collect
                    }

                    // 发送音频帧
                    if (!wsReady.get()) {
                        // WS 未就绪：写入预缓冲（滑窗上限）；保留最近 2s 的音频
                        synchronized(prebufferLock) {
                            prebuffer.addLast(audioChunk)
                            while (prebuffer.size > maxFrames) prebuffer.removeFirst()
                        }
                    } else {
                        // WS 就绪：先冲刷预缓冲，再发送当前块
                        var flushed: Array<ByteArray>? = null
                        synchronized(prebufferLock) {
                            if (prebuffer.isNotEmpty()) {
                                flushed = prebuffer.toTypedArray()
                                prebuffer.clear()
                            }
                        }
                        flushed?.forEach { b ->
                            try {
                                sendAudioFrame(b, last = false)
                            } catch (t: Throwable) {
                                Log.e(TAG, "Failed to send buffered audio frame", t)
                            }
                        }
                        try {
                            sendAudioFrame(audioChunk, last = false)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to send audio frame", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Audio streaming cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                }
            }
        }
    }

    private fun flushPrebufferAndSendLast() {
        if (!wsReady.get()) return
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
                Log.d(TAG, "Flushing ${flushed!!.size} prebuffered frames")
            }
        }
        flushed?.forEach { b ->
            try {
                sendAudioFrame(b, last = false)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to send prebuffered frame", t)
            }
        }
        if (!audioLastSent.get()) {
            try {
                sendAudioFrame(byteArrayOf(), last = true)
                audioLastSent.set(true)
                Log.d(TAG, "Sent final audio frame marker")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to send final audio frame", t)
            }
        }
    }

    private fun sendAudioFrame(pcm: ByteArray, last: Boolean) {
        val webSocket = ws ?: return
        if (!wsReady.get()) return
        // audio-only client request: raw bytes + gzip
        val payload = gzip(pcm)
        val flags = if (last) FLAG_AUDIO_LAST else 0
        val frame = buildClientFrame(
            messageType = MSG_TYPE_AUDIO_ONLY_CLIENT_REQ,
            flags = flags,
            serialization = SERIALIZE_NONE,
            compression = COMPRESS_GZIP,
            payload = payload
        )
        webSocket.send(ByteString.of(*frame))
    }

    private fun buildFullClientRequestJson(): String {
        val user = JSONObject().apply { put("uid", prefs.appKey) }
        val audio = JSONObject().apply {
            put("format", "pcm")
            put("rate", sampleRate)
            put("bits", 16)
            put("channel", 1)
            val lang = prefs.volcLanguage
            if (lang.isNotBlank()) put("language", lang)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            // 语义顺滑（去口头禅/重复等），由设置控制
            put("enable_ddc", prefs.volcDdcEnabled)
            // 二遍识别（nostream 重识别提升最终准确）
            if (prefs.volcNonstreamEnabled) {
                put("enable_nonstream", true)
            }
            // VAD 判停与分句（按需启用）
            if (prefs.volcVadEnabled) {
                // 输出分句/词级时间（服务端才会返回 utterances/words）
                put("show_utterances", true)
                // 强制静音判停窗口：800ms（官方推荐实时性较好数值，最小200）
                put("end_window_size", 800)
                // 强制语音时间：1000ms（短音频也能尽快产生 definite）
                put("force_to_speech_time", 1000)
                // 说明：配置 end_window_size 后 vad_segment_duration 不生效，这里不再冗余设置
            }

            // Pro功能：动态注入个性化热词和上下文信息
            try {
                val volcContext = com.brycewg.asrkb.asr.ProAsrHelper.buildVolcContext(context)
                if (volcContext != null) {
                    val corpus = JSONObject().apply {
                        put("context", volcContext)
                    }
                    put("corpus", corpus)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to inject Volc context", t)
            }
        }
        return JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }.toString()
    }

    private fun handleServerMessage(bytes: ByteString) {
        val arr = bytes.toByteArray()
        if (arr.size < 8) return // 至少 header(4)+size(4)
        val b0 = arr[0].toInt() and 0xFF
        val b1 = arr[1].toInt() and 0xFF
        val b2 = arr[2].toInt() and 0xFF
        val headerSizeBytes = (b0 and 0x0F) * 4
        val msgType = (b1 ushr 4) and 0x0F
        val flags = b1 and 0x0F
        val serialization = (b2 ushr 4) and 0x0F
        val compression = b2 and 0x0F
        var offset = headerSizeBytes
        if (msgType == MSG_TYPE_FULL_SERVER_RESP) {
            // 跳过 sequence(4)
            if (arr.size < offset + 4) return
            offset += 4
            if (arr.size < offset + 4) return
            val payloadSize = readUInt32BE(arr, offset)
            offset += 4
            if (arr.size < offset + payloadSize) return
            var payload = arr.copyOfRange(offset, offset + payloadSize)
            if (compression == COMPRESS_GZIP) {
                payload = gunzip(payload)
            }
            if (serialization == SERIALIZE_JSON) {
                val text = parseTextFromJson(String(payload, Charsets.UTF_8))
                val isFinal = (flags and FLAG_SERVER_FINAL_MASK) == FLAG_SERVER_FINAL_MASK
                // 停止后仅接受最终结果；录音中允许 partial
                if (!running.get() && !isFinal) return
                if (isFinal) {
                    Log.d(TAG, "Received final result, length: ${text.length}")
                    // 重要：即使服务端最终文本为空也要回调 onFinal("")，
                    // 以便上层（悬浮球/IME）清理 isProcessing 状态并给出友好提示。
                    try { listener.onFinal(text) } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify final result", t)
                    }
                    running.set(false)
                    awaitingFinal.set(false)
                    scope.launch(Dispatchers.IO) {
                        try { ws?.close(1000, "final") } catch (t: Throwable) {
                            Log.e(TAG, "Failed to close WebSocket after final", t)
                        }
                        ws = null
                        wsReady.set(false)
                    }
                } else {
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Received partial result: ${text.take(50)}...")
                        listener.onPartial(text)
                    }
                }
            }
        } else if (msgType == MSG_TYPE_ERROR_SERVER) {
            if (arr.size < offset + 8) return
            val code = readUInt32BE(arr, offset)
            val size = readUInt32BE(arr, offset + 4)
            val start = offset + 8
            val end = (start + size).coerceAtMost(arr.size)
            val msg = try { String(arr.copyOfRange(start, end), Charsets.UTF_8) } catch (t: Throwable) {
                Log.e(TAG, "Failed to decode error message", t)
                ""
            }
            val lowerMsg = msg.lowercase()
            if (code == 45000000 && ("decode ws request failed" in lowerMsg || "unable to decode" in lowerMsg)) {
                Log.w(TAG, "Ignoring known error: $code - $msg")
                return
            }
            Log.e(TAG, "ASR server error: $code - $msg")
            listener.onError("ASR Error $code: $msg")
        }
    }

    private fun parseTextFromJson(json: String): String {
        return try {
            val o = JSONObject(json)
            if (o.has("result")) {
                val r = o.getJSONObject("result")
                r.optString("text", "")
            } else ""
        } catch (_: Throwable) { "" }
    }

    @Suppress("SameParameterValue")
    private fun buildClientFrame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        payload: ByteArray
    ): ByteArray {
        val header = ByteArray(4)
        header[0] = (((PROTOCOL_VERSION and 0x0F) shl 4) or (HEADER_SIZE_UNITS and 0x0F)).toByte()
        header[1] = (((messageType and 0x0F) shl 4) or (flags and 0x0F)).toByte()
        header[2] = (((serialization and 0x0F) shl 4) or (compression and 0x0F)).toByte()
        header[3] = 0
        val size = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
        return header + size + payload
    }

    private fun readUInt32BE(arr: ByteArray, offset: Int): Int {
        return ((arr[offset].toInt() and 0xFF) shl 24) or
            ((arr[offset + 1].toInt() and 0xFF) shl 16) or
            ((arr[offset + 2].toInt() and 0xFF) shl 8) or
            (arr[offset + 3].toInt() and 0xFF)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return try {
            GZIPInputStream(data.inputStream()).readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to gunzip data", t)
            data
        }
    }
}
