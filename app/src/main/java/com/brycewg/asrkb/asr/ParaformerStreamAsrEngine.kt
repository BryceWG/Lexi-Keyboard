package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 sherpa-onnx OnlineRecognizer 的本地 Paraformer 流式识别引擎。
 * - 反射调用 sherpa-onnx Kotlin API，避免编译期强耦合。
 * - 录音分片（默认200ms），送入在线流；每次分片后尽可能 decode，并节流发送 partial。
 * - 停止时写入尾部静音 + inputFinished + 完整 decode 输出最终结果。
 */
class ParaformerStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "ParaformerStreamAsrEngine"
        private const val FRAME_MS = 200
    }

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private var audioJob: Job? = null
    private val mgr = ParaformerOnnxManager.getInstance()
    private var currentStream: Any? = null
    private val streamMutex = Mutex()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 预缓冲：在模型加载/流未创建前缓存音频，避免首字延迟过长
    private val prebufferMutex = Mutex()
    private val prebuffer = ArrayDeque<ByteArray>()
    private var prebufferBytes: Int = 0
    private val maxPrebufferBytes: Int = 384 * 1024 // ~12s @16kHz s16le mono

    private var lastEmitUptimeMs: Long = 0L
    private var lastEmittedText: String? = null

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        closing.set(false)
        // 权限校验
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }

        if (!mgr.isOnnxAvailable()) {
            listener.onError(context.getString(R.string.error_local_asr_not_ready))
            return
        }

        running.set(true)
        lastEmitUptimeMs = 0L
        lastEmittedText = null

        // 启动采集；后台准备模型与在线识别器
        startCapture()
        scope.launch(Dispatchers.Default) {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val root = java.io.File(base, "paraformer")
            val isTri = prefs.pfModelVariant.startsWith("trilingual")
            val group = if (isTri) java.io.File(root, "trilingual") else java.io.File(root, "bilingual")
            val dir = findPfModelDir(group)
            if (dir == null) {
                listener.onError(context.getString(R.string.error_paraformer_model_missing))
                running.set(false)
                return@launch
            }

            val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
            val int8 = prefs.pfModelVariant.contains("int8")
            val enc = if (int8) java.io.File(dir, "encoder.int8.onnx") else java.io.File(dir, "encoder.onnx")
            val dec = if (int8) java.io.File(dir, "decoder.int8.onnx") else java.io.File(dir, "decoder.onnx")
            if (!enc.exists() || !dec.exists()) {
                listener.onError(context.getString(R.string.error_paraformer_model_missing))
                running.set(false)
                return@launch
            }

            val keepMinutes = prefs.pfKeepAliveMinutes
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val ruleFsts = try { if (prefs.pfUseItn) ItnAssets.ensureItnFstPath(context) else null } catch (_: Throwable) { null }
            val ok = mgr.prepare(
                tokens = tokensPath,
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                ruleFsts = ruleFsts,
                numThreads = prefs.pfNumThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = { notifyLoadUi(true) },
                onLoadDone = { notifyLoadUi(false) },
            )
            if (!ok) {
                Log.w(TAG, "Paraformer prepare() failed")
                return@launch
            }

            val stream = mgr.createStreamOrNull()
            if (stream == null) {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
                return@launch
            }
            currentStream = stream

            // 冲刷预缓冲
            drainPrebufferTo(stream)

            // 若在准备期间已调用 stop()，此处直接做最终解码
            if (closing.get()) {
                finalizeAndEmit(stream)
                mgr.releaseStream(stream)
                currentStream = null
                closing.set(false)
                running.set(false)
            }
        }
    }

    override fun stop() {
        if (!running.get() && currentStream == null) {
            // 尚在加载阶段：仅标记关闭并停止采集
            closing.set(true)
            audioJob?.cancel()
            audioJob = null
            return
        }
        if (!running.get()) return
        running.set(false)
        closing.set(true)
        audioJob?.cancel()
        audioJob = null

        val s = currentStream
        if (s != null) {
            scope.launch(Dispatchers.Default) {
                try { finalizeAndEmit(s) } catch (t: Throwable) { Log.e(TAG, "finalizeAndEmit failed", t) }
                try { mgr.releaseStream(s) } catch (t: Throwable) { Log.e(TAG, "releaseStream failed", t) }
                currentStream = null
                closing.set(false)
            }
        }
    }

    private fun notifyLoadUi(start: Boolean) {
        val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi) ?: return
        if (start) ui.onLocalModelLoadStart() else ui.onLocalModelLoadDone()
    }

    private fun startCapture() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = FRAME_MS
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

            val vadDetector = if (isVadAutoStopEnabled(context, prefs))
                VadDetector(context, sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            else null

            try {
                Log.d(TAG, "Starting audio capture for Paraformer with chunk=${chunkMillis}ms")
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get() && currentStream == null) return@collect

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
                        try { listener.onStopped() } catch (t: Throwable) { Log.e(TAG, "Failed to notify stopped", t) }
                        stop()
                        return@collect
                    }

                    val s = currentStream
                    if (s == null) {
                        appendPrebuffer(audioChunk)
                    } else {
                        deliverChunk(s, audioChunk, audioChunk.size)
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
        if (!running.get() && !closing.get()) return
        if (currentStream !== stream) return
        val floats = pcmToFloatArray(bytes, len)
        if (floats.isEmpty()) return

        var partial: String? = null
        streamMutex.withLock {
            if (currentStream !== stream) return
            mgr.acceptWaveform(stream, floats, sampleRate)
            var loops = 0
            while (mgr.isReady(stream) && loops < 8) {
                mgr.decode(stream)
                loops++
            }
            partial = mgr.getResultText(stream)
        }

        // 节流发送 partial
        val now = SystemClock.uptimeMillis()
        if (!partial.isNullOrBlank() && running.get() && !closing.get()) {
            val trimmed = partial!!.trim()
            val needEmit = (now - lastEmitUptimeMs) >= FRAME_MS && trimmed != lastEmittedText
            if (needEmit) {
                try { listener.onPartial(trimmed) } catch (t: Throwable) { Log.e(TAG, "notify partial failed", t) }
                lastEmitUptimeMs = now
                lastEmittedText = trimmed
            }
        }
    }

    private suspend fun finalizeAndEmit(stream: Any) {
        try {
            // 尾部静音 + 完成标记
            val tailSamples = ((sampleRate * 0.6).toInt()).coerceAtLeast(1)
            val tail = FloatArray(tailSamples)
            mgr.acceptWaveform(stream, tail, sampleRate)
            mgr.inputFinished(stream)

            // 解码直到不再就绪
            var loops = 0
            while (mgr.isReady(stream) && loops < 64) {
                mgr.decode(stream)
                loops++
            }

            val text = mgr.getResultText(stream)
            listener.onFinal(text?.trim().orEmpty())
        } catch (t: Throwable) {
            Log.e(TAG, "finalizeAndEmit outer failed", t)
            try { listener.onFinal("") } catch (_: Throwable) { }
        }
    }

    private suspend fun appendPrebuffer(bytes: ByteArray) {
        prebufferMutex.withLock {
            if (bytes.isEmpty()) return@withLock
            while (prebufferBytes + bytes.size > maxPrebufferBytes && prebuffer.isNotEmpty()) {
                val rm = prebuffer.removeFirst()
                prebufferBytes -= rm.size
            }
            prebuffer.addLast(bytes.copyOf())
            prebufferBytes += bytes.size
        }
    }

    private suspend fun drainPrebufferTo(stream: Any) {
        val list = mutableListOf<ByteArray>()
        prebufferMutex.withLock {
            if (prebuffer.isEmpty()) return
            list.addAll(prebuffer)
            prebuffer.clear()
            prebufferBytes = 0
        }
        for (b in list) {
            deliverChunk(stream, b, b.size)
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

/**
 * 查找 Paraformer 模型目录：在 root 下寻找包含 tokens.txt 且存在 encoder/decoder(onxx/int8.onnx) 的目录。
 */
fun findPfModelDir(root: java.io.File?): java.io.File? {
    if (root == null || !root.exists()) return null
    fun validDir(d: java.io.File): Boolean {
        if (!d.exists() || !d.isDirectory) return false
        val tokens = java.io.File(d, "tokens.txt")
        if (!tokens.exists()) return false
        val e1 = java.io.File(d, "encoder.onnx")
        val d1 = java.io.File(d, "decoder.onnx")
        val e2 = java.io.File(d, "encoder.int8.onnx")
        val d2 = java.io.File(d, "decoder.int8.onnx")
        return (e1.exists() && d1.exists()) || (e2.exists() && d2.exists())
    }
    if (validDir(root)) return root
    val subs = root.listFiles() ?: return null
    subs.forEach { f -> if (f.isDirectory && validDir(f)) return f }
    return null
}

/**
 * 释放 Paraformer 识别器（供设置页或切换供应商时手工卸载）
 */
fun unloadParaformerRecognizer() {
    try { ParaformerOnnxManager.getInstance().unload() } catch (t: Throwable) {
        Log.e("ParaformerStreamAsrEngine", "Failed to unload paraformer recognizer", t)
    }
}

// 判断是否已有缓存的本地 Paraformer 识别器（已加载模型）
fun isParaformerPrepared(): Boolean {
    return try {
        ParaformerOnnxManager.getInstance().isPrepared()
    } catch (t: Throwable) {
        Log.e("ParaformerStreamAsrEngine", "Failed to check paraformer prepared", t)
        false
    }
}

// ===== 反射式在线识别管理器 =====

private class ReflectiveOnlineStream(val instance: Any) {
    private val cls = instance.javaClass

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        try {
            cls.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, samples, sampleRate)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "acceptWaveform reflection failed", t)
        }
    }

    fun inputFinished() {
        try { cls.getMethod("inputFinished").invoke(instance) } catch (t: Throwable) {
            Log.e("ROnlineStream", "inputFinished failed", t)
        }
    }

    fun release() {
        try { cls.getMethod("release").invoke(instance) } catch (t: Throwable) {
            Log.e("ROnlineStream", "release failed", t)
        }
    }
}

private class ReflectiveOnlineRecognizer(private val instance: Any, private val cls: Class<*>) {
    fun createStream(): ReflectiveOnlineStream {
        val s = cls.getMethod("createStream", String::class.java).invoke(instance, "")
        return ReflectiveOnlineStream(s)
    }

    fun isReady(stream: ReflectiveOnlineStream): Boolean {
        return cls.getMethod("isReady", stream.instance.javaClass)
            .invoke(instance, stream.instance) as Boolean
    }

    fun decode(stream: ReflectiveOnlineStream) {
        cls.getMethod("decode", stream.instance.javaClass)
            .invoke(instance, stream.instance)
    }

    fun getResultText(stream: ReflectiveOnlineStream): String? {
        val res = cls.getMethod("getResult", stream.instance.javaClass)
            .invoke(instance, stream.instance)
        return try {
            res.javaClass.getMethod("getText").invoke(res) as? String
        } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "getResultText getter not found", t)
            null
        }
    }

    fun release() {
        try { cls.getMethod("release").invoke(instance) } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "release failed", t)
        }
    }
}

class ParaformerOnnxManager private constructor() {
    companion object {
        private const val TAG = "ParaformerOnnxManager"

        @Volatile private var instance: ParaformerOnnxManager? = null
        fun getInstance(): ParaformerOnnxManager = instance ?: synchronized(this) {
            instance ?: ParaformerOnnxManager().also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()

    @Volatile private var cachedConfig: RecognizerConfig? = null
    @Volatile private var cachedRecognizer: ReflectiveOnlineRecognizer? = null
    @Volatile private var clsOnlineRecognizer: Class<*>? = null
    @Volatile private var clsOnlineRecognizerConfig: Class<*>? = null
    @Volatile private var clsOnlineModelConfig: Class<*>? = null
    @Volatile private var clsOnlineParaformerModelConfig: Class<*>? = null
    @Volatile private var clsFeatureConfig: Class<*>? = null
    @Volatile private var unloadJob: Job? = null

    // 最近一次配置与流计数：用于保留/卸载
    @Volatile private var lastKeepAliveMs: Long = 0L
    @Volatile private var lastAlwaysKeep: Boolean = false
    @Volatile private var activeStreams: Int = 0

    fun isOnnxAvailable(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer"); true
        } catch (t: Throwable) {
            Log.d(TAG, "sherpa-onnx online not available", t)
            false
        }
    }

    fun unload() {
        scope.launch {
            mutex.withLock {
                cachedRecognizer?.release()
                cachedRecognizer = null
                cachedConfig = null
            }
        }
    }

    fun isPrepared(): Boolean = cachedRecognizer != null

    private fun scheduleAutoUnload(keepAliveMs: Long, alwaysKeep: Boolean) {
        unloadJob?.cancel()
        if (alwaysKeep) return
        if (keepAliveMs <= 0L) { unload(); return }
        unloadJob = scope.launch {
            delay(keepAliveMs)
            unload()
        }
    }

    private fun initClasses() {
        if (clsOnlineRecognizer == null) {
            clsOnlineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            clsOnlineRecognizerConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
            clsOnlineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
            clsOnlineParaformerModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig")
            clsFeatureConfig = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            Log.d(TAG, "Initialized reflection classes for online recognizer")
        }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean {
        return try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
            true
        } catch (t: Throwable) {
            try {
                val m = target.javaClass.getMethod(
                    "set" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    value?.javaClass ?: Any::class.java
                )
                m.invoke(target, value)
                true
            } catch (t2: Throwable) {
                Log.w(TAG, "Failed to set field '$name'", t2)
                false
            }
        }
    }

    private data class RecognizerConfig(
        val tokens: String,
        val encoder: String,
        val decoder: String,
        val ruleFsts: String?,
        val numThreads: Int,
        val provider: String = "cpu",
        val sampleRate: Int = 16000,
        val featureDim: Int = 80,
        val debug: Boolean = false
    ) {
        fun toCacheKey(): String = listOf(tokens, encoder, decoder, ruleFsts.orEmpty(), numThreads, provider, sampleRate, featureDim, debug).joinToString("|")
    }

    private fun buildModelConfig(tokens: String, encoder: String, decoder: String, numThreads: Int, provider: String, debug: Boolean): Any {
        val para = clsOnlineParaformerModelConfig!!.getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance(encoder, decoder)
        val model = clsOnlineModelConfig!!.getDeclaredConstructor().newInstance()
        // OnlineModelConfig: tokens/numThreads/provider/debug/paraformer
        trySetField(model, "tokens", tokens)
        trySetField(model, "numThreads", numThreads)
        trySetField(model, "provider", provider)
        trySetField(model, "debug", debug)
        trySetField(model, "paraformer", para)
        return model
    }

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        return feat
    }

    private fun buildRecognizerConfig(config: RecognizerConfig): Any {
        val model = buildModelConfig(config.tokens, config.encoder, config.decoder, config.numThreads, config.provider, config.debug)
        val feat = buildFeatureConfig(config.sampleRate, config.featureDim)
        val rec = clsOnlineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        // OnlineRecognizerConfig: modelConfig/featConfig/decodingMethod/enableEndpoint/maxActivePaths...
        trySetField(rec, "modelConfig", model)
        trySetField(rec, "featConfig", feat)
        trySetField(rec, "decodingMethod", "greedy_search")
        trySetField(rec, "enableEndpoint", true)
        trySetField(rec, "maxActivePaths", 4)
        // ITN：若给定 ruleFsts 路径，则直接设置。资产文件已由调用方拷贝到 files/itn。
        if (!config.ruleFsts.isNullOrBlank()) {
            trySetField(rec, "ruleFsts", config.ruleFsts)
        }
        return rec
    }

    private fun createRecognizer(recConfig: Any): Any {
        val ctor = clsOnlineRecognizer!!.getDeclaredConstructor(
            android.content.res.AssetManager::class.java,
            clsOnlineRecognizerConfig
        )
        return ctor.newInstance(null, recConfig)
    }

    suspend fun prepare(
        tokens: String,
        encoder: String,
        decoder: String,
        ruleFsts: String?,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null,
    ): Boolean = mutex.withLock {
        try {
            initClasses()
            val config = RecognizerConfig(tokens, encoder, decoder, ruleFsts, numThreads)
            val same = (cachedConfig == config)
            if (!same || cachedRecognizer == null) {
                try { onLoadStart?.invoke() } catch (t: Throwable) { Log.e(TAG, "onLoadStart failed", t) }
                val recConfig = buildRecognizerConfig(config)
                val inst = createRecognizer(recConfig)
                cachedRecognizer?.release()
                cachedRecognizer = ReflectiveOnlineRecognizer(inst, clsOnlineRecognizer!!)
                cachedConfig = config
                try { onLoadDone?.invoke() } catch (t: Throwable) { Log.e(TAG, "onLoadDone failed", t) }
            }
            lastKeepAliveMs = keepAliveMs
            lastAlwaysKeep = alwaysKeep
            true
        } catch (t: Throwable) {
            Log.e(TAG, "prepare failed", t); false
        }
    }

    suspend fun createStreamOrNull(): Any? = mutex.withLock {
        try {
            val r = cachedRecognizer ?: return@withLock null
            val s = r.createStream()
            activeStreams++
            s
        } catch (t: Throwable) {
            Log.e(TAG, "createStream failed", t); null
        }
    }

    fun acceptWaveform(stream: Any, samples: FloatArray, sampleRate: Int) {
        try { if (stream is ReflectiveOnlineStream) stream.acceptWaveform(samples, sampleRate) } catch (t: Throwable) {
            Log.e(TAG, "acceptWaveform failed", t)
        }
    }

    fun inputFinished(stream: Any) {
        try { if (stream is ReflectiveOnlineStream) stream.inputFinished() } catch (t: Throwable) {
            Log.e(TAG, "inputFinished failed", t)
        }
    }

    fun isReady(stream: Any): Boolean {
        return try {
            val r = cachedRecognizer ?: return false
            if (stream is ReflectiveOnlineStream) r.isReady(stream) else false
        } catch (t: Throwable) {
            Log.e(TAG, "isReady failed", t); false
        }
    }

    fun decode(stream: Any) {
        try {
            val r = cachedRecognizer ?: return
            if (stream is ReflectiveOnlineStream) r.decode(stream)
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
        }
    }

    fun getResultText(stream: Any): String? {
        return try {
            val r = cachedRecognizer ?: return null
            if (stream is ReflectiveOnlineStream) r.getResultText(stream) else null
        } catch (t: Throwable) {
            Log.e(TAG, "getResultText failed", t); null
        }
    }

    fun releaseStream(stream: Any?) {
        if (stream == null) return
        try {
            if (stream is ReflectiveOnlineStream) stream.release()
            if (activeStreams > 0) activeStreams--
            scheduleUnloadIfIdle()
        } catch (t: Throwable) {
            Log.e(TAG, "releaseStream failed", t)
        }
    }

    fun scheduleUnloadIfIdle() {
        if (activeStreams <= 0) {
            scheduleAutoUnload(lastKeepAliveMs, lastAlwaysKeep)
        }
    }
}

// 预加载：根据当前配置尝试构建本地 Paraformer 在线识别器，降低首次点击等待
fun preloadParaformerIfConfigured(
    context: android.content.Context,
    prefs: com.brycewg.asrkb.store.Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        val manager = ParaformerOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) return

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = java.io.File(base, "paraformer")
        val isTri = prefs.pfModelVariant.startsWith("trilingual")
        val group = if (isTri) java.io.File(root, "trilingual") else java.io.File(root, "bilingual")
        val dir = findPfModelDir(group) ?: return
        val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
        val int8 = prefs.pfModelVariant.contains("int8")
        val enc = if (int8) java.io.File(dir, "encoder.int8.onnx") else java.io.File(dir, "encoder.onnx")
        val dec = if (int8) java.io.File(dir, "decoder.int8.onnx") else java.io.File(dir, "decoder.onnx")
        if (!enc.exists() || !dec.exists()) return

        val keepMinutes = prefs.pfKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val t0 = try { android.os.SystemClock.uptimeMillis() } catch (_: Throwable) { 0L }
            val ruleFsts = try { if (prefs.pfUseItn) ItnAssets.ensureItnFstPath(context) else null } catch (_: Throwable) { null }
            val ok = manager.prepare(
                tokens = tokensPath,
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                ruleFsts = ruleFsts,
                numThreads = prefs.pfNumThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    try { onLoadStart?.invoke() } catch (t: Throwable) { Log.e("ParaformerPreload", "onLoadStart failed", t) }
                    if (!suppressToastOnStart) {
                        try {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try { android.widget.Toast.makeText(context, context.getString(com.brycewg.asrkb.R.string.pf_loading_model), android.widget.Toast.LENGTH_SHORT).show() } catch (t: Throwable) {
                                    Log.e("ParaformerPreload", "Show toast failed", t)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e("ParaformerPreload", "Post toast failed", t)
                        }
                    }
                },
                onLoadDone = onLoadDone,
            )
            if (ok && !forImmediateUse) {
                // 加载完成后显示 Toast，并报告加载用时
                val dt = try {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (t0 > 0L && now >= t0) now - t0 else -1L
                } catch (_: Throwable) { -1L }
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            val text = if (dt > 0) {
                                context.getString(com.brycewg.asrkb.R.string.sv_model_ready_with_ms, dt)
                            } else context.getString(com.brycewg.asrkb.R.string.sv_model_ready)
                            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Log.e("ParaformerPreload", "Show load-done toast failed", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("ParaformerPreload", "Post load-done toast failed", t)
                }
                try { manager.scheduleUnloadIfIdle() } catch (t: Throwable) {
                    Log.e("ParaformerPreload", "scheduleUnloadIfIdle failed", t)
                }
            }
        }
    } catch (t: Throwable) {
        Log.e("ParaformerPreload", "preloadParaformerIfConfigured failed", t)
    }
}
