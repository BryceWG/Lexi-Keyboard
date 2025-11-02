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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 sherpa-onnx OfflineRecognizer 的"伪流式"本地识别：
 * - 录音中每 200ms 投送一帧 PCM -> Float 到离线流，并动态调整解码间隔。
 * - 动态处理间隔：随录音时长增加而增加，每录音 4 秒增加 200ms（一帧）处理间隔。
 *   例如：0-4s -> 200ms, 4-8s -> 400ms, 8-12s -> 600ms，依此类推。
 * - 停止录音后进行一次最终 decode 并回调 onFinal。
 * - 依赖 LocalModelOnnxBridge 以反射方式调用，避免编译期耦合。
 */
class LocalModelPseudoStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "LocalModelPseudoStreamAsrEngine"
        // 基准帧长：200ms（对应一次完整的音频帧）
        private const val FRAME_MS = 200L
        // 动态调整参数：每经过多少秒增加一帧的处理间隔
        private const val INTERVAL_INCREASE_PER_SECONDS = 4
    }

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val svManager = SenseVoiceOnnxManager.getInstance()
    private var audioJob: Job? = null
    private var currentStream: Any? = null
    private val streamMutex = Mutex()
    private var lastDecodeUptimeMs: Long = 0L
    private var lastEmitUptimeMs: Long = 0L
    // 录音开始时间戳，用于计算动态处理间隔
    private var recordingStartTimeMs: Long = 0L
    // 基准分片时长（ms），固定为 200ms（一帧）
    @Volatile private var chunkMillis: Int = FRAME_MS.toInt()
    private var lastEmittedText: String? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override val isRunning: Boolean
        get() = running.get()

    // 预缓冲：在模型加载期间缓存音频，待流创建后一次性送入
    private val prebufferMutex = Mutex()
    private val prebuffer = ArrayDeque<ByteArray>()
    private var prebufferBytes: Int = 0
    // ~12 秒缓冲：16kHz * 2B * 12s ≈ 384KB
    private val maxPrebufferBytes: Int = 384 * 1024

    private var loadJob: Job? = null
    override fun start() {
        if (running.get()) return
        closing.set(false)
        // 快速权限/依赖校验（主线程上尽快返回）
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }
        if (!svManager.isOnnxAvailable()) {
            listener.onError(context.getString(R.string.error_local_asr_not_ready))
            return
        }
        // 将引擎标记为运行中，以便开始采集和静音判停
        running.set(true)
        // 重置节流窗口与上次文本，确保每次会话"同步起步"。
        recordingStartTimeMs = SystemClock.uptimeMillis()
        lastDecodeUptimeMs = 0L
        lastEmitUptimeMs = 0L
        lastEmittedText = null
        // 启动音频采集（先录先缓冲），并在后台加载模型与创建 stream
        startCapture()

        // 后台加载模型并创建 stream，避免阻塞主线程
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Default) {
            try {
                val base = try { context.getExternalFilesDir(null) } catch (_: Throwable) { null } ?: context.filesDir
                val probeRoot = java.io.File(base, "sensevoice")
                val variant = try { prefs.svModelVariant } catch (_: Throwable) { "small-int8" }
                val variantDir = if (variant == "small-full") java.io.File(probeRoot, "small-full") else java.io.File(probeRoot, "small-int8")
                val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot)
                if (auto == null) {
                    listener.onError(context.getString(R.string.error_sensevoice_model_missing)); return@launch
                }
                val dir = auto.absolutePath
                val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
                val int8File = java.io.File(dir, "model.int8.onnx")
                val f32File = java.io.File(dir, "model.onnx")
                val modelFile = when {
                    int8File.exists() -> int8File
                    f32File.exists() -> f32File
                    else -> null
                }
                val modelPath = modelFile?.absolutePath
                val minBytes = 8L * 1024L * 1024L
                if (modelPath == null || !java.io.File(tokensPath).exists() || (modelFile?.length() ?: 0L) < minBytes) {
                    listener.onError(context.getString(R.string.error_sensevoice_model_missing)); return@launch
                }

                val keepMinutes = try { prefs.svKeepAliveMinutes } catch (_: Throwable) { -1 }
                val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
                val alwaysKeep = keepMinutes < 0

                Log.d(TAG, "Preparing SenseVoice model at $modelPath")
                val ok = svManager.prepare(
                    assetManager = null,
                    tokens = tokensPath,
                    model = modelPath,
                    language = try { prefs.svLanguage } catch (_: Throwable) { "auto" },
                    useItn = try { prefs.svUseItn } catch (_: Throwable) { false },
                    provider = "cpu",
                    numThreads = try { prefs.svNumThreads } catch (_: Throwable) { 2 },
                    keepAliveMs = keepMs,
                    alwaysKeep = alwaysKeep,
                    onLoadStart = { try { (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)?.onLocalModelLoadStart() } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify load start", t)
                    } },
                    onLoadDone = { try { (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)?.onLocalModelLoadDone() } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify load done", t)
                    } },
                )
                if (!ok) {
                    Log.w(TAG, "Model preparation failed or cancelled")
                    return@launch
                }

                Log.d(TAG, "Creating recognition stream")
                val stream = svManager.createStreamOrNull()
                if (stream == null) {
                    listener.onError(context.getString(R.string.error_local_asr_not_ready))
                    return@launch
                }
                currentStream = stream
                Log.d(TAG, "Draining prebuffer and entering streaming mode")
                // 先把预缓冲送入
                drainPrebufferTo(stream)

                if (closing.get()) {
                    // 用户已停止：做一次最终解码并收尾
                    try {
                        val finalText = streamMutex.withLock { svManager.decodeAndGetText(stream) }
                        Log.d(TAG, "Final(result-after-late-load) length: ${finalText?.length ?: 0}")
                        try {
                            listener.onFinal(finalText?.trim() ?: "")
                        } catch (notifyError: Throwable) {
                            Log.e(TAG, "Failed to notify final after late load", notifyError)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to decode final after late load", t)
                        try { listener.onFinal("") } catch (notifyError: Throwable) {
                            Log.e(TAG, "Failed to notify final after late load", notifyError)
                        }
                    } finally {
                        try { streamMutex.withLock { svManager.releaseStream(stream) } } catch (t: Throwable) {
                            Log.e(TAG, "Failed to release stream after late load", t)
                        }
                        currentStream = null
                        closing.set(false)
                        running.set(false)
                    }
                } else {
                    // 继续采集：后续音频直接走实时 deliverChunk()
                    running.set(true)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start engine: ${t.message}", t)
                try {
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                } catch (notifyError: Throwable) {
                    Log.e(TAG, "Failed to notify error", notifyError)
                }
            }
        }
    }

    override fun stop() {
        if (!running.get() && currentStream == null) {
            // 未进入 running 但可能仍在加载/预缓冲，标记关闭并等待加载完成后冲刷
            closing.set(true)
            audioJob?.cancel()
            audioJob = null
            return
        }
        if (!running.get()) return
        Log.d(TAG, "Stopping engine")
        closing.set(true)
        running.set(false)
        audioJob?.cancel()
        audioJob = null
        val s = currentStream
        if (s != null) {
            // 在后台协程中完成最终解码与资源释放，避免在主线程阻塞
            scope.launch(Dispatchers.Default) {
                try {
                    val finalText = streamMutex.withLock { svManager.decodeAndGetText(s) }
                    Log.d(TAG, "Final result length: ${finalText?.length ?: 0}")
                    try {
                        if (!finalText.isNullOrBlank()) {
                            listener.onFinal(finalText.trim())
                        } else {
                            listener.onFinal("")
                        }
                    } catch (notifyError: Throwable) {
                        Log.e(TAG, "Failed to notify final result", notifyError)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to get final result", t)
                    try { listener.onFinal("") } catch (_: Throwable) { /* ignore */ }
                } finally {
                    try {
                        streamMutex.withLock { svManager.releaseStream(s) }
                        Log.d(TAG, "Released recognition stream")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to release stream", t)
                    }
                    currentStream = null
                    closing.set(false)
                }
            }
        }
    }

    /**
     * 启动音频采集。若流未创建则先进入预缓冲；创建后直接投送 + 解码。
     */
    private fun startCapture() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            // 分片时长：固定为 200ms（一帧）
            val targetChunkMs = chunkMillis
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = targetChunkMs
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

            try {
                Log.d(TAG, "Starting audio capture with chunk=${targetChunkMs}ms (dynamic interval adjustment enabled)")
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get() && currentStream == null) return@collect

                    // VAD 自动判停
                    if (vadDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        try { listener.onStopped() } catch (t: Throwable) {
                            Log.e(TAG, "Failed to notify stopped", t)
                        }
                        stop()
                        return@collect
                    }

                    // 若流已就绪则直接投送，否则进入预缓冲
                    val s = currentStream
                    if (s != null) {
                        deliverChunk(s, audioChunk, audioChunk.size)
                    } else {
                        appendPrebuffer(audioChunk)
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

    private suspend fun deliverChunk(stream: Any, bytes: ByteArray, len: Int) {
        // 在运行中或处于收尾阶段(closing)均可投送；仅在二者皆不满足时返回
        if (!running.get() && !closing.get()) return
        if (currentStream !== stream) return
        val floats = pcmToFloatArray(bytes, len)
        if (floats.isEmpty()) return
        val now = SystemClock.uptimeMillis()

        // 动态计算处理间隔：随录音时长增加而增加
        // 算法：每经过 INTERVAL_INCREASE_PER_SECONDS 秒，增加 FRAME_MS 的处理间隔
        val elapsedMs = now - recordingStartTimeMs
        val elapsedSeconds = elapsedMs / 1000
        val additionalFrames = elapsedSeconds / INTERVAL_INCREASE_PER_SECONDS
        val currentDecodeInterval = FRAME_MS * (1 + additionalFrames)
        val currentEmitInterval = FRAME_MS  // 发射间隔保持为一帧

        var text: String? = null
        try {
            streamMutex.withLock {
                if (!running.get() || closing.get()) return
                if (currentStream !== stream) return
                try {
                    svManager.acceptWaveform(stream, floats, sampleRate)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to accept waveform", t)
                }
                val shouldDecode = closing.get() || (now - lastDecodeUptimeMs) >= currentDecodeInterval
                if (shouldDecode) {
                    text = try {
                        svManager.decodeAndGetText(stream)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to decode text", t)
                        null
                    }
                    lastDecodeUptimeMs = now
                    // 打印动态间隔调试信息
                    if (elapsedSeconds % 4 == 0L && elapsedSeconds > 0L) {
                        Log.d(TAG, "Dynamic interval adjusted: elapsed=${elapsedSeconds}s, decodeInterval=${currentDecodeInterval}ms")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in deliverChunk", t)
        }
        if (!text.isNullOrBlank() && running.get() && !closing.get()) {
            val trimmed = text!!.trim()
            val needEmit = (now - lastEmitUptimeMs) >= currentEmitInterval && trimmed != lastEmittedText
            if (needEmit) {
                try {
                    listener.onPartial(trimmed)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to notify partial result", t)
                }
                lastEmitUptimeMs = now
                lastEmittedText = trimmed
            }
        }
    }

    private suspend fun appendPrebuffer(bytes: ByteArray) {
        prebufferMutex.withLock {
            // 控制内存上限：超限则丢弃最旧的块
            if (bytes.isEmpty()) return@withLock
            while (prebufferBytes + bytes.size > maxPrebufferBytes && prebuffer.isNotEmpty()) {
                val removed = prebuffer.removeFirst()
                prebufferBytes -= removed.size
            }
            prebuffer.addLast(bytes.copyOf())
            prebufferBytes += bytes.size
        }
    }

    private suspend fun drainPrebufferTo(stream: Any) {
        val drainList = mutableListOf<ByteArray>()
        prebufferMutex.withLock {
            if (prebuffer.isEmpty()) return
            drainList.addAll(prebuffer)
            prebuffer.clear()
            prebufferBytes = 0
        }
        for (chunk in drainList) {
            deliverChunk(stream, chunk, chunk.size)
        }
    }

    private fun pcmToFloatArray(src: ByteArray, len: Int): FloatArray {
        if (len <= 1) return FloatArray(0)
        val n = len / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(src, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            val s = bb.short.toInt()
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f
            i++
        }
        return out
    }
}
