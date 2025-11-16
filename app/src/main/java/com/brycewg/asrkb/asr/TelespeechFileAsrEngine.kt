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
 * 本地 TeleSpeech（通过 sherpa-onnx）非流式文件识别引擎。
 * - 基于 OfflineRecognizer + OfflineModelConfig.teleSpeech 字段。
 * - 仅支持本地离线识别，不支持流式。
 */
class TelespeechFileAsrEngine(
  context: Context,
  scope: CoroutineScope,
  prefs: Prefs,
  listener: StreamingAsrEngine.Listener,
  onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration), PcmBatchRecognizer {

  // TeleSpeech 本地：同 SenseVoice，默认限制为 5 分钟以控制内存与处理时长
  override val maxRecordDurationMillis: Int = 5 * 60 * 1000

  private fun showToast(resId: Int) {
    try {
      Handler(Looper.getMainLooper()).post {
        try {
          Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
          Log.e("TelespeechFileAsrEngine", "Failed to show toast", t)
        }
      }
    } catch (t: Throwable) {
      Log.e("TelespeechFileAsrEngine", "Failed to post toast", t)
    }
  }

  private fun notifyLoadStart() {
    val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)
    if (ui != null) {
      try {
        ui.onLocalModelLoadStart()
      } catch (t: Throwable) {
        Log.e("TelespeechFileAsrEngine", "Failed to notify load start", t)
      }
    } else {
      // 复用通用“本地模型加载中”文案
      showToast(R.string.sv_loading_model)
    }
  }

  private fun notifyLoadDone() {
    val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)
    if (ui != null) {
      try {
        ui.onLocalModelLoadDone()
      } catch (t: Throwable) {
        Log.e("TelespeechFileAsrEngine", "Failed to notify load done", t)
      }
    }
  }

  override fun ensureReady(): Boolean {
    if (!super.ensureReady()) return false
    val manager = TelespeechOnnxManager.getInstance()
    if (!manager.isOnnxAvailable()) {
      try {
        listener.onError(context.getString(R.string.error_local_asr_not_ready))
      } catch (t: Throwable) {
        Log.e("TelespeechFileAsrEngine", "Failed to send error callback", t)
      }
      return false
    }
    return true
  }

  override suspend fun recognize(pcm: ByteArray) {
    val t0 = System.currentTimeMillis()
    try {
      val manager = TelespeechOnnxManager.getInstance()
      if (!manager.isOnnxAvailable()) {
        listener.onError(context.getString(R.string.error_local_asr_not_ready))
        return
      }

      val base = try {
        context.getExternalFilesDir(null)
      } catch (t: Throwable) {
        Log.w("TelespeechFileAsrEngine", "Failed to get external files dir", t)
        null
      } ?: context.filesDir

      val probeRoot = java.io.File(base, "telespeech")
      val variant = try {
        prefs.tsModelVariant
      } catch (t: Throwable) {
        Log.w("TelespeechFileAsrEngine", "Failed to get TeleSpeech variant", t)
        "int8"
      }
      val variantDir = when (variant) {
        "full" -> java.io.File(probeRoot, "full")
        else -> java.io.File(probeRoot, "int8")
      }
      val auto = findTsModelDir(variantDir) ?: findTsModelDir(probeRoot)
      if (auto == null) {
        listener.onError(context.getString(R.string.error_telespeech_model_missing))
        return
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
        listener.onError(context.getString(R.string.error_telespeech_model_missing))
        return
      }

      val samples = pcmToFloatArray(pcm)
      if (samples.isEmpty()) {
        listener.onError(context.getString(R.string.error_audio_empty))
        return
      }

      val keepMinutes = try {
        prefs.tsKeepAliveMinutes
      } catch (t: Throwable) {
        Log.w("TelespeechFileAsrEngine", "Failed to get keep alive minutes", t)
        -1
      }
      val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
      val alwaysKeep = keepMinutes < 0

      val ruleFsts = try {
        if (prefs.tsUseItn) ItnAssets.ensureItnFstPath(context) else null
      } catch (t: Throwable) {
        Log.e("TelespeechFileAsrEngine", "Failed to resolve ITN FST path", t)
        null
      }

      val text = manager.decodeOffline(
        assetManager = null,
        tokens = tokensPath,
        model = modelPath,
        provider = "cpu",
        numThreads = try {
          prefs.tsNumThreads
        } catch (t: Throwable) {
          Log.w("TelespeechFileAsrEngine", "Failed to get num threads", t)
          2
        },
        ruleFsts = ruleFsts,
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
      Log.e("TelespeechFileAsrEngine", "Recognition failed", t)
      listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
    } finally {
      val dt = System.currentTimeMillis() - t0
      try {
        onRequestDuration?.invoke(dt)
      } catch (t: Throwable) {
        Log.e("TelespeechFileAsrEngine", "Failed to invoke duration callback", t)
      }
    }
  }

  override suspend fun recognizeFromPcm(pcm: ByteArray) {
    recognize(pcm)
  }

  private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
    if (pcm.isEmpty()) return FloatArray(0)
    val n = pcm.size / 2
    val out = FloatArray(n)
    val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
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

// 公开卸载入口：供设置页在清除模型后释放本地识别器内存
fun unloadTelespeechRecognizer() {
  try {
    TelespeechOnnxManager.getInstance().unload()
  } catch (t: Throwable) {
    Log.e("TelespeechFileAsrEngine", "Failed to unload recognizer", t)
  }
}

// 判断是否已有缓存的本地识别器（已加载模型）
fun isTelespeechPrepared(): Boolean {
  return try {
    TelespeechOnnxManager.getInstance().isPrepared()
  } catch (t: Throwable) {
    Log.e("TelespeechFileAsrEngine", "Failed to check if prepared", t)
    false
  }
}

// TeleSpeech 模型目录探测：与 SenseVoice 一致，查找含 tokens.txt 的目录
fun findTsModelDir(root: java.io.File?): java.io.File? {
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
 * TeleSpeech ONNX 识别器管理器（基于 OfflineRecognizer）
 */
class TelespeechOnnxManager private constructor() {

  companion object {
    private const val TAG = "TelespeechOnnxManager"

    @Volatile
    private var instance: TelespeechOnnxManager? = null

    fun getInstance(): TelespeechOnnxManager {
      return instance ?: synchronized(this) {
        instance ?: TelespeechOnnxManager().also { instance = it }
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob())
  private val mutex = Mutex()

  @Volatile
  private var cachedConfig: RecognizerConfig? = null
  @Volatile
  private var cachedRecognizer: ReflectiveRecognizer? = null
  @Volatile
  private var clsOfflineRecognizer: Class<*>? = null
  @Volatile
  private var clsOfflineRecognizerConfig: Class<*>? = null
  @Volatile
  private var clsOfflineModelConfig: Class<*>? = null
  @Volatile
  private var clsFeatureConfig: Class<*>? = null
  @Volatile
  private var unloadJob: Job? = null

  @Volatile
  private var lastKeepAliveMs: Long = 0L
  @Volatile
  private var lastAlwaysKeep: Boolean = false

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

  fun isPrepared(): Boolean = cachedRecognizer != null

  private fun scheduleAutoUnload(keepAliveMs: Long, alwaysKeep: Boolean) {
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

  private data class RecognizerConfig(
    val tokens: String,
    val model: String,
    val provider: String,
    val numThreads: Int,
    val ruleFsts: String?,
    val sampleRate: Int,
    val featureDim: Int
  )

  private fun initClasses() {
    if (clsOfflineRecognizer == null) {
      clsOfflineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
      clsOfflineRecognizerConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
      clsOfflineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
      clsFeatureConfig = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
      Log.d(TAG, "Initialized sherpa-onnx reflection classes for TeleSpeech")
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
        val methodName = "set" + name.replaceFirstChar {
          if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val m = if (value == null) {
          target.javaClass.getMethod(methodName, Any::class.java)
        } else {
          target.javaClass.getMethod(methodName, value.javaClass)
        }
        m.invoke(target, value)
        true
      } catch (t2: Throwable) {
        Log.w(TAG, "Failed to set field '$name'", t2)
        false
      }
    }
  }

  private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
    val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
    trySetField(feat, "sampleRate", sampleRate)
    trySetField(feat, "featureDim", featureDim)
    return feat
  }

  private fun buildModelConfig(tokens: String, model: String, numThreads: Int, provider: String): Any {
    val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
    trySetField(modelConfig, "tokens", tokens)
    trySetField(modelConfig, "numThreads", numThreads)
    trySetField(modelConfig, "provider", provider)
    trySetField(modelConfig, "debug", false)
    trySetField(modelConfig, "teleSpeech", model)
    trySetField(modelConfig, "modelType", "telespeech_ctc")
    return modelConfig
  }

  private fun buildRecognizerConfig(config: RecognizerConfig): Any {
    val modelConfig = buildModelConfig(config.tokens, config.model, config.numThreads, config.provider)
    val featConfig = buildFeatureConfig(config.sampleRate, config.featureDim)
    val recConfig = clsOfflineRecognizerConfig!!.getDeclaredConstructor().newInstance()
    if (!trySetField(recConfig, "modelConfig", modelConfig)) {
      trySetField(recConfig, "model_config", modelConfig)
    }
    if (!trySetField(recConfig, "featConfig", featConfig)) {
      trySetField(recConfig, "feat_config", featConfig)
    }
    // ITN：如提供了 ruleFsts，则透传给 OfflineRecognizerConfig
    if (!config.ruleFsts.isNullOrBlank()) {
      trySetField(recConfig, "ruleFsts", config.ruleFsts)
    }
    trySetField(recConfig, "decodingMethod", "greedy_search")
    trySetField(recConfig, "maxActivePaths", 4)
    return recConfig
  }

  private fun createRecognizer(assetManager: android.content.res.AssetManager?, recConfig: Any): Any {
    val ctor = if (assetManager == null) {
      try {
        clsOfflineRecognizer!!.getDeclaredConstructor(clsOfflineRecognizerConfig)
      } catch (t: Throwable) {
        Log.d(TAG, "No single-param constructor, using AssetManager variant", t)
        clsOfflineRecognizer!!.getDeclaredConstructor(
          android.content.res.AssetManager::class.java,
          clsOfflineRecognizerConfig
        )
      }
    } else {
      try {
        clsOfflineRecognizer!!.getDeclaredConstructor(
          android.content.res.AssetManager::class.java,
          clsOfflineRecognizerConfig
        )
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

  suspend fun decodeOffline(
    assetManager: android.content.res.AssetManager?,
    tokens: String,
    model: String,
    provider: String,
    numThreads: Int,
    ruleFsts: String? = null,
    samples: FloatArray,
    sampleRate: Int,
    keepAliveMs: Long,
    alwaysKeep: Boolean,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null
  ): String? = mutex.withLock {
    try {
      initClasses()
      val cfg = RecognizerConfig(
        tokens = tokens,
        model = model,
        provider = provider,
        numThreads = numThreads,
        ruleFsts = ruleFsts,
        sampleRate = sampleRate,
        featureDim = 40
      )
      var recognizer = cachedRecognizer
      if (cachedConfig != cfg || recognizer == null) {
        try {
          onLoadStart?.invoke()
        } catch (t: Throwable) {
          Log.e(TAG, "onLoadStart callback failed", t)
        }
        val recConfig = buildRecognizerConfig(cfg)
        val raw = createRecognizer(assetManager, recConfig)
        recognizer = ReflectiveRecognizer(raw, clsOfflineRecognizer!!)
        cachedRecognizer = recognizer
        cachedConfig = cfg
        try {
          onLoadDone?.invoke()
        } catch (t: Throwable) {
          Log.e(TAG, "onLoadDone callback failed", t)
        }
      }
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
      Log.e(TAG, "Failed to decode offline TeleSpeech: ${t.message}", t)
      return@withLock null
    }
  }

  suspend fun prepare(
    assetManager: android.content.res.AssetManager?,
    tokens: String,
    model: String,
    provider: String,
    numThreads: Int,
    ruleFsts: String? = null,
    keepAliveMs: Long,
    alwaysKeep: Boolean,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null
  ): Boolean = mutex.withLock {
    try {
      initClasses()
      val cfg = RecognizerConfig(
        tokens = tokens,
        model = model,
        provider = provider,
        numThreads = numThreads,
        ruleFsts = ruleFsts,
        sampleRate = 16000,
        featureDim = 40
      )
      var recognizer = cachedRecognizer
      if (cachedConfig != cfg || recognizer == null) {
        try {
          onLoadStart?.invoke()
        } catch (t: Throwable) {
          Log.e(TAG, "onLoadStart callback failed", t)
        }
        val recConfig = buildRecognizerConfig(cfg)
        val raw = createRecognizer(assetManager, recConfig)
        recognizer = ReflectiveRecognizer(raw, clsOfflineRecognizer!!)
        cachedRecognizer = recognizer
        cachedConfig = cfg
        try {
          onLoadDone?.invoke()
        } catch (t: Throwable) {
          Log.e(TAG, "onLoadDone callback failed", t)
        }
      }
      lastKeepAliveMs = keepAliveMs
      lastAlwaysKeep = alwaysKeep
      true
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to prepare TeleSpeech recognizer: ${t.message}", t)
      false
    }
  }
}

/**
 * TeleSpeech 预加载：根据当前配置尝试构建本地识别器，便于降低首次点击等待
 */
fun preloadTelespeechIfConfigured(
  context: Context,
  prefs: Prefs,
  onLoadStart: (() -> Unit)? = null,
  onLoadDone: (() -> Unit)? = null,
  suppressToastOnStart: Boolean = false,
  forImmediateUse: Boolean = false
) {
  try {
    val manager = TelespeechOnnxManager.getInstance()
    if (!manager.isOnnxAvailable()) return

    val keepMinutesGuard = try {
      prefs.tsKeepAliveMinutes
    } catch (t: Throwable) {
      Log.w("TelespeechFileAsrEngine", "Failed to get keep alive minutes", t)
      -1
    }
    val base = try {
      context.getExternalFilesDir(null)
    } catch (t: Throwable) {
      Log.w("TelespeechFileAsrEngine", "Failed to get external files dir", t)
      null
    } ?: context.filesDir
    val probeRoot = java.io.File(base, "telespeech")
    val variant = try {
      prefs.tsModelVariant
    } catch (t: Throwable) {
      Log.w("TelespeechFileAsrEngine", "Failed to get TeleSpeech variant", t)
      "int8"
    }
    val variantDir = when (variant) {
      "full" -> java.io.File(probeRoot, "full")
      else -> java.io.File(probeRoot, "int8")
    }
    val auto = findTsModelDir(variantDir) ?: findTsModelDir(probeRoot) ?: return
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
    val keepMinutes = try {
      prefs.tsKeepAliveMinutes
    } catch (t: Throwable) {
      Log.w("TelespeechFileAsrEngine", "Failed to get keep alive minutes", t)
      -1
    }
    val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
    val alwaysKeep = keepMinutes < 0

    val ruleFsts = try {
      if (prefs.tsUseItn) ItnAssets.ensureItnFstPath(context) else null
    } catch (t: Throwable) {
      Log.e("TelespeechFileAsrEngine", "Failed to resolve ITN FST path for preload", t)
      null
    }

    CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
      val t0 = try {
        android.os.SystemClock.uptimeMillis()
      } catch (_: Throwable) {
        0L
      }
      val ok = manager.prepare(
        assetManager = null,
        tokens = tokensPath,
        model = modelPath,
        provider = "cpu",
        numThreads = try {
          prefs.tsNumThreads
        } catch (t: Throwable) {
          Log.w("TelespeechFileAsrEngine", "Failed to get num threads", t)
          2
        },
        ruleFsts = ruleFsts,
        keepAliveMs = keepMs,
        alwaysKeep = alwaysKeep,
        onLoadStart = {
          try {
            onLoadStart?.invoke()
          } catch (t: Throwable) {
            Log.e("TelespeechFileAsrEngine", "onLoadStart callback failed", t)
          }
          if (!suppressToastOnStart) {
            try {
              val mh = Handler(Looper.getMainLooper())
              mh.post {
                try {
                  Toast.makeText(
                    context,
                    context.getString(R.string.sv_loading_model),
                    Toast.LENGTH_SHORT
                  ).show()
                } catch (t: Throwable) {
                  Log.e("TelespeechFileAsrEngine", "Failed to show toast", t)
                }
              }
            } catch (t: Throwable) {
              Log.e("TelespeechFileAsrEngine", "Failed to post toast", t)
            }
          }
        },
        onLoadDone = onLoadDone
      )
      if (ok && !forImmediateUse) {
        val dt = try {
          android.os.SystemClock.uptimeMillis() - t0
        } catch (_: Throwable) {
          0L
        }
        if (dt > 0) {
          try {
            val mh = Handler(Looper.getMainLooper())
            mh.post {
              try {
                Toast.makeText(
                  context,
                  context.getString(R.string.sv_model_ready_with_ms, dt),
                  Toast.LENGTH_SHORT
                ).show()
              } catch (t: Throwable) {
                Log.e("TelespeechFileAsrEngine", "Failed to show done toast", t)
              }
            }
          } catch (t: Throwable) {
            Log.e("TelespeechFileAsrEngine", "Failed to post done toast", t)
          }
        }
      }
    }
  } catch (t: Throwable) {
    Log.e("TelespeechFileAsrEngine", "Failed to preload TeleSpeech model", t)
  }
}
