package com.brycewg.asrkb.ui.settings.asr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地模型下载/解压 前台服务：
 * - 采用通知栏通知进度
 * - 支持同时下载不同版本
 * - 解压采用严格校验，临时目录原子替换
 */
class ModelDownloadService : Service() {

  override fun attachBaseContext(newBase: Context?) {
    val wrapped = newBase?.let { LocaleHelper.wrap(it) }
    super.attachBaseContext(wrapped ?: newBase)
  }

  companion object {
    private const val TAG = "ModelDownloadService"
    private const val CHANNEL_ID = "model_download"
    private const val GROUP_ID = "model_download_group"
    private const val SUMMARY_ID = 1000

    private const val ACTION_START = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_START"
    private const val ACTION_IMPORT = "com.brycewg.asrkb.action.MODEL_IMPORT"
    private const val ACTION_CANCEL = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_CANCEL"

    private const val EXTRA_URL = "url"
    private const val EXTRA_URI = "uri"
    private const val EXTRA_VARIANT = "variant"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_MODEL_TYPE = "modelType" // sensevoice | paraformer

    private fun buildDownloadKey(variant: String, modelType: String): DownloadKey {
      val sourceId = when (modelType) {
        "paraformer" -> "download_paraformer"
        "zipformer" -> "download_zipformer"
        "telespeech" -> "download_telespeech"
        else -> "download_sensevoice"
      }
      return DownloadKey(variant, sourceId)
    }

    fun startDownload(context: Context, url: String, variant: String) {
      val modelType = "sensevoice"
      val key = buildDownloadKey(variant, modelType)
      val i = Intent(context, ModelDownloadService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_URL, url)
        putExtra(EXTRA_VARIANT, variant)
        putExtra(EXTRA_KEY, key.toSerializedKey())
        putExtra(EXTRA_MODEL_TYPE, modelType)
      }
      context.startService(i)
    }

    fun startDownload(context: Context, url: String, variant: String, modelType: String) {
      val key = buildDownloadKey(variant, modelType)
      val i = Intent(context, ModelDownloadService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_URL, url)
        putExtra(EXTRA_VARIANT, variant)
        putExtra(EXTRA_KEY, key.toSerializedKey())
        putExtra(EXTRA_MODEL_TYPE, modelType)
      }
      context.startService(i)
    }

    fun startImport(context: Context, uri: android.net.Uri, variant: String) {
      val key = DownloadKey(variant, "import_${uri.lastPathSegment ?: "unknown"}")
      val i = Intent(context, ModelDownloadService::class.java).apply {
        action = ACTION_IMPORT
        putExtra(EXTRA_URI, uri.toString())
        putExtra(EXTRA_VARIANT, variant)
        putExtra(EXTRA_KEY, key.toSerializedKey())
      }
      context.startService(i)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val tasks = ConcurrentHashMap<DownloadKey, kotlinx.coroutines.Job>()
  private val notificationHandlers = ConcurrentHashMap<DownloadKey, NotificationHandler>()
  private lateinit var nm: NotificationManager

  override fun onCreate() {
    super.onCreate()
    nm = getSystemService(NotificationManager::class.java)
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val variant = intent.getStringExtra(EXTRA_VARIANT) ?: ""
        val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "sensevoice"
        val serializedKey = intent.getStringExtra(EXTRA_KEY)
          ?: buildDownloadKey(variant, modelType).toSerializedKey()
        val key = DownloadKey.fromSerializedKey(serializedKey)

        if (!tasks.containsKey(key)) {
          if (tasks.isEmpty()) startAsForegroundSummary()

          val notificationHandler = NotificationHandler(
            context = this,
            notificationManager = nm,
            key = key,
            variant = variant,
            modelType = modelType
          )
          notificationHandlers[key] = notificationHandler

          val job = scope.launch { doDownloadTask(key, url, variant, modelType, notificationHandler) }
          tasks[key] = job
        }
      }
      ACTION_IMPORT -> {
        val uriString = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
        val uri = android.net.Uri.parse(uriString)
        val variant = intent.getStringExtra(EXTRA_VARIANT) ?: ""
        val serializedKey = intent.getStringExtra(EXTRA_KEY) ?: DownloadKey(variant, "import").toSerializedKey()
        val key = DownloadKey.fromSerializedKey(serializedKey)

        if (!tasks.containsKey(key)) {
          if (tasks.isEmpty()) startAsForegroundSummary()

          val notificationHandler = NotificationHandler(
            context = this,
            notificationManager = nm,
            key = key,
            variant = variant,
            modelType = "auto" // Will be detected from file content
          )
          notificationHandlers[key] = notificationHandler

          val job = scope.launch { doImportTask(key, uri, variant, notificationHandler) }
          tasks[key] = job
        }
      }
      ACTION_CANCEL -> {
        val serializedKey = intent.getStringExtra(EXTRA_KEY) ?: return START_NOT_STICKY
        val key = DownloadKey.fromSerializedKey(serializedKey)

        tasks.remove(key)?.cancel()
        // 由各自 handler 决定文案
        notificationHandlers[key]?.notifyFailed(
          notificationHandlers[key]?.getFailedText() ?: getString(R.string.sv_download_status_failed)
        )
        notificationHandlers.remove(key)
      }
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    try {
      scope.cancel()
    } catch (e: Throwable) {
      Log.w(TAG, "Error cancelling scope in onDestroy", e)
    }
  }

  private fun startAsForegroundSummary() {
    val notif = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.notif_model_summary_title))
      .setContentText(getString(R.string.notif_model_summary_text))
      .setSmallIcon(R.drawable.cloud_arrow_down)
      .setGroup(GROUP_ID)
      .setOngoing(true)
      .build()
    startForeground(SUMMARY_ID, notif)
  }

  private suspend fun doDownloadTask(
    key: DownloadKey,
    url: String,
    variant: String,
    modelType: String,
    notificationHandler: NotificationHandler
  ) {
    // 仅支持 .zip 下载源；非 .zip 直接报错（提示更新下载链接）
    val cacheFile = File(cacheDir, key.toSafeFileName() + ".zip")

    try {
      if (!url.lowercase().substringBefore('#').substringBefore('?').endsWith(".zip")) {
        throw IllegalArgumentException(getString(R.string.error_only_zip_supported))
      }

      // 下载文件
      downloadFile(url, cacheFile, notificationHandler)

      // 解压归档
      val modelDir = extractArchive(cacheFile, key, variant, modelType, notificationHandler)

      // 验证并安装模型
      verifyAndInstallModel(modelDir, variant, modelType)

      val doneText = when (modelType) {
        "paraformer" -> getString(R.string.pf_download_status_done)
        "zipformer" -> getString(R.string.zf_download_status_done)
        "telespeech" -> getString(R.string.ts_download_status_done)
        else -> getString(R.string.sv_download_status_done)
      }
      notificationHandler.notifySuccess(doneText)
    } catch (t: Throwable) {
      Log.e(TAG, "Download task failed for key=$key, url=$url", t)
      val onlyZipMsg = getString(R.string.error_only_zip_supported)
      val failText = when {
        t.message == onlyZipMsg -> onlyZipMsg
        modelType == "paraformer" -> getString(R.string.pf_download_status_failed)
        modelType == "zipformer" -> getString(R.string.zf_download_status_failed)
        modelType == "telespeech" -> getString(R.string.ts_download_status_failed)
        else -> getString(R.string.sv_download_status_failed)
      }
      notificationHandler.notifyFailed(failText)
    } finally {
      tasks.remove(key)
      notificationHandlers.remove(key)

      // 若无任务，结束前台与自身
      if (tasks.isEmpty()) {
        try {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
          Log.w(TAG, "Error stopping foreground in finally", e)
        }
        stopSelf()
      }
      try {
        cacheFile.delete()
      } catch (e: Throwable) {
        Log.w(TAG, "Error deleting cache file: ${cacheFile.path}", e)
      }
    }
  }

  /**
   * 从本地文件导入模型
   */
  private suspend fun doImportTask(
    key: DownloadKey,
    uri: android.net.Uri,
    variant: String,
    notificationHandler: NotificationHandler
  ) {
    // 仅支持 .zip 导入：先根据显示名或路径判断并在服务侧再次校验
    val cacheFile = File(cacheDir, key.toSafeFileName() + ".zip")

    try {
      val displayName = getDisplayNameFromUri(uri) ?: uri.lastPathSegment ?: ""
      if (!displayName.lowercase().endsWith(".zip")) {
        throw IllegalArgumentException(getString(R.string.error_only_zip_supported))
      }

      // 从 Uri 复制文件到缓存目录
      copyFileFromUri(uri, cacheFile, notificationHandler)

      // 通过压缩包文件名精准识别模型类型与变体
      val typeAndVariant = detectModelTypeAndVariantFromFileName(displayName)
        ?: throw IllegalStateException(getString(R.string.sv_import_failed, "无法识别模型类型"))
      val modelType = typeAndVariant.first
      val detectedVariant = typeAndVariant.second

      // 更新通知处理器（类型与变体），以便文案准确：
      // Paraformer/Zipformer(bi*) 包含双量化，保持用户当前变体文案；SenseVoice 与 Zip zh* 精确展示
      notificationHandler.updateModelType(modelType)
      val shouldUpdateVariant = when (modelType) {
        "sensevoice" -> true
        "zipformer" -> detectedVariant.startsWith("zh")
        else -> false // paraformer 保持用户选择
      }
      if (shouldUpdateVariant) notificationHandler.updateVariant(detectedVariant)

      // 同步首选项中的变体，确保后续加载/校验路径一致
      try {
        val prefs = Prefs(this@ModelDownloadService)
        when (modelType) {
          // SenseVoice：二选一，直接同步用户选择
          "sensevoice" -> prefs.svModelVariant = detectedVariant
          // TeleSpeech：离线 CTC，int8/full 二选一，直接同步用户选择
          "telespeech" -> prefs.tsModelVariant = detectedVariant
          // Paraformer：单包含 int8+fp32，两者均可用，不覆盖用户当前偏好
          "paraformer" -> { /* no-op */ }
          // Zipformer：zh/zh-xl 为单量化包，同步；bilingual 系列包含双量化，不覆盖
          "zipformer" -> if (detectedVariant.startsWith("zh")) prefs.zfModelVariant = detectedVariant
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to persist detected variant: $detectedVariant for $modelType", e)
      }

      // 解压归档（仅支持 ZIP）
      val installVariant = if (modelType == "zipformer" && !detectedVariant.startsWith("zh")) variant else detectedVariant
      val modelDir = extractArchive(cacheFile, key, installVariant, modelType, notificationHandler)

      // 验证并安装模型（使用检测到的变体决定最终安装路径）
      verifyAndInstallModel(modelDir, installVariant, modelType)

      // 构造成功消息
      val modelInfo = getModelInfo(modelType, installVariant)
      val successMessage = getString(R.string.sv_import_success, modelInfo)
      notificationHandler.notifySuccess(successMessage)
    } catch (t: Throwable) {
      Log.e(TAG, "Import task failed for key=$key, uri=$uri", t)
      val errorMessage = t.message ?: "Unknown error"
      val failMessage = getString(R.string.sv_import_failed, errorMessage)
      notificationHandler.notifyFailed(failMessage)
    } finally {
      tasks.remove(key)
      notificationHandlers.remove(key)

      // 若无任务，结束前台与自身
      if (tasks.isEmpty()) {
        try {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
          Log.w(TAG, "Error stopping foreground in finally", e)
        }
        stopSelf()
      }
      try {
        cacheFile.delete()
      } catch (e: Throwable) {
        Log.w(TAG, "Error deleting cache file: ${cacheFile.path}", e)
      }
    }
  }

  /**
   * 从 Uri 复制文件到缓存目录
   */
  private suspend fun copyFileFromUri(
    uri: android.net.Uri,
    destFile: File,
    notificationHandler: NotificationHandler
  ) = withContext(Dispatchers.IO) {
    Log.d(TAG, "Copying file from URI: $uri")

    contentResolver.openInputStream(uri)?.use { input ->
      val total = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
      } catch (e: Exception) {
        0L
      }

      FileOutputStream(destFile).use { output ->
        val buf = ByteArray(128 * 1024)
        var readSum = 0L
        var lastProgress = -1

        while (true) {
          val n = input.read(buf)
          if (n <= 0) break

          output.write(buf, 0, n)
          readSum += n

          if (total > 0) {
            val progress = ((readSum * 100) / total).toInt()
            if (progress != lastProgress) {
              val cancelIntent = notificationHandler.createCancelIntent()
              notificationHandler.notifyExtractProgress(progress, cancelIntent)
              lastProgress = progress
            }
          }
        }
      }
    } ?: throw IllegalStateException("无法打开文件")

    Log.d(TAG, "File copied successfully: ${destFile.length()} bytes")
  }

  /**
   * 根据压缩包文件名识别模型类型（不解压内容）
   */
  private fun detectModelTypeFromFileName(name: String): String? {
    val n = name.lowercase()
    return when {
      n.contains("paraformer") -> "paraformer"
      n.contains("zipformer") -> "zipformer"
      n.contains("telespeech") -> "telespeech"
      n.contains("sense-voice") || n.contains("sensevoice") -> "sensevoice"
      else -> null
    }
  }

  /**
   * 基于压缩包“完整文件名”精准识别模型类型与应用内变体编码
   * 要求：文件名在转换为 .zip 时不改主名，仅改后缀
   */
  private fun detectModelTypeAndVariantFromFileName(name: String): Pair<String, String>? {
    val base = name.substringAfterLast('/')
      .substringBeforeLast('.')
      .lowercase()
    return when (base) {
      // SenseVoice
      "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17" -> "sensevoice" to "small-full"
      "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17" -> "sensevoice" to "small-int8"

      // Paraformer（主包名不含量化，默认按 int8 归类）
      "sherpa-onnx-streaming-paraformer-bilingual-zh-en" -> "paraformer" to "bilingual-int8"
      "sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en" -> "paraformer" to "trilingual-int8"

      // TeleSpeech（离线 CTC）
      "sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04" -> "telespeech" to "int8"
      "sherpa-onnx-telespeech-ctc-zh-2024-06-04" -> "telespeech" to "full"

      // Zipformer（精确到量化/日期）
      "sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30" -> "zipformer" to "zh-xl-int8-20250630"
      "sherpa-onnx-streaming-zipformer-zh-xlarge-fp16-2025-06-30" -> "zipformer" to "zh-xl-fp16-20250630"
      "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30" -> "zipformer" to "zh-int8-20250630"
      "sherpa-onnx-streaming-zipformer-zh-fp16-2025-06-30" -> "zipformer" to "zh-fp16-20250630"
      "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20" -> "zipformer" to "bi-20230220-int8"
      "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16" -> "zipformer" to "bi-small-20230216-int8"

      else -> null
    }
  }

  /**
   * 获取模型信息字符串
   */
  private fun getModelInfo(modelType: String, variant: String): String {
    return when (modelType) {
      "sensevoice" -> {
        val versionName = if (variant == "small-full") "Small (fp32)" else "Small (int8)"
        "SenseVoice $versionName"
      }
      "telespeech" -> {
        val versionName = if (variant == "full") "Zh (fp32)" else "Zh (int8)"
        "TeleSpeech $versionName"
      }
      "paraformer" -> {
        val versionName = when (variant) {
          "bilingual" -> "双语版"
          "trilingual" -> "三语版"
          else -> variant
        }
        "Paraformer $versionName"
      }
      "zipformer" -> {
        val versionName = when {
          variant.contains("bilingual") -> "双语版"
          variant.contains("zh") -> "中文版"
          else -> variant
        }
        "Zipformer $versionName"
      }
      else -> "$modelType $variant"
    }
  }

  /**
   * 下载文件到本地缓存
   */
  private suspend fun downloadFile(
    url: String,
    cacheFile: File,
    notificationHandler: NotificationHandler
  ) = withContext(Dispatchers.IO) {
    Log.d(TAG, "Starting download from: $url")

    val cancelIntent = notificationHandler.createCancelIntent()
    notificationHandler.notifyDownloadProgress(0, cancelIntent)

    val ok = OkHttpClient()
    val req = Request.Builder().url(url).build()
    val call = ok.newCall(req)

    try {
      call.execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IllegalStateException("HTTP ${resp.code}")
        }

        val body = resp.body ?: throw IllegalStateException("empty body")
        val total = body.contentLength()

        cacheFile.outputStream().use { out ->
          var readSum = 0L
          val buf = ByteArray(128 * 1024)

          body.byteStream().use { ins ->
            while (true) {
              if (!coroutineContext.isActive) {
                call.cancel()
                throw CancellationException("Download cancelled")
              }

              val n = ins.read(buf)
              if (n <= 0) break

              out.write(buf, 0, n)
              readSum += n

              if (total > 0L) {
                val progress = ((readSum * 100) / total).toInt().coerceIn(0, 100)
                notificationHandler.notifyDownloadProgress(progress, cancelIntent)
              }
            }
          }
        }
      }
    } finally {
      if (!coroutineContext.isActive) {
        call.cancel()
      }
    }

    Log.d(TAG, "Download completed: ${cacheFile.path}")
  }

  /**
   * 解压归档文件到临时目录
   * @return 模型所在的目录
   */
  private suspend fun extractArchive(
    cacheFile: File,
    key: DownloadKey,
    variant: String,
    modelType: String,
    notificationHandler: NotificationHandler
  ): File {
    Log.d(TAG, "Starting extraction for variant: $variant")
    // 解压前准备通知/目录

    // 输出目录
    val base = getExternalFilesDir(null) ?: filesDir
    val outRoot = when (modelType) {
      "paraformer" -> File(base, "paraformer")
      "zipformer" -> File(base, "zipformer")
      "telespeech" -> File(base, "telespeech")
      else -> File(base, "sensevoice")
    }
    val tmpDir = File(outRoot, ".tmp_extract_${key.toSafeFileName()}_${System.currentTimeMillis()}")

    if (tmpDir.exists()) {
      tmpDir.deleteRecursively()
    }
    tmpDir.mkdirs()

    val cancelIntent = notificationHandler.createCancelIntent()
    val compressedTotal = cacheFile.length()
    notificationHandler.notifyExtractProgressImmediate(0, cancelIntent)
    if (detectArchiveType(cacheFile) != ArchiveType.ZIP) {
      throw IllegalStateException(getString(R.string.error_only_zip_supported))
    }
    extractZipWithCompressedProgress(cacheFile, tmpDir, compressedTotal) { percent ->
      notificationHandler.notifyExtractProgress(percent, cancelIntent)
    }

    Log.d(TAG, "Extraction completed to: ${tmpDir.path}")
    return tmpDir
  }

  /**
   * 验证模型文件并安装到最终目录
   */
  private suspend fun verifyAndInstallModel(
    tmpDir: File,
    variant: String,
    modelType: String
  ) = withContext(Dispatchers.IO) {
    Log.d(TAG, "Verifying model files for variant: $variant")

    // 校验并定位模型目录
    val modelDir = findModelDir(tmpDir)
    if (modelDir == null) throw IllegalStateException("model dir not found")
    val tokens = File(modelDir, "tokens.txt")
    if (!tokens.exists()) throw IllegalStateException("tokens.txt missing")

    if (modelType == "paraformer") {
      val encInt8 = File(modelDir, "encoder.int8.onnx")
      val decInt8 = File(modelDir, "decoder.int8.onnx")
      val encF32 = File(modelDir, "encoder.onnx")
      val decF32 = File(modelDir, "decoder.onnx")
      if (!((encInt8.exists() && decInt8.exists()) || (encF32.exists() && decF32.exists()))) {
        throw IllegalStateException("paraformer files missing after extract")
      }
    } else if (modelType == "zipformer") {
      val files = modelDir.listFiles() ?: emptyArray()
      fun exists(regex: Regex): Boolean = files.any { it.isFile && regex.matches(it.name) }
      val hasTokens = File(modelDir, "tokens.txt").exists()
      val hasEncoder = exists(Regex("^encoder(?:[.-].*)?\\.onnx$"))
      val hasDecoder = exists(Regex("^decoder(?:[.-].*)?\\.onnx$"))
      val hasJoiner = exists(Regex("^joiner(?:[.-].*)?\\.onnx$"))
      if (!(hasTokens && hasEncoder && hasDecoder && hasJoiner)) {
        throw IllegalStateException("zipformer files missing after extract (pattern)")
      }
    } else if (modelType == "telespeech") {
      if (!(File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())) {
        throw IllegalStateException("telespeech files missing after extract")
      }
    } else {
      if (!(File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())) {
        throw IllegalStateException("sensevoice files missing after extract")
      }
    }

    Log.d(TAG, "Model files verified, installing to final location")

    // 确定最终输出目录
    val base = getExternalFilesDir(null) ?: filesDir
    val outFinal = when (modelType) {
      "paraformer" -> {
        val outRoot = File(base, "paraformer")
        val group = if (variant.startsWith("trilingual")) "trilingual" else "bilingual"
        File(outRoot, group)
      }
      "zipformer" -> {
        val outRoot = File(base, "zipformer")
        val groupDir = when {
          variant.startsWith("zh-xl-") -> "zh-xlarge-2025-06-30"
          variant.startsWith("zh-") -> "zh-2025-06-30"
          variant.startsWith("bi-small-") -> "small-bilingual-zh-en-2023-02-16"
          else -> "bilingual-zh-en-2023-02-20"
        }
        File(outRoot, groupDir)
      }
      "telespeech" -> {
        val outRoot = File(base, "telespeech")
        if (variant == "full") File(outRoot, "full") else File(outRoot, "int8")
      }
      else -> {
        val outRoot = File(base, "sensevoice")
        if (variant == "small-full") File(outRoot, "small-full") else File(outRoot, "small-int8")
      }
    }

    // 原子替换
    if (outFinal.exists()) {
      outFinal.deleteRecursively()
    }

    val renamed = tmpDir.renameTo(outFinal)
    if (!renamed) {
      Log.w(TAG, "Direct rename failed, falling back to recursive copy")
      copyDirRecursively(tmpDir, outFinal)
    }

    try {
      tmpDir.deleteRecursively()
    } catch (e: Throwable) {
      Log.w(TAG, "Error deleting temp directory: ${tmpDir.path}", e)
    }

    Log.d(TAG, "Model installation completed: ${outFinal.path}")
  }

  private fun ensureChannel() {
    val ch = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.notif_channel_model_download),
      NotificationManager.IMPORTANCE_LOW
    )
    ch.description = getString(R.string.notif_channel_model_download_desc)
    nm.createNotificationChannel(ch)
  }

  // --- 解压与文件工具 ---
  

  private class CountingInputStream(private val input: java.io.InputStream) : java.io.InputStream() {
    @Volatile var bytesRead: Long = 0
      private set
    override fun read(): Int {
      val r = input.read()
      if (r >= 0) bytesRead++
      return r
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val n = input.read(b, off, len)
      if (n > 0) bytesRead += n
      return n
    }
    override fun close() = input.close()
    override fun available(): Int = input.available()
    override fun markSupported(): Boolean = input.markSupported()
    override fun mark(readlimit: Int) = input.mark(readlimit)
    override fun reset() = input.reset()
  }

  private enum class ArchiveType { ZIP, UNKNOWN }

  private fun detectArchiveType(file: File): ArchiveType {
    return try {
      file.inputStream().use { ins ->
        val header = ByteArray(4)
        val n = ins.read(header)
        if (n >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) { // PK
          ArchiveType.ZIP
        } else ArchiveType.UNKNOWN
      }
    } catch (e: Throwable) {
      Log.w(TAG, "detectArchiveType failed", e)
      ArchiveType.UNKNOWN
    }
  }


  private suspend fun extractZipWithCompressedProgress(
    file: File,
    outDir: File,
    compressedTotal: Long,
    onProgress: (Int) -> Unit
  ) = withContext(Dispatchers.IO) {
    val ctx = coroutineContext
    val counting = CountingInputStream(file.inputStream().buffered(64 * 1024))
    ZipInputStream(counting).use { zis ->
      val buf = ByteArray(64 * 1024)
      var entry: ZipEntry? = zis.nextEntry
      var lastPercent = -1
      while (entry != null) {
        if (!ctx.isActive) {
          throw CancellationException("Extraction cancelled")
        }
        val outFile = File(outDir, entry.name)
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          java.io.BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { bos ->
            var written = 0L
            while (true) {
              if (!ctx.isActive) {
                throw CancellationException("Extraction cancelled")
              }
              val n = zis.read(buf)
              if (n <= 0) break
              bos.write(buf, 0, n)
              written += n
              if (compressedTotal > 0L) {
                val percent = ((counting.bytesRead * 100) / compressedTotal).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                  lastPercent = percent
                  onProgress(percent)
                }
              }
            }
            bos.flush()
          }
        }
        // CRC 校验在 closeEntry 时由 ZipInputStream 执行；若失败会抛异常
        zis.closeEntry()
        entry = zis.nextEntry
      }
      // 结束时确保进度到 100%
      if (ctx.isActive) {
        onProgress(100)
      }
    }
  }

  private suspend fun copyDirRecursively(src: File, dst: File) {
    withContext(Dispatchers.IO) { copyDirRecursivelyInternal(src, dst) }
  }

  private fun copyDirRecursivelyInternal(src: File, dst: File) {
    if (!src.exists()) return
    if (src.isDirectory) {
      if (!dst.exists()) dst.mkdirs()
      src.listFiles()?.forEach { child ->
        val target = File(dst, child.name)
        if (child.isDirectory) {
          copyDirRecursivelyInternal(child, target)
        } else {
          target.parentFile?.mkdirs()
          child.inputStream().use { ins ->
            java.io.BufferedOutputStream(FileOutputStream(target), 64 * 1024).use { bos ->
              val buf = ByteArray(64 * 1024)
              while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
              }
              bos.flush()
            }
          }
        }
      }
    } else {
      dst.parentFile?.mkdirs()
      src.inputStream().use { ins ->
        java.io.BufferedOutputStream(FileOutputStream(dst), 64 * 1024).use { bos ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
          }
          bos.flush()
        }
      }
    }
  }

  private fun findModelDir(root: File): File? {
    if (!root.exists()) return null
    val direct = File(root, "tokens.txt")
    if (direct.exists()) return root
    val subs = root.listFiles() ?: return null
    subs.forEach { f ->
      if (f.isDirectory) {
        val t = File(f, "tokens.txt")
        if (t.exists()) return f
      }
    }
    return null
  }

  private fun getDisplayNameFromUri(uri: android.net.Uri): String? {
    return try {
      val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
      contentResolver.query(uri, projection, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
      }
    } catch (e: Throwable) {
      Log.w(TAG, "Failed to get display name from uri: $uri", e)
      null
    }
  }
}

/**
 * 下载任务的唯一标识符
 * 使用数据类替代字符串拼接，提供类型安全和更好的可读性
 */
data class DownloadKey(
  val variant: String,
  val url: String
) {
  companion object {
    private const val SEPARATOR = "|"
    private const val MAX_LENGTH = 200

    fun fromSerializedKey(serialized: String): DownloadKey {
      val parts = serialized.split(SEPARATOR, limit = 2)
      return if (parts.size == 2) {
        DownloadKey(parts[0], parts[1])
      } else {
        DownloadKey("", serialized)
      }
    }
  }

  fun toSerializedKey(): String {
    return (variant + SEPARATOR + url).take(MAX_LENGTH)
  }

  fun toSafeFileName(): String {
    return toSerializedKey().replace("[^A-Za-z0-9._-]".toRegex(), "_")
  }

  fun notifIdForKey(): Int {
    return 2000 + (toSerializedKey().hashCode() and 0x7fffffff) % 100000
  }
}

/**
 * 封装通知逻辑的处理器
 * 负责管理单个下载任务的通知状态，包括节流、进度更新和完成状态
 */
class NotificationHandler(
  private val context: Context,
  private val notificationManager: NotificationManager,
  val key: DownloadKey,
  private var variant: String,
  private var modelType: String
) {
  companion object {
    private const val THROTTLE_INTERVAL_MS = 500L
    private const val CHANNEL_ID = "model_download"
    private const val GROUP_ID = "model_download_group"
  }

  private var lastProgress: Int = -1
  private var lastNotifyTime: Long = 0L

  private val notifId: Int = key.notifIdForKey()
  private var title: String = getTitleForVariant()

  /**
   * 更新模型类型（用于导入时自动检测）
   */
  fun updateModelType(newModelType: String) {
    modelType = newModelType
    title = getTitleForVariant()
  }

  /** 更新变体编码（用于导入时根据文件名精准识别） */
  fun updateVariant(newVariant: String) {
    variant = newVariant
    title = getTitleForVariant()
  }

  /**
   * 创建取消下载的 PendingIntent
   */
  fun createCancelIntent(): PendingIntent {
    return PendingIntent.getService(
      context,
      key.hashCode(),
      Intent(context, ModelDownloadService::class.java).apply {
        action = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_CANCEL"
        putExtra("key", key.toSerializedKey())
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  /**
   * 通知下载进度（带节流）
   */
  fun notifyDownloadProgress(progress: Int, cancelIntent: PendingIntent) {
    val text = when (modelType) {
      "paraformer" -> context.getString(R.string.pf_download_status_downloading, progress)
      "zipformer" -> context.getString(R.string.zf_download_status_downloading, progress)
      else -> context.getString(R.string.sv_download_status_downloading, progress)
    }
    notifyProgress(
      progress = progress,
      text = text,
      indeterminate = false,
      ongoing = true,
      done = false,
      action = cancelIntent,
      throttle = true
    )
  }


  /**
   * 通知解压进度（带百分比与节流）
   */
  fun notifyExtractProgress(progress: Int, cancelIntent: PendingIntent) {
    val text = when (modelType) {
      "paraformer" -> context.getString(R.string.pf_download_status_extracting_progress, progress)
      "zipformer" -> context.getString(R.string.zf_download_status_extracting_progress, progress)
      else -> context.getString(R.string.sv_download_status_extracting_progress, progress)
    }
    notifyProgress(
      progress = progress,
      text = text,
      indeterminate = false,
      ongoing = true,
      done = false,
      action = cancelIntent,
      throttle = true
    )
  }

  /**
   * 立即切换为确定型解压进度（不节流），用于首次从转圈切换为0%
   */
  fun notifyExtractProgressImmediate(progress: Int, cancelIntent: PendingIntent) {
    val text = when (modelType) {
      "paraformer" -> context.getString(R.string.pf_download_status_extracting_progress, progress)
      "zipformer" -> context.getString(R.string.zf_download_status_extracting_progress, progress)
      else -> context.getString(R.string.sv_download_status_extracting_progress, progress)
    }
    notifyProgress(
      progress = progress,
      text = text,
      indeterminate = false,
      ongoing = true,
      done = false,
      action = cancelIntent,
      throttle = false,
      force = true
    )
  }

  /**
   * 通知下载成功
   */
  fun notifySuccess(text: String) {
    notifyProgress(
      progress = 100,
      text = text,
      indeterminate = false,
      ongoing = false,
      done = true,
      throttle = false,
      force = true
    )
  }

  /**
   * 通知下载失败
   */
  fun notifyFailed(text: String) {
    notifyProgress(
      progress = 0,
      text = text,
      indeterminate = false,
      ongoing = false,
      done = true,
      throttle = false,
      force = true
    )
  }

  fun getFailedText(): String {
    return when (modelType) {
      "paraformer" -> context.getString(R.string.pf_download_status_failed)
      "zipformer" -> context.getString(R.string.zf_download_status_failed)
      "telespeech" -> context.getString(R.string.ts_download_status_failed)
      else -> context.getString(R.string.sv_download_status_failed)
    }
  }

  private fun notifyProgress(
    progress: Int,
    text: String,
    indeterminate: Boolean = false,
    ongoing: Boolean = false,
    done: Boolean = false,
    action: PendingIntent? = null,
    throttle: Boolean = false,
    force: Boolean = false
  ) {
    // 节流：避免高频刷新被系统丢弃
    if (!force && throttle) {
      if (shouldThrottle(progress, indeterminate)) {
        return
      }
    }

    updateThrottleState(progress)

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.cloud_arrow_down)
      .setContentTitle(title)
      .setContentText(text)
      .setOnlyAlertOnce(true)
      .setGroup(GROUP_ID)
      .setOngoing(ongoing && !done)

    if (!done) {
      builder.setProgress(100, if (indeterminate) 0 else progress, indeterminate)
      action?.let {
        builder.addAction(0, context.getString(R.string.btn_cancel), it)
      }
    } else {
      builder.setProgress(0, 0, false)
    }

    // 点击跳转设置页
    val pi = PendingIntent.getActivity(
      context,
      key.hashCode() + 1,
      Intent(context, SettingsActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    builder.setContentIntent(pi)

    notificationManager.notify(notifId, builder.build())
  }

  private fun shouldThrottle(progress: Int, indeterminate: Boolean): Boolean {
    val now = System.currentTimeMillis()

    // 进度未变化且非不定进度，直接丢弃
    if (progress == lastProgress && !indeterminate) {
      return true
    }

    // 距离上次通知小于阈值，丢弃
    if (now - lastNotifyTime < THROTTLE_INTERVAL_MS) {
      return true
    }

    return false
  }

  private fun updateThrottleState(progress: Int) {
    lastProgress = progress
    lastNotifyTime = System.currentTimeMillis()
  }

  private fun getTitleForVariant(): String {
    return when (modelType) {
      "paraformer" -> {
        if (variant.startsWith("trilingual"))
          context.getString(R.string.notif_pf_title_trilingual)
        else
          context.getString(R.string.notif_pf_title_bilingual)
      }
      "zipformer" -> {
        when {
          variant.startsWith("zh-xl-") -> context.getString(R.string.notif_zf_title_zh_xl)
          variant.startsWith("zh-") -> context.getString(R.string.notif_zf_title_zh)
          variant.startsWith("bi-small-") -> context.getString(R.string.notif_zf_title_small_bi)
          else -> context.getString(R.string.notif_zf_title_bi)
        }
      }
      "telespeech" -> {
        if (variant == "full") context.getString(R.string.notif_ts_title_full)
        else context.getString(R.string.notif_ts_title_int8)
      }
      else -> {
        when (variant) {
          "small-full" -> context.getString(R.string.notif_model_title_full)
          else -> context.getString(R.string.notif_model_title_int8)
        }
      }
    }
  }
}
