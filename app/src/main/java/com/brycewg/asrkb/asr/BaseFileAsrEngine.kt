package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * 基础的文件识别 ASR 引擎，封装了麦克风采集、静音判停等通用逻辑，
 * 子类只需实现具体的识别请求即可。
 */
abstract class BaseFileAsrEngine(
    protected val context: Context,
    private val scope: CoroutineScope,
    protected val prefs: Prefs,
    protected val listener: StreamingAsrEngine.Listener,
    protected val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "BaseFileAsrEngine"
    }

    private val running = AtomicBoolean(false)
    @Volatile private var stopRequested: Boolean = false
    @Volatile private var stoppedDelivered: Boolean = false
    private var audioJob: Job? = null
    private var processingJob: Job? = null
    private var segmentChan: Channel<ByteArray>? = null
    private var lastSegmentForRetry: ByteArray? = null

    protected open val sampleRate: Int = 16000
    protected open val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    protected open val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    protected open val chunkMillis: Int = 200
    // 非流式录音的最大时长（子类按供应商覆盖）。
    // 达到该时长会立即结束录音并触发一次识别请求，以避免超过服务商限制。
    protected open val maxRecordDurationMillis: Int = 30 * 60 * 1000 // 默认 30 分钟

    private val bytesPerSample = 2 // 16bit mono

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        if (!ensureReady()) return
        running.set(true)
        stopRequested = false
        stoppedDelivered = false
        audioJob?.cancel()
        processingJob?.cancel()
        // 使用有界队列并在溢出时丢弃最旧的数据，避免内存溢出
        val chan = Channel<ByteArray>(
            capacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        segmentChan = chan
        // 顺序消费识别请求，确保结果按段落顺序提交
        processingJob = scope.launch(Dispatchers.IO) {
            try {
                for (seg in chan) {
                    try {
                        // 记录最近一次用于识别的片段，供“重试”功能使用
                        lastSegmentForRetry = seg
                        recognize(seg)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Recognition failed for segment", t)
                        try {
                            listener.onError(
                                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
                            )
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to notify recognition error", e)
                        }
                    }
                }
            } finally {
                processingJob = null
            }
        }
        // 持续录音并按上限切段，投递到识别队列
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                recordAndEnqueueSegments(chan)
            } finally {
                running.set(false)
                try { DebugLogManager.log("asr", "engine_run_end", mapOf("reason" to "audio_job_end")) } catch (_: Throwable) { }
                // 若录音流意外结束且未显式通知 onStopped，则补发一次，确保上层释放音频焦点与路由。
                if (!stoppedDelivered) {
                    try {
                        DebugLogManager.log("asr", "engine_stop_implied")
                    } catch (_: Throwable) { }
                    try {
                        listener.onStopped()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify implied onStopped", t)
                    } finally {
                        stoppedDelivered = true
                    }
                }
                try {
                    chan.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to close channel", t)
                }
                audioJob = null
            }
        }
    }

    override fun stop() {
        val wasRunning = running.getAndSet(false)
        stopRequested = true
        // 主动停止采集：取消录音协程以触发 finally 冲刷尾段并关闭通道
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel audio job on stop", t)
        }
        // 通知 UI 录音已结束（与静音判停一致），便于及时切换到“识别中”
        if (wasRunning) {
            try {
                listener.onStopped()
                stoppedDelivered = true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify onStopped on stop", t)
            }
        }
    }

    /**
     * 识别前的准备校验，可在子类中扩展，如检查 API Key 是否配置。
     *
     * 注意：权限检查已由 AudioCaptureManager 处理，此处保留是为了向后兼容。
     */
    protected open fun ensureReady(): Boolean {
        return true
    }

    /**
     * 连续录音并按 [maxRecordDurationMillis] 切分，将片段依次投递到 [chan]。
     *
     * 使用 AudioCaptureManager 封装音频采集逻辑，简化代码并提高可维护性。
     * - 段间不停止/重建 AudioRecord，尽量保证采集连续
     * - 仅在静音判停或用户停止时回调 onStopped()，切段不打断 UI 的"正在聆听"
     */
    private suspend fun recordAndEnqueueSegments(chan: Channel<ByteArray>) {
        val audioManager = AudioCaptureManager(
            context = context,
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat,
            chunkMillis = chunkMillis
        )

        // 权限检查
        if (!audioManager.hasPermission()) {
            Log.w(TAG, "Missing RECORD_AUDIO permission")
            try {
                listener.onError(context.getString(R.string.error_record_permission_denied))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify permission error", t)
            }
            return
        }

        // VAD 检测器（如果启用）。长按说话模式下由用户松手决定停止，绕过 VAD 自动判停
        val vadDetector = if (isVadAutoStopEnabled(context, prefs)) {
            VadDetector(
                context,
                sampleRate,
                prefs.autoStopSilenceWindowMs,
                prefs.autoStopSilenceSensitivity
            )
        } else null

        // 计算分段阈值
        val maxBytes = (maxRecordDurationMillis / 1000.0 * sampleRate * bytesPerSample).toInt()
        val currentSeg = ByteArrayOutputStream()
        val pendingList = java.util.ArrayDeque<ByteArray>()

        try {
            audioManager.startCapture().collect { audioChunk ->
                if (!running.get()) return@collect

                // 计算并发送音频振幅（用于波形动画）
                try {
                    val amplitude = calculateNormalizedAmplitude(audioChunk)
                    listener.onAmplitude(amplitude)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to calculate amplitude", t)
                }

                currentSeg.write(audioChunk)

                // VAD 自动判停：结束录音，推送最后一段
                if (vadDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
                    running.set(false)
                    Log.d(TAG, "Silence detected, stopping recording")
                    try {
                        listener.onStopped()
                        stoppedDelivered = true
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify stopped", t)
                    }

                    val last = currentSeg.toByteArray()
                    if (last.isNotEmpty()) {
                        // 刷出已有待发送
                        while (!pendingList.isEmpty()) {
                            val head = pendingList.peekFirst() ?: break
                            val ok = chan.trySend(head).isSuccess
                            if (ok) {
                                pendingList.removeFirst()
                            } else break
                        }
                        // 尝试直接投递最后一段；不成则加入待发送
                        val ok2 = chan.trySend(last).isSuccess
                        if (!ok2) {
                            pendingList.addLast(last)
                        }
                        // 已投递/入队最后一段后，重置缓冲，避免 finally 重复推送
                        currentSeg.reset()
                        Log.d(TAG, "Final segment enqueued (${last.size} bytes)")
                    }
                    // 取消录音协程，尽快退出采集循环并在 finally 中完成清理
                    try {
                        audioJob?.cancel()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to cancel audio job after silence stop", t)
                    }
                    return@collect
                }

                // 尝试非阻塞地刷出待发送片段（若存在）
                while (!pendingList.isEmpty()) {
                    val head = pendingList.peekFirst() ?: break
                    val r = chan.trySend(head)
                    if (r.isSuccess) {
                        pendingList.removeFirst()
                        Log.d(TAG, "Pending segment sent (${head.size} bytes)")
                    } else break
                }

                // 达到上限：切出一个片段，不打断录音
                if (currentSeg.size() >= maxBytes) {
                    val out = currentSeg.toByteArray()
                    currentSeg.reset()
                    Log.d(TAG, "Segment threshold reached, cutting segment (${out.size} bytes)")

                    // 先尝试刷出之前的待发送段
                    while (!pendingList.isEmpty()) {
                        val head = pendingList.peekFirst() ?: break
                        val ok = chan.trySend(head).isSuccess
                        if (ok) {
                            pendingList.removeFirst()
                        } else break
                    }

                    // 再投递当前片段；不成则加入待发送队列
                    val ok2 = chan.trySend(out).isSuccess
                    if (!ok2) {
                        pendingList.addLast(out)
                        Log.d(TAG, "Segment queued for later sending")
                    } else {
                        Log.d(TAG, "Segment sent immediately")
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Audio capture cancelled: ${t.message}")
            } else {
                Log.e(TAG, "Audio capture failed", t)
                try {
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify audio error", e)
                }
            }
        } finally {
            // 录音结束后，推送任何遗留的待发送段与缓冲
            Log.d(TAG, "Cleaning up: ${pendingList.size} pending segments, ${currentSeg.size()} bytes in buffer")
            while (!pendingList.isEmpty()) {
                try {
                    val head = pendingList.removeFirst()
                    chan.trySend(head)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to send pending segment during cleanup", t)
                    break
                }
            }
            val tail = currentSeg.toByteArray()
            if (tail.isNotEmpty()) {
                try {
                    chan.trySend(tail)
                    Log.d(TAG, "Final buffer sent (${tail.size} bytes)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to send final buffer during cleanup", t)
                }
            }

            // 释放 VAD 资源
            try {
                vadDetector?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to release VAD detector", t)
            }
        }
    }

    /**
     * 将 PCM 格式音频转换为 WAV 格式
     *
     * @param pcm PCM 音频数据
     * @return WAV 格式音频数据
     */
    protected fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val headerSize = 44
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        val out = ByteArrayOutputStream(headerSize + dataSize)
        out.write("RIFF".toByteArray())
        out.write(intToBytesLE(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToBytesLE(16))
        out.write(shortToBytesLE(1))
        out.write(shortToBytesLE(channels))
        out.write(intToBytesLE(sampleRate))
        out.write(intToBytesLE(byteRate))
        out.write(shortToBytesLE((channels * bitsPerSample / 8)))
        out.write(shortToBytesLE(bitsPerSample))
        out.write("data".toByteArray())
        out.write(intToBytesLE(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * 将整数转换为小端序字节数组（4字节）
     */
    private fun intToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v)
        return bb.array()
    }

    /**
     * 将短整数转换为小端序字节数组（2字节）
     */
    private fun shortToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(v.toShort())
        return bb.array()
    }

    /**
     * 交由子类实现具体的识别流程，如上传音频并解析结果。
     *
     * @param pcm PCM 格式音频数据
     */
    protected abstract suspend fun recognize(pcm: ByteArray)

    /**
     * 是否存在可用于重试的片段
     */
    fun hasRetryableSegment(): Boolean {
        val data = lastSegmentForRetry
        return data != null && data.isNotEmpty()
    }

    /**
     * 对最近一次片段发起重新识别（不重新录音）。
     * 该操作不会修改 running 状态；仅触发一次识别请求。
     */
    fun retryLastSegment() {
        val data = lastSegmentForRetry
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "retryLastSegment: no segment available")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                recognize(data)
            } catch (t: Throwable) {
                Log.e(TAG, "retryLastSegment recognize failed", t)
                try {
                    listener.onError(
                        context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify recognition error (retry)", e)
                }
            }
        }
    }
}
