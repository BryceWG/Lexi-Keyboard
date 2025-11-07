package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.common.ResultCallback
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DashScope Fun-ASR 实时流式引擎（SDK）。
 *
 * - 使用 Recognition + ResultCallback 实现，避免自定义 WS 协议。
 * - 每 ~100ms 发送一帧 PCM（16kHz/16bit/mono）。
 * - 开启 heartbeat 以在长静音下维持连接（SDK 侧支持）。
 * - 注意：fun-asr 不支持 language_hints/context 等参数，这些不会传递。
 */
class DashscopeStreamAsrEngine(
  private val context: Context,
  private val scope: CoroutineScope,
  private val prefs: Prefs,
  private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

  companion object {
    private const val TAG = "DashscopeStreamAsrEngine"
  }

  private val running = AtomicBoolean(false)
  private var audioJob: Job? = null
  private var controlJob: Job? = null

  private val sampleRate = 16000
  private val channelConfig = AudioFormat.CHANNEL_IN_MONO
  private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

  private var recognizer: Recognition? = null
  private val finalBuffer = StringBuilder()
  private var currentSentence: String = ""

  override val isRunning: Boolean
    get() = running.get()

  override fun start() {
    if (running.get()) return

    val hasPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) {
      listener.onError(context.getString(R.string.error_record_permission_denied))
      return
    }
    if (prefs.dashApiKey.isBlank()) {
      listener.onError(context.getString(R.string.error_missing_dashscope_key))
      return
    }

    running.set(true)
    finalBuffer.setLength(0)
    currentSentence = ""

    // 在 IO 线程启动 SDK 识别并随后启动采集
    controlJob?.cancel()
    controlJob = scope.launch(Dispatchers.IO) {
      try {
        val param = RecognitionParam.builder()
          .apiKey(prefs.dashApiKey)
          .model("fun-asr-realtime")
          .format("pcm")
          .sampleRate(sampleRate)
          // SDK 文档建议：长连接场景开启心跳。
          .parameter("heartbeat", true)
          .build()

        val cb = object : ResultCallback<RecognitionResult>() {
          override fun onEvent(result: RecognitionResult) {
            val text = try { result.sentence.text ?: "" } catch (_: Throwable) { "" }
            if (text.isNotEmpty() && running.get()) {
              if (result.isSentenceEnd()) {
                // 一句完成：写入最终缓存，清空当前句
                currentSentence = text
                finalBuffer.append(currentSentence)
                currentSentence = ""
                // 作为“中间/最终段落”也推送给 UI 预览
                try { listener.onPartial(finalBuffer.toString()) } catch (t: Throwable) { Log.e(TAG, "notify partial failed", t) }
              } else {
                // 中间结果：更新当前句并合成预览
                currentSentence = text
                try { listener.onPartial((finalBuffer.toString() + currentSentence)) } catch (t: Throwable) { Log.e(TAG, "notify partial failed", t) }
              }
            }
          }

          override fun onComplete() {
            val finalText = (finalBuffer.toString() + currentSentence).trim()
            try { listener.onFinal(finalText) } catch (t: Throwable) { Log.e(TAG, "notify final failed", t) }
            running.set(false)
          }

          override fun onError(e: Exception) {
            Log.e(TAG, "Fun-ASR stream error: ${e.message}", e)
            try { listener.onError(context.getString(R.string.error_recognize_failed_with_reason, e.message ?: "")) } catch (_: Throwable) {}
            running.set(false)
          }
        }

        val rec = Recognition()
        recognizer = rec
        rec.call(param, cb)

        // 建立连接后开始推送音频
        startCaptureAndSend(rec)
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to start Fun-ASR recognition", t)
        try { listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")) } catch (_: Throwable) {}
        running.set(false)
        safeClose()
      }
    }
  }

  override fun stop() {
    if (!running.get()) return
    running.set(false)

    try { audioJob?.cancel() } catch (t: Throwable) { Log.w(TAG, "cancel audio job", t) }
    audioJob = null

    scope.launch(Dispatchers.IO) {
      try {
        // 通知 UI：录音阶段结束，可复位麦克风按钮
        try { listener.onStopped() } catch (t: Throwable) { Log.e(TAG, "notify stopped failed", t) }
        // SDK 的 stop 会阻塞到 onComplete / onError
        recognizer?.stop()
      } catch (t: Throwable) {
        Log.w(TAG, "recognizer.stop() failed", t)
      } finally {
        safeClose()
      }
    }
  }

  private fun startCaptureAndSend(rec: Recognition) {
    audioJob?.cancel()
    audioJob = scope.launch(Dispatchers.IO) {
      val chunkMillis = 100 // 建议 100ms 左右
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
        audioManager.startCapture().collect { audioChunk ->
          if (!running.get()) return@collect

          // Calculate and send audio amplitude (for waveform animation)
          try {
            val amplitude = com.brycewg.asrkb.asr.calculateNormalizedAmplitude(audioChunk)
            listener.onAmplitude(amplitude)
          } catch (t: Throwable) {
            Log.w(TAG, "Failed to calculate amplitude", t)
          }

          if (vadDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
            Log.d(TAG, "Silence detected, stopping recording")
            try { listener.onStopped() } catch (t: Throwable) { Log.e(TAG, "notify stopped failed", t) }
            try { recognizer?.stop() } catch (t: Throwable) { Log.w(TAG, "stop after VAD failed", t) }
            return@collect
          }

          try {
            val buf = ByteBuffer.wrap(audioChunk)
            rec.sendAudioFrame(buf)
          } catch (t: Throwable) {
            Log.e(TAG, "sendAudioFrame failed", t)
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

  private fun safeClose() {
    try {
      recognizer?.duplexApi?.close(1000, "bye")
    } catch (t: Throwable) {
      Log.w(TAG, "duplex close failed", t)
    } finally {
      recognizer = null
    }
  }
}
