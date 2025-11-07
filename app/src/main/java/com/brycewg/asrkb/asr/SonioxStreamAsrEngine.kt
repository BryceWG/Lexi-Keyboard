package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque

/**
 * Soniox WebSocket 实时 ASR 引擎实现。
 * 连接后首包发送 JSON 配置（含 api_key + 音频参数），随后流式发送 PCM 二进制帧；
 * 服务端返回 tokens 队列，已最终的 tokens 追加缓存，非最终 tokens 用于增量预览。
 */
class SonioxStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "SonioxStreamAsrEngine"
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private var audioJob: Job? = null
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val finalTextBuffer = StringBuilder()

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
        if (prefs.sonioxApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_soniox_key))
            return
        }
        running.set(true)
        wsReady.set(false)
        synchronized(prebufferLock) { prebuffer.clear() }
        openWebSocketAndStartAudio()
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        wsReady.set(false)
        synchronized(prebufferLock) { prebuffer.clear() }
        try { ws?.send("") } catch (_: Throwable) { }
        scope.launch(Dispatchers.IO) {
            delay(150)
            try { ws?.close(1000, "stop") } catch (_: Throwable) { }
            ws = null
        }
        audioJob?.cancel()
        audioJob = null
    }

    private fun openWebSocketAndStartAudio() {
        finalTextBuffer.setLength(0)
        // 并行：先启动录音进行预采集，后建立 WS 连接
        startCaptureAndSendAudio()
        val req = Request.Builder()
            .url(Prefs.SONIOX_WS_URL)
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened, sending config")
                try {
                    // 发送配置
                    val config = buildConfigJson()
                    webSocket.send(config)
                    // 标记 WS 已就绪，录音线程将冲刷预缓冲并进入实时发送
                    wsReady.set(true)
                    Log.d(TAG, "WebSocket ready, audio streaming can begin")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to send config", t)
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                    stop()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Soniox 文档为文本 JSON 响应；此处保底兼容
                try { handleMessage(bytes.utf8()) } catch (t: Throwable) {
                    Log.e(TAG, "Failed to decode binary message", t)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failed: ${t.message}", t)
                if (running.get()) {
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed with code $code: $reason")
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
    private fun startCaptureAndSendAudio() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = 200
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

            val maxFrames = (2000 / chunkMillis).coerceAtLeast(1) // 预缓冲≈2s

            try {
                Log.d(TAG, "Starting audio capture with chunk size ${chunkMillis}ms")
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get()) return@collect

                    // Calculate and send audio amplitude (for waveform animation)
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

                    if (!wsReady.get()) {
                        // WS 未就绪：写入预缓冲
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
                        val socket = ws
                        if (socket != null) {
                            flushed?.forEach { b ->
                                try {
                                    socket.send(ByteString.of(*b))
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Failed to send buffered audio frame", t)
                                }
                            }
                            try {
                                socket.send(ByteString.of(*audioChunk))
                            } catch (t: Throwable) {
                                Log.e(TAG, "Failed to send audio frame", t)
                            }
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

    private fun buildConfigJson(): String {
        val o = JSONObject().apply {
            put("api_key", prefs.sonioxApiKey)
            put("model", "stt-rt-v3")
            // 原始 PCM；若改为 auto，可不传采样率/通道
            put("audio_format", "pcm_s16le")
            put("sample_rate", sampleRate)
            put("num_channels", 1)
            // 辅助能力（尽量低延迟）
            put("enable_endpoint_detection", true)
            put("enable_language_identification", true)
            // 若选择了识别语言，作为 language_hints 提示（显著提升准确率）
            val langs = prefs.getSonioxLanguages()
            if (langs.isNotEmpty()) {
                val arr = org.json.JSONArray()
                langs.forEach { arr.put(it) }
                put("language_hints", arr)
            }
            // put("enable_speaker_diarization", true) // 如需说话人区分
            // Pro 功能：注入个性化上下文（text）与热词（terms）
            try {
                val proCtx = com.brycewg.asrkb.asr.ProAsrHelper.buildSonioxContext(context)
                if (proCtx != null) put("context", proCtx)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to inject Soniox context", t)
            }
        }
        return o.toString()
    }

    private fun handleMessage(json: String) {
        try {
            val o = JSONObject(json)
            // 错误响应
            if (o.has("error_code")) {
                val code = o.optInt("error_code")
                val msg = o.optString("error_message")
                Log.e(TAG, "ASR server error: $code - $msg")
                listener.onError("ASR Error $code: $msg")
                return
            }

            val tokens = o.optJSONArray("tokens")
            val nonFinal = StringBuilder()
            val msgFinal = StringBuilder()
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val t = tokens.optJSONObject(i) ?: continue
                    val raw = t.optString("text")
                    // 过滤 Soniox 示例/调试中可能出现的结束标记 "<end>"
                    if (raw.trim() == "<end>") continue
                    val text = raw
                    if (text.isEmpty()) continue
                    val isFinal = t.optBoolean("is_final", false)
                    if (isFinal) {
                        // 收集本条消息中的稳定 tokens，稍后整体与历史做去重合并
                        msgFinal.append(text)
                    } else {
                        nonFinal.append(text)
                    }
                }
            }
            // 将本条消息中的稳定片段按边界去重后合入历史稳定缓冲区
            if (msgFinal.isNotEmpty()) {
                val merged = mergeWithOverlapDedup(finalTextBuffer.toString(), msgFinal.toString())
                // 仅追加新增的部分，避免重复
                if (merged.length > finalTextBuffer.length) {
                    finalTextBuffer.setLength(0)
                    finalTextBuffer.append(merged)
                }
            }

            // 预览字符串 = 稳定文本 + 非稳定文本；为防止服务端的非稳定片段以稳定片段的尾部作为上下文前缀而导致的重复，
            // 在边界处做一次前后缀重叠去重：去掉 nonFinal 与 final 尾部的最长公共前后缀重叠。
            val stable = stripEndMarker(finalTextBuffer.toString())
            val preview = if (nonFinal.isNotEmpty()) {
                stripEndMarker(mergeWithOverlapDedup(stable, nonFinal.toString()))
            } else stable
            // 仅在会话运行中才发出中间预览；用户 stop() 后可能仍有零星 non-final 到达，需忽略以避免重复追加
            if (preview.isNotEmpty() && running.get()) listener.onPartial(preview)

            val finished = o.optBoolean("finished", false)
            if (finished) {
                val finalText = stripEndMarker(finalTextBuffer.toString().ifBlank { preview })
                Log.d(TAG, "Received final result, length: ${finalText.length}")
                // 即使最终文本为空也通知上层，避免 UI 卡在处理中态
                try { listener.onFinal(finalText) } catch (t: Throwable) {
                    Log.e(TAG, "Failed to notify final result", t)
                }
                running.set(false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse message: $json", t)
        }
    }

    /**
     * 将稳定文本 stable 与非稳定文本 tail 合并，移除二者边界处的重复前后缀重叠。
     * 例如：stable = "你好世界。", tail = "世界。我们来了" => "你好世界。我们来了"。
     */
    private fun mergeWithOverlapDedup(stable: String, tail: String): String {
        if (stable.isEmpty()) return tail
        if (tail.isEmpty()) return stable
        val max = minOf(stable.length, tail.length)
        var k = max
        while (k > 0) {
            if (stable.regionMatches(stable.length - k, tail, 0, k)) {
                return stable + tail.substring(k)
            }
            k--
        }
        return stable + tail
    }

    /**
     * 去除文本中的 "<end>" 标记（若存在），并裁剪结尾空白。
     */
    private fun stripEndMarker(s: String): String {
        if (s.isEmpty()) return s
        return s.replace("<end>", "").trimEnd()
    }

}
