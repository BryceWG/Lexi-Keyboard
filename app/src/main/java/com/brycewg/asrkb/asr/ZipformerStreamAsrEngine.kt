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
 * 基于 sherpa-onnx OnlineRecognizer 的本地 Zipformer 流式识别引擎。
 * 流程同 ParaformerStreamAsrEngine：录音分片送入 OnlineStream，解码并节流发送 partial。
 */
class ZipformerStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "ZipformerStreamAsrEngine"
        private const val FRAME_MS = 200
    }

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val finalizeOnce = AtomicBoolean(false)
    private var audioJob: Job? = null
    private val mgr = ZipformerOnnxManager.getInstance()
    private var currentStream: Any? = null
    private val streamMutex = Mutex()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

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
        finalizeOnce.set(false)

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

        startCapture()
        scope.launch(Dispatchers.Default) {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val root = java.io.File(base, "zipformer")
            val groupDir = when {
                prefs.zfModelVariant.startsWith("zh-xl-") -> java.io.File(root, "zh-xlarge-2025-06-30")
                prefs.zfModelVariant.startsWith("zh-") -> java.io.File(root, "zh-2025-06-30")
                prefs.zfModelVariant.startsWith("bi-small-") -> java.io.File(root, "small-bilingual-zh-en-2023-02-16")
                else -> java.io.File(root, "bilingual-zh-en-2023-02-20")
            }
            val dir = findZfModelDir(groupDir)
            if (dir == null) {
                listener.onError(context.getString(R.string.error_paraformer_model_missing))
                running.set(false)
                return@launch
            }

            val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
            val int8 = prefs.zfModelVariant.contains("int8")
            val enc = pickZfComponentFile(dir, "encoder", int8)
            val dec = pickZfComponentFile(dir, "decoder", int8) ?: pickZfComponentFile(dir, "decoder", false)
            val join = pickZfComponentFile(dir, "joiner", int8)
            if (enc == null || dec == null || join == null) {
                listener.onError(context.getString(R.string.error_paraformer_model_missing))
                running.set(false)
                return@launch
            }

            // 语言偏好：若选英文且存在 bpe.model，则使用 bpe 词汇表；中文则使用 cjkchar；auto 则按默认
            val bpeFile = java.io.File(dir, "bpe.model")
            val modelingUnit = if (bpeFile.exists()) "bpe" else "cjkchar"
            val bpeVocab = if (bpeFile.exists()) bpeFile.absolutePath else ""

            val keepMinutes = prefs.zfKeepAliveMinutes
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val ruleFsts = try { if (prefs.zfUseItn) ItnAssets.ensureItnFstPath(context) else null } catch (_: Throwable) { null }
            val ok = mgr.prepare(
                tokens = tokensPath,
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                joiner = join.absolutePath,
                ruleFsts = ruleFsts,
                numThreads = prefs.zfNumThreads,
                modelingUnit = modelingUnit,
                bpeVocab = bpeVocab,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = { notifyLoadUi(true) },
                onLoadDone = { notifyLoadUi(false) },
            )
            if (!ok) {
                Log.w(TAG, "Zipformer prepare() failed")
                return@launch
            }

            val stream = mgr.createStreamOrNull()
            if (stream == null) {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
                return@launch
            }
            currentStream = stream

            drainPrebufferTo(stream)

            if (closing.get() && finalizeOnce.compareAndSet(false, true)) {
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
        if (s != null && finalizeOnce.compareAndSet(false, true)) {
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
                Log.d(TAG, "Starting audio capture for Zipformer with chunk=${chunkMillis}ms")
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get() && currentStream == null) return@collect

                    // Calculate and send audio amplitude (for waveform animation)
                    try {
                        val amplitude = com.brycewg.asrkb.asr.calculateNormalizedAmplitude(audioChunk)
                        listener.onAmplitude(amplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

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

        val now = SystemClock.uptimeMillis()
        if (!partial.isNullOrBlank() && running.get() && !closing.get()) {
            var trimmed = partial!!.trim()
            try {
                if (prefs.zfUseItn) {
                    trimmed = com.brycewg.asrkb.util.TextSanitizer.trimTrailingPunctAndEmoji(trimmed)
                }
            } catch (_: Throwable) { }
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
            streamMutex.withLock {
                if (currentStream !== stream) return
                val tailSamples = ((sampleRate * 0.6).toInt()).coerceAtLeast(1)
                val tail = FloatArray(tailSamples)
                mgr.acceptWaveform(stream, tail, sampleRate)
                mgr.inputFinished(stream)

                var loops = 0
                while (mgr.isReady(stream) && loops < 64) {
                    try {
                        mgr.decode(stream)
                    } catch (decodeErr: Throwable) {
                        Log.e(TAG, "decode failed during finalize", decodeErr)
                        break
                    }
                    loops++
                }
            }

            var text = mgr.getResultText(stream)?.trim().orEmpty()
            try {
                if (prefs.zfUseItn) {
                    text = com.brycewg.asrkb.util.TextSanitizer.trimTrailingPunctAndEmoji(text)
                }
            } catch (_: Throwable) { }
            listener.onFinal(text)
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
 * 查找 Zipformer 模型目录：包含 tokens.txt、decoder.onnx，且存在 encoder(.int8).onnx 与 joiner(.int8).onnx。
 */
fun findZfModelDir(root: java.io.File?): java.io.File? {
    if (root == null || !root.exists()) return null
    fun validDir(d: java.io.File): Boolean {
        if (!d.exists() || !d.isDirectory) return false
        val tokens = java.io.File(d, "tokens.txt")
        if (!tokens.exists()) return false
        return hasZfRequiredComponents(d)
    }
    if (validDir(root)) return root
    val subs = root.listFiles() ?: return null
    subs.forEach { f -> if (f.isDirectory && validDir(f)) return f }
    return null
}

private fun hasZfRequiredComponents(dir: java.io.File): Boolean {
    val files = dir.listFiles() ?: return false
    fun exists(regex: Regex): Boolean = files.any { f -> f.isFile && regex.matches(f.name) }
    val hasEncoder = exists(Regex("^encoder(?:-epoch-\\d+-avg-\\d+)?(?:\\.int8)?\\.onnx$"))
    val hasDecoder = exists(Regex("^decoder(?:-epoch-\\d+-avg-\\d+)?(?:\\.int8)?\\.onnx$")) || exists(Regex("^decoder\\.onnx$"))
    val hasJoiner = exists(Regex("^joiner(?:-epoch-\\d+-avg-\\d+)?(?:\\.int8)?\\.onnx$"))
    return hasEncoder && hasDecoder && hasJoiner
}

private fun pickZfComponentFile(dir: java.io.File, comp: String, preferInt8: Boolean): java.io.File? {
    val files = dir.listFiles() ?: return null
    val patternsPreferred = if (preferInt8) listOf(
        Regex("^${comp}(?:-epoch-\\d+-avg-\\d+)?\\.int8\\.onnx$"),
        Regex("^${comp}\\.int8\\.onnx$")
    ) else listOf(
        Regex("^${comp}\\.onnx$"),
        Regex("^${comp}(?:-epoch-\\d+-avg-\\d+)?\\.onnx$")
    )
    val patternsFallback = if (preferInt8) listOf(
        Regex("^${comp}\\.onnx$"),
        Regex("^${comp}(?:-epoch-\\d+-avg-\\d+)?\\.onnx$")
    ) else listOf(
        Regex("^${comp}(?:-epoch-\\d+-avg-\\d+)?\\.int8\\.onnx$"),
        Regex("^${comp}\\.int8\\.onnx$")
    )

    fun matchFirst(patterns: List<Regex>): java.io.File? {
        for (p in patterns) {
            val f = files.firstOrNull { it.isFile && p.matches(it.name) }
            if (f != null) return f
        }
        return null
    }

    return matchFirst(patternsPreferred) ?: matchFirst(patternsFallback)
}

/**
 * 释放 Zipformer 识别器（供设置页或切换供应商时手工卸载）
 */
fun unloadZipformerRecognizer() {
    try { ZipformerOnnxManager.getInstance().unload() } catch (t: Throwable) {
        Log.e("ZipformerStreamAsrEngine", "Failed to unload zipformer recognizer", t)
    }
}

// 判断是否已有缓存的本地 Zipformer 识别器（已加载模型）
fun isZipformerPrepared(): Boolean {
    return try {
        ZipformerOnnxManager.getInstance().isPrepared()
    } catch (t: Throwable) {
        Log.e("ZipformerStreamAsrEngine", "Failed to check zipformer prepared", t)
        false
    }
}

// ====== 反射式 OnlineRecognizer/Stream 封装与管理器（Transducer/Zipformer） ======

private class ZfReflectiveOnlineStream(val instance: Any) {
    private val cls = instance.javaClass

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        try {
            cls.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, samples, sampleRate)
        } catch (t: Throwable) {
            Log.e("ZfOnlineStream", "acceptWaveform reflection failed", t)
        }
    }

    fun inputFinished() {
        try { cls.getMethod("inputFinished").invoke(instance) } catch (t: Throwable) {
            Log.e("ZfOnlineStream", "inputFinished failed", t)
        }
    }

    fun release() {
        try { cls.getMethod("release").invoke(instance) } catch (t: Throwable) {
            Log.e("ZfOnlineStream", "release failed", t)
        }
    }
}

private class ZfReflectiveOnlineRecognizer(private val instance: Any, private val cls: Class<*>) {
    fun createStream(hotwords: String = ""): ZfReflectiveOnlineStream {
        val s = cls.getMethod("createStream", String::class.java).invoke(instance, hotwords)
            ?: throw IllegalStateException("OnlineRecognizer.createStream returned null")
        return ZfReflectiveOnlineStream(s)
    }

    fun isReady(stream: ZfReflectiveOnlineStream): Boolean {
        return cls.getMethod("isReady", stream.instance.javaClass)
            .invoke(instance, stream.instance) as Boolean
    }

    fun decode(stream: ZfReflectiveOnlineStream) {
        cls.getMethod("decode", stream.instance.javaClass)
            .invoke(instance, stream.instance)
    }

    fun getResultText(stream: ZfReflectiveOnlineStream): String? {
        val res = cls.getMethod("getResult", stream.instance.javaClass)
            .invoke(instance, stream.instance)
        return try {
            res.javaClass.getMethod("getText").invoke(res) as? String
        } catch (t: Throwable) {
            Log.e("ZfOnlineRecognizer", "getResultText getter not found", t)
            null
        }
    }

    fun release() {
        try { cls.getMethod("release").invoke(instance) } catch (t: Throwable) {
            Log.e("ZfOnlineRecognizer", "release failed", t)
        }
    }
}

class ZipformerOnnxManager private constructor() {
    companion object {
        private const val TAG = "ZipformerOnnxManager"

        @Volatile private var instance: ZipformerOnnxManager? = null
        fun getInstance(): ZipformerOnnxManager = instance ?: synchronized(this) {
            instance ?: ZipformerOnnxManager().also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()

    @Volatile private var cachedConfig: RecognizerConfig? = null
    @Volatile private var cachedRecognizer: ZfReflectiveOnlineRecognizer? = null
    @Volatile private var clsOnlineRecognizer: Class<*>? = null
    @Volatile private var clsOnlineRecognizerConfig: Class<*>? = null
    @Volatile private var clsOnlineModelConfig: Class<*>? = null
    @Volatile private var clsOnlineTransducerModelConfig: Class<*>? = null
    @Volatile private var clsFeatureConfig: Class<*>? = null
    @Volatile private var unloadJob: Job? = null

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
            clsOnlineTransducerModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
            clsFeatureConfig = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            Log.d(TAG, "Initialized reflection classes for online recognizer (Zipformer)")
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
        val joiner: String,
        val ruleFsts: String?,
        val numThreads: Int,
        val provider: String = "cpu",
        val sampleRate: Int = 16000,
        val featureDim: Int = 80,
        val debug: Boolean = false,
        val modelingUnit: String = "",
        val bpeVocab: String = ""
    )

    private fun buildModelConfig(tokens: String, encoder: String, decoder: String, joiner: String, numThreads: Int, provider: String, debug: Boolean, modelingUnit: String, bpeVocab: String): Any {
        val trans = clsOnlineTransducerModelConfig!!.getDeclaredConstructor(String::class.java, String::class.java, String::class.java)
            .newInstance(encoder, decoder, joiner)
        val model = clsOnlineModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(model, "tokens", tokens)
        trySetField(model, "numThreads", numThreads)
        trySetField(model, "provider", provider)
        trySetField(model, "debug", debug)
        trySetField(model, "transducer", trans)
        if (modelingUnit.isNotEmpty()) trySetField(model, "modelingUnit", modelingUnit)
        if (bpeVocab.isNotEmpty()) trySetField(model, "bpeVocab", bpeVocab)
        return model
    }

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        return feat
    }

    private fun buildRecognizerConfig(config: RecognizerConfig): Any {
        val model = buildModelConfig(config.tokens, config.encoder, config.decoder, config.joiner, config.numThreads, config.provider, config.debug, config.modelingUnit, config.bpeVocab)
        val feat = buildFeatureConfig(config.sampleRate, config.featureDim)
        val rec = clsOnlineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        trySetField(rec, "modelConfig", model)
        trySetField(rec, "featConfig", feat)
        // 固定默认值，移除设置项
        trySetField(rec, "decodingMethod", "greedy_search")
        trySetField(rec, "enableEndpoint", true)
        trySetField(rec, "maxActivePaths", 4)
        // ITN：若给定 ruleFsts 路径，则直接设置（资产文件已由调用方拷贝到 files/itn）。
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
        joiner: String,
        ruleFsts: String?,
        numThreads: Int,
        modelingUnit: String,
        bpeVocab: String,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null,
    ): Boolean = mutex.withLock {
        try {
            initClasses()
            val config = RecognizerConfig(tokens, encoder, decoder, joiner, ruleFsts, numThreads, modelingUnit = modelingUnit, bpeVocab = bpeVocab)
            val same = (cachedConfig == config)
            if (!same || cachedRecognizer == null) {
                try { onLoadStart?.invoke() } catch (t: Throwable) { Log.e(TAG, "onLoadStart failed", t) }
                val recConfig = buildRecognizerConfig(config)
                val inst = createRecognizer(recConfig)
                cachedRecognizer?.release()
                cachedRecognizer = ZfReflectiveOnlineRecognizer(inst, clsOnlineRecognizer!!)
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
            val s = r.createStream("")
            activeStreams++
            s
        } catch (t: Throwable) {
            Log.e(TAG, "createStream failed", t); null
        }
    }

    fun acceptWaveform(stream: Any, samples: FloatArray, sampleRate: Int) {
        try { if (stream is ZfReflectiveOnlineStream) stream.acceptWaveform(samples, sampleRate) } catch (t: Throwable) {
            Log.e(TAG, "acceptWaveform failed", t)
        }
    }

    fun inputFinished(stream: Any) {
        try { if (stream is ZfReflectiveOnlineStream) stream.inputFinished() } catch (t: Throwable) {
            Log.e(TAG, "inputFinished failed", t)
        }
    }

    fun isReady(stream: Any): Boolean {
        return try {
            val r = cachedRecognizer ?: return false
            if (stream is ZfReflectiveOnlineStream) r.isReady(stream) else false
        } catch (t: Throwable) {
            Log.e(TAG, "isReady failed", t); false
        }
    }

    fun decode(stream: Any) {
        try {
            val r = cachedRecognizer ?: return
            if (stream is ZfReflectiveOnlineStream) r.decode(stream)
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
        }
    }

    fun getResultText(stream: Any): String? {
        return try {
            val r = cachedRecognizer ?: return null
            if (stream is ZfReflectiveOnlineStream) r.getResultText(stream) else null
        } catch (t: Throwable) {
            Log.e(TAG, "getResultText failed", t); null
        }
    }

    fun releaseStream(stream: Any?) {
        if (stream == null) return
        try {
            if (stream is ZfReflectiveOnlineStream) stream.release()
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

// 预加载：根据当前配置尝试构建本地 Zipformer 在线识别器
fun preloadZipformerIfConfigured(
    context: android.content.Context,
    prefs: com.brycewg.asrkb.store.Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        val manager = ZipformerOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) return

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = java.io.File(base, "zipformer")
        val group = when {
            prefs.zfModelVariant.startsWith("zh-xl-") -> java.io.File(root, "zh-xlarge-2025-06-30")
            prefs.zfModelVariant.startsWith("zh-") -> java.io.File(root, "zh-2025-06-30")
            prefs.zfModelVariant.startsWith("bi-small-") -> java.io.File(root, "small-bilingual-zh-en-2023-02-16")
            else -> java.io.File(root, "bilingual-zh-en-2023-02-20")
        }
        val dir = findZfModelDir(group) ?: return
        val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
        val int8 = prefs.zfModelVariant.contains("int8")
        val enc = pickZfComponentFile(dir, "encoder", int8) ?: return
        val dec = pickZfComponentFile(dir, "decoder", int8) ?: pickZfComponentFile(dir, "decoder", false) ?: return
        val join = pickZfComponentFile(dir, "joiner", int8) ?: return

        val bpeFile = java.io.File(dir, "bpe.model")
        val modelingUnit = if (bpeFile.exists()) "bpe" else "cjkchar"
        val bpeVocab = if (bpeFile.exists()) bpeFile.absolutePath else ""

        val keepMinutes = prefs.zfKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val t0 = try { android.os.SystemClock.uptimeMillis() } catch (_: Throwable) { 0L }
            val ruleFsts = try { if (prefs.zfUseItn) ItnAssets.ensureItnFstPath(context) else null } catch (_: Throwable) { null }
            val ok = manager.prepare(
                tokens = tokensPath,
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                joiner = join.absolutePath,
                ruleFsts = ruleFsts,
                numThreads = prefs.zfNumThreads,
                modelingUnit = modelingUnit,
                bpeVocab = bpeVocab,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    try { onLoadStart?.invoke() } catch (t: Throwable) { Log.e("ZipformerPreload", "onLoadStart failed", t) }
                    if (!suppressToastOnStart) {
                        try {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try { android.widget.Toast.makeText(context, context.getString(com.brycewg.asrkb.R.string.sv_loading_model), android.widget.Toast.LENGTH_SHORT).show() } catch (t: Throwable) {
                                    Log.e("ZipformerPreload", "Show toast failed", t)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e("ZipformerPreload", "Post toast failed", t)
                        }
                    }
                },
                onLoadDone = onLoadDone,
            )
            if (ok && !forImmediateUse) {
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
                            Log.e("ZipformerPreload", "Show load-done toast failed", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("ZipformerPreload", "Post load-done toast failed", t)
                }
                try { manager.scheduleUnloadIfIdle() } catch (t: Throwable) {
                    Log.e("ZipformerPreload", "scheduleUnloadIfIdle failed", t)
                }
            }
        }
    } catch (t: Throwable) {
        Log.e("ZipformerPreload", "preloadZipformerIfConfigured failed", t)
    }
}
