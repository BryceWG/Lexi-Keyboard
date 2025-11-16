package com.brycewg.asrkb.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地 SenseVoice（通过 sherpa-onnx）非流式文件识别引擎。
 * 目前为占位实现：当 sherpa-onnx 依赖与模型未接入时，给出友好提示而不发起录音后的无效识别。
 * 后续接入模型与 AAR/so 后，可在 [recognize] 中补充实际推理调用。
 */
class SenseVoiceFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration), PcmBatchRecognizer {

    // 本地 SenseVoice：为降低内存占用，主动限制为 5 分钟
    override val maxRecordDurationMillis: Int = 5 * 60 * 1000

    interface LocalModelLoadUi {
        fun onLocalModelLoadStart()
        fun onLocalModelLoadDone()
    }

    private fun showToast(resId: Int) {
        try {
            Handler(Looper.getMainLooper()).post {
                try { Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show() } catch (t: Throwable) {
                    Log.e("SenseVoiceFileAsrEngine", "Failed to show toast", t)
                }
            }
        } catch (t: Throwable) {
            Log.e("SenseVoiceFileAsrEngine", "Failed to post toast", t)
        }
    }

    private fun notifyLoadStart() {
        val ui = (listener as? LocalModelLoadUi)
        if (ui != null) {
            try { ui.onLocalModelLoadStart() } catch (t: Throwable) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to notify load start", t)
            }
        } else {
            showToast(R.string.sv_loading_model)
        }
    }

    private fun notifyLoadDone() {
        val ui = (listener as? LocalModelLoadUi)
        if (ui != null) {
            try { ui.onLocalModelLoadDone() } catch (t: Throwable) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to notify load done", t)
            }
        }
    }

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        // 若未集成 sherpa-onnx Kotlin/so，则直接报错以避免无意义的录音
        val manager = SenseVoiceOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            try { listener.onError(context.getString(R.string.error_local_asr_not_ready)) } catch (t: Throwable) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to send error callback", t)
            }
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val t0 = System.currentTimeMillis()
        try {
            val manager = SenseVoiceOnnxManager.getInstance()
            if (!manager.isOnnxAvailable()) {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
                return
            }
            // 模型目录：固定为外部专属目录（不可配置）；外部不可用时回退内部目录
            val base = try { context.getExternalFilesDir(null) } catch (t: Throwable) {
                Log.w("SenseVoiceFileAsrEngine", "Failed to get external files dir", t)
                null
            } ?: context.filesDir
            val probeRoot = java.io.File(base, "sensevoice")
            // 优先在所选版本目录下查找；若缺失则回退到根下任意含 tokens 的目录（兼容旧版）
            val variant = try { prefs.svModelVariant } catch (t: Throwable) {
                Log.w("SenseVoiceFileAsrEngine", "Failed to get model variant", t)
                "small-int8"
            }
            val variantDir = when (variant) {
                "small-full" -> java.io.File(probeRoot, "small-full")
                else -> java.io.File(probeRoot, "small-int8")
            }
            val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot)
            if (auto == null) {
                listener.onError(context.getString(R.string.error_sensevoice_model_missing))
                return
            }
            val dir = auto.absolutePath

            // 准备模型文件（优先 int8 回退 float32）
            val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
            val int8File = java.io.File(dir, "model.int8.onnx")
            val f32File = java.io.File(dir, "model.onnx")
            val modelFile = when {
                int8File.exists() -> int8File
                f32File.exists() -> f32File
                else -> null
            }
            val modelPath = modelFile?.absolutePath
            val minBytes = 8L * 1024L * 1024L // 粗略下限，避免明显的截断文件
            if (modelPath == null || !java.io.File(tokensPath).exists() || (modelFile?.length() ?: 0L) < minBytes) {
                listener.onError(context.getString(R.string.error_sensevoice_model_missing))
                return
            }

            // PCM16LE -> FloatArray(-1..1)
            val samples = pcmToFloatArray(pcm)
            if (samples.isEmpty()) {
                listener.onError(context.getString(R.string.error_audio_empty))
                return
            }

            // 反射调用 sherpa-onnx Kotlin API
            // 注意：当从绝对路径加载模型/词表时，必须将 assetManager 设为 null
            // 参考 sherpa-onnx 提示 https://github.com/k2-fsa/sherpa-onnx/issues/2562
            // 在需要创建新识别器时，向用户提示"加载中/完成"
            val keepMinutes = try { prefs.svKeepAliveMinutes } catch (t: Throwable) {
                Log.w("SenseVoiceFileAsrEngine", "Failed to get keep alive minutes", t)
                -1
            }
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val text = manager.decodeOffline(
                assetManager = null,
                tokens = tokensPath,
                model = modelPath,
                language = try { prefs.svLanguage } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get language", t)
                    "auto"
                },
                useItn = try { prefs.svUseItn } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get useItn", t)
                    false
                },
                provider = "cpu",
                numThreads = try { prefs.svNumThreads } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get num threads", t)
                    2
                },
                samples = samples,
                sampleRate = sampleRate,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = { notifyLoadStart() },
                onLoadDone = { notifyLoadDone() }
            )

            if (text.isNullOrBlank()) {
                listener.onError(context.getString(R.string.error_asr_empty_result))
            } else {
                listener.onFinal(text.trim())
            }
        } catch (t: Throwable) {
            Log.e("SenseVoiceFileAsrEngine", "Recognition failed", t)
            listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
        } finally {
            val dt = System.currentTimeMillis() - t0
            try { onRequestDuration?.invoke(dt) } catch (t: Throwable) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to invoke duration callback", t)
            }
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) { recognize(pcm) }

    private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = pcm.size / 2
        val out = FloatArray(n)
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            val s = bb.short.toInt()
            // 32768f 防止 -32768 溢出；限制到 [-1, 1]
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f
            i++
        }
        return out
    }

    // 目录探测改为统一使用顶层 findSvModelDir
}

// 公开卸载入口：供设置页在清除模型后释放本地识别器内存
fun unloadSenseVoiceRecognizer() {
    try {
        SenseVoiceOnnxManager.getInstance().unload()
    } catch (t: Throwable) {
        Log.e("SenseVoiceFileAsrEngine", "Failed to unload recognizer", t)
    }
}

// 预加载：根据当前配置尝试构建本地识别器，便于降低首次点击等待
fun preloadSenseVoiceIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    // force 参数仅用于兼容旧调用，不再改变保留策略；始终遵循偏好设置
    force: Boolean = false,
    // 该预加载是否紧跟着会被立即使用（例如开始录音）。
    // 若为 true，则不在此处调度卸载，由实际使用/释放时再调度；否则预加载后按设置调度卸载。
    forImmediateUse: Boolean = false
) {
    try {
        val manager = SenseVoiceOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) return
        // 读取保留时长：预加载阶段不再据此直接卸载，由实际使用时机决定
        val keepMinutesGuard = try { prefs.svKeepAliveMinutes } catch (t: Throwable) {
            Log.w("SenseVoiceFileAsrEngine", "Failed to get keep alive minutes", t)
            -1
        }
        val base = try { context.getExternalFilesDir(null) } catch (t: Throwable) {
            Log.w("SenseVoiceFileAsrEngine", "Failed to get external files dir", t)
            null
        } ?: context.filesDir
        val probeRoot = java.io.File(base, "sensevoice")
        val variant = try { prefs.svModelVariant } catch (t: Throwable) {
            Log.w("SenseVoiceFileAsrEngine", "Failed to get model variant", t)
            "small-int8"
        }
        val variantDir = when (variant) {
            "small-full" -> java.io.File(probeRoot, "small-full")
            else -> java.io.File(probeRoot, "small-int8")
        }
        val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot) ?: return
        val dir = auto.absolutePath
        val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
        val int8File = java.io.File(dir, "model.int8.onnx")
        val f32File = java.io.File(dir, "model.onnx")
        val modelFile = when {
            int8File.exists() -> int8File
            f32File.exists() -> f32File
            else -> return
        }
        val modelPath = modelFile.absolutePath
        val minBytes = 8L * 1024L * 1024L
        if (!java.io.File(tokensPath).exists() || modelFile.length() < minBytes) return
        val keepMinutes = try { prefs.svKeepAliveMinutes } catch (t: Throwable) {
            Log.w("SenseVoiceFileAsrEngine", "Failed to get keep alive minutes", t)
            -1
        }
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0
        // 在后台协程触发预加载，避免直接在调用线程（可能是主线程）阻塞
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val t0 = try { android.os.SystemClock.uptimeMillis() } catch (_: Throwable) { 0L }
            val ok = manager.prepare(
                assetManager = null,
                tokens = tokensPath,
                model = modelPath,
                language = try { prefs.svLanguage } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get language", t)
                    "auto"
                },
                useItn = try { prefs.svUseItn } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get useItn", t)
                    false
                },
                provider = "cpu",
                numThreads = try { prefs.svNumThreads } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get num threads", t)
                    2
                },
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    try { onLoadStart?.invoke() } catch (t: Throwable) {
                        Log.e("SenseVoiceFileAsrEngine", "onLoadStart callback failed", t)
                    }
                    if (!suppressToastOnStart) {
                        // 确保在主线程弹出 Toast，避免后台线程直接触发导致异常或卡顿
                        try {
                            val mh = Handler(Looper.getMainLooper())
                            mh.post {
                                try {
                                    Toast.makeText(context, context.getString(R.string.sv_loading_model), Toast.LENGTH_SHORT).show()
                                } catch (t: Throwable) {
                                    Log.e("SenseVoiceFileAsrEngine", "Failed to show toast", t)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e("SenseVoiceFileAsrEngine", "Failed to post toast", t)
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
                                context.getString(R.string.sv_model_ready_with_ms, dt)
                            } else context.getString(R.string.sv_model_ready)
                            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Log.e("SenseVoiceFileAsrEngine", "Failed to show load-done toast", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("SenseVoiceFileAsrEngine", "Post load-done toast failed", t)
                }
            }
        }
    } catch (t: Throwable) {
        Log.e("SenseVoiceFileAsrEngine", "Failed to preload SenseVoice", t)
    }
}

// 顶层工具：在指定根目录下寻找包含 tokens.txt 的模型目录（最多一层）
fun findSvModelDir(root: java.io.File?): java.io.File? {
    if (root == null || !root.exists()) return null
    val direct = java.io.File(root, "tokens.txt")
    if (direct.exists()) return root
    val subs = root.listFiles() ?: return null
    for (f in subs) {
        if (f.isDirectory) {
            val t = java.io.File(f, "tokens.txt")
            if (t.exists()) return f
        }
    }
    return null
}

/**
 * 识别器配置（作为缓存 key）
 */
private data class RecognizerConfig(
    val tokens: String,
    val model: String,
    val language: String,
    val useItn: Boolean,
    val provider: String,
    val numThreads: Int
) {
    fun toCacheKey(): String = listOf(tokens, model, language, useItn, provider, numThreads).joinToString("|")
}

/**
 * 反射式音频流包装
 */
internal class ReflectiveStream(val instance: Any) {
    val streamClass: Class<*> = instance.javaClass

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        try {
            streamClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, samples, sampleRate)
        } catch (t: Throwable) {
            Log.d("ReflectiveStream", "Failed with sampleRate param, trying without", t)
            // 兼容仅 FloatArray 的重载
            streamClass.getMethod("acceptWaveform", FloatArray::class.java)
                .invoke(instance, samples)
        }
    }

    fun release() {
        try {
            streamClass.getMethod("release").invoke(instance)
        } catch (t: Throwable) {
            Log.e("ReflectiveStream", "Failed to release stream", t)
        }
    }
}

/**
 * 反射式识别器包装
 * 封装所有反射调用，对外提供类型安全的接口
 */
internal class ReflectiveRecognizer(
    private val instance: Any,
    private val clsOfflineRecognizer: Class<*>
) {

    fun createStream(): ReflectiveStream {
        val stream = clsOfflineRecognizer
            .getMethod("createStream")
            .invoke(instance)
            ?: throw IllegalStateException("Failed to create stream")
        return ReflectiveStream(stream)
    }

    fun decode(stream: ReflectiveStream): String? {
        val clsStream = stream.streamClass
        clsOfflineRecognizer.getMethod("decode", clsStream).invoke(instance, stream.instance)
        val result = clsOfflineRecognizer.getMethod("getResult", clsStream).invoke(instance, stream.instance)
        return tryGetStringField(result, "text") ?: result?.toString()
    }

    fun release() {
        try {
            clsOfflineRecognizer.getMethod("release").invoke(instance)
        } catch (t: Throwable) {
            Log.e("ReflectiveRecognizer", "Failed to release recognizer", t)
        }
    }

    private fun tryGetStringField(target: Any?, name: String): String? {
        if (target == null) return null
        return try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(target) as? String)
        } catch (t: Throwable) {
            Log.d("ReflectiveRecognizer", "Failed to get string field '$name'", t)
            null
        }
    }
}

/**
 * SenseVoice ONNX 识别器管理器
 *
 * 通过反射调用 sherpa-onnx Kotlin API，实现离线语音识别。
 * 使用单例模式管理识别器实例和生命周期。
 */
class SenseVoiceOnnxManager private constructor() {

    companion object {
        private const val TAG = "SenseVoiceOnnxManager"

        @Volatile
    private var instance: SenseVoiceOnnxManager? = null

        fun getInstance(): SenseVoiceOnnxManager {
            return instance ?: synchronized(this) {
                instance ?: SenseVoiceOnnxManager().also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()

    @Volatile private var cachedConfig: RecognizerConfig? = null
    @Volatile private var cachedRecognizer: ReflectiveRecognizer? = null
    @Volatile private var clsOfflineRecognizer: Class<*>? = null
    @Volatile private var clsOfflineRecognizerConfig: Class<*>? = null
    @Volatile private var clsOfflineModelConfig: Class<*>? = null
    @Volatile private var clsOfflineSenseVoiceModelConfig: Class<*>? = null
    @Volatile private var unloadJob: Job? = null

    fun isOnnxAvailable(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "sherpa-onnx not available", t)
            false
        }
    }

    fun unload() {
        scope.launch {
            mutex.withLock {
                val recognizer = cachedRecognizer
                cachedRecognizer = null
                cachedConfig = null

                recognizer?.release()
                Log.d(TAG, "Recognizer unloaded")
            }
        }
    }

    fun isPrepared(): Boolean {
        return cachedRecognizer != null
    }

    // 记录最近一次配置，用于解码完成后按用户设置调度卸载
    @Volatile private var lastKeepAliveMs: Long = 0L
    @Volatile private var lastAlwaysKeep: Boolean = false

    /**
     * 调度自动卸载
     */
    private fun scheduleAutoUnload(keepAliveMs: Long, alwaysKeep: Boolean) {
        // 取消上一次计划
        unloadJob?.cancel()

        if (alwaysKeep) {
            Log.d(TAG, "Recognizer will be kept alive indefinitely")
            return
        }

        if (keepAliveMs <= 0L) {
            Log.d(TAG, "Auto-unloading immediately (keepAliveMs=$keepAliveMs)")
            unload()
            return
        }

        Log.d(TAG, "Scheduling auto-unload in ${keepAliveMs}ms")
        unloadJob = scope.launch {
            delay(keepAliveMs)
            Log.d(TAG, "Auto-unloading recognizer after timeout")
            unload()
        }
    }

    /**
     * 构建 SenseVoice 模型配置
     */
    private fun buildSenseVoiceConfig(model: String, language: String, useItn: Boolean): Any {
        try {
            // 优先使用完整构造函数
            return clsOfflineSenseVoiceModelConfig!!
                .getDeclaredConstructor(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(model, language, useItn)
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to use 3-param constructor, falling back to 1-param", t)
            // 回退：仅模型路径构造 + 反射设置属性
            val inst = clsOfflineSenseVoiceModelConfig!!
                .getDeclaredConstructor(String::class.java)
                .newInstance(model)
            trySetField(inst, "language", language)
            trySetField(inst, "useItn", useItn)
            return inst
        }
    }

    /**
     * 构建模型配置
     */
    private fun buildModelConfig(tokens: String, numThreads: Int, provider: String, senseVoice: Any): Any {
        val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(modelConfig, "tokens", tokens)
        trySetField(modelConfig, "numThreads", numThreads)
        trySetField(modelConfig, "provider", provider)
        trySetField(modelConfig, "debug", false)

        // Kotlin 属性名可能为 senseVoice 或 sense_voice
        if (!trySetField(modelConfig, "senseVoice", senseVoice)) {
            trySetField(modelConfig, "sense_voice", senseVoice)
        }

        return modelConfig
    }

    /**
     * 构建识别器配置
     */
    private fun buildRecognizerConfig(config: RecognizerConfig): Any {
        val senseVoice = buildSenseVoiceConfig(config.model, config.language, config.useItn)
        val modelConfig = buildModelConfig(config.tokens, config.numThreads, config.provider, senseVoice)

        val recConfig = clsOfflineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        if (!trySetField(recConfig, "modelConfig", modelConfig)) {
            trySetField(recConfig, "model_config", modelConfig)
        }

        return recConfig
    }

    /**
     * 创建识别器实例
     */
    private fun createRecognizer(assetManager: android.content.res.AssetManager?, recConfig: Any): Any {
        // 当 assetManager 为空（从绝对路径加载）时，优先尝试无 assetManager 的构造函数
        val ctor = if (assetManager == null) {
            try {
                clsOfflineRecognizer!!.getDeclaredConstructor(clsOfflineRecognizerConfig)
            } catch (t: Throwable) {
                Log.d(TAG, "No single-param constructor, using AssetManager variant", t)
                // 回退到带 assetManager 的构造（以 null 传入）
                clsOfflineRecognizer!!.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsOfflineRecognizerConfig)
            }
        } else {
            try {
                clsOfflineRecognizer!!.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsOfflineRecognizerConfig)
            } catch (t: Throwable) {
                Log.d(TAG, "No AssetManager constructor, using single-param variant", t)
                clsOfflineRecognizer!!.getDeclaredConstructor(clsOfflineRecognizerConfig)
            }
        }

        return if (ctor.parameterCount == 2) {
            ctor.newInstance(assetManager, recConfig)
        } else {
            ctor.newInstance(recConfig)
        }
    }

    /**
     * 初始化反射类引用
     */
    private fun initClasses() {
        if (clsOfflineRecognizer == null) {
            clsOfflineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            clsOfflineRecognizerConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
            clsOfflineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
            clsOfflineSenseVoiceModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig")
            Log.d(TAG, "Initialized sherpa-onnx reflection classes")
        }
    }

    /**
     * 尝试设置对象字段
     */
    private fun trySetField(target: Any, name: String, value: Any?): Boolean {
        return try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
            Log.d(TAG, "Successfully set field '$name' to ${value?.javaClass?.simpleName}")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to set field '$name', trying setter method", t)
            try {
                val methodName = "set" + name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                val m = target.javaClass.getMethod(methodName, value?.javaClass ?: Any::class.java)
                m.invoke(target, value)
                Log.d(TAG, "Successfully set field '$name' via setter")
                true
            } catch (t2: Throwable) {
                Log.w(TAG, "Failed to set field '$name' via both direct access and setter", t2)
                false
            }
        }
    }

    /**
     * 通过反射完成一次离线解码。依赖于 sherpa-onnx Kotlin API 在运行时可用。
     */
    suspend fun decodeOffline(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        samples: FloatArray,
        sampleRate: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): String? = mutex.withLock {
        try {
            initClasses()

            val config = RecognizerConfig(tokens, model, language, useItn, provider, numThreads)
            var recognizer = cachedRecognizer

            if (cachedConfig != config || recognizer == null) {
                try { onLoadStart?.invoke() } catch (t: Throwable) {
                    Log.e(TAG, "onLoadStart callback failed", t)
                }

                val recConfig = buildRecognizerConfig(config)
                val rawRecognizer = createRecognizer(assetManager, recConfig)

                recognizer = ReflectiveRecognizer(rawRecognizer, clsOfflineRecognizer!!)
                cachedRecognizer = recognizer
                cachedConfig = config

                try { onLoadDone?.invoke() } catch (t: Throwable) {
                    Log.e(TAG, "onLoadDone callback failed", t)
                }
            }

            // 记录本次配置
            lastKeepAliveMs = keepAliveMs
            lastAlwaysKeep = alwaysKeep

            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                val text = recognizer.decode(stream)
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                return@withLock text
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decode offline: ${t.message}", t)
            return@withLock null
        }
    }

    /**
     * 仅预加载（不解码），用于打开键盘等场景的预热。
     */
    suspend fun prepare(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = mutex.withLock {
        try {
            initClasses()

            val config = RecognizerConfig(tokens, model, language, useItn, provider, numThreads)
            var recognizer = cachedRecognizer

            if (cachedConfig != config || recognizer == null) {
                try { onLoadStart?.invoke() } catch (t: Throwable) {
                    Log.e(TAG, "onLoadStart callback failed", t)
                }

                val recConfig = buildRecognizerConfig(config)
                val rawRecognizer = createRecognizer(assetManager, recConfig)

                recognizer = ReflectiveRecognizer(rawRecognizer, clsOfflineRecognizer!!)
                cachedRecognizer = recognizer
                cachedConfig = config

                try { onLoadDone?.invoke() } catch (t: Throwable) {
                    Log.e(TAG, "onLoadDone callback failed", t)
                }
            }
            // 记录本次配置。注意：不在预加载时立即调度卸载，
            // 将在实际使用（decodeOffline）后再依据设置调度。
            lastKeepAliveMs = keepAliveMs
            lastAlwaysKeep = alwaysKeep
            return@withLock true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to prepare recognizer: ${t.message}", t)
            return@withLock false
        }
    }

}

/**
 * 向后兼容的桥接 object
 * 委托给新的 SenseVoiceOnnxManager 单例
 */
@Deprecated("Use SenseVoiceOnnxManager.getInstance() instead", ReplaceWith("SenseVoiceOnnxManager.getInstance()"))
object SenseVoiceOnnxBridge {
    private val manager = SenseVoiceOnnxManager.getInstance()

    fun isOnnxAvailable(): Boolean = manager.isOnnxAvailable()

    fun unload() = manager.unload()

    fun isPrepared(): Boolean = manager.isPrepared()

    suspend fun decodeOffline(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        samples: FloatArray,
        sampleRate: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): String? = manager.decodeOffline(
        assetManager, tokens, model, language, useItn, provider, numThreads,
        samples, sampleRate, keepAliveMs, alwaysKeep, onLoadStart, onLoadDone
    )

    suspend fun prepare(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = manager.prepare(
        assetManager, tokens, model, language, useItn, provider, numThreads,
        keepAliveMs, alwaysKeep, onLoadStart, onLoadDone
    )
}

// 判断是否已有缓存的本地识别器（已加载模型）
fun isSenseVoicePrepared(): Boolean {
    return try {
        SenseVoiceOnnxManager.getInstance().isPrepared()
    } catch (t: Throwable) {
        Log.e("SenseVoiceFileAsrEngine", "Failed to check if prepared", t)
        false
    }
}
