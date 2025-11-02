package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * 音频采集管理器
 *
 * 封装 AudioRecord 的初始化、权限检查、预热和音频流读取逻辑。
 * 提供简洁的音频流接口供 ASR 引擎使用，消除重复代码。
 *
 * ## 功能特性
 * - 自动处理音频源回退（VOICE_RECOGNITION -> MIC）
 * - 预热策略：两帧探测 + 仅在确认“坏源”时回退到 MIC
 *
 * @param context Android Context
 * @param sampleRate 采样率（Hz），默认 16000
 * @param channelConfig 声道配置，默认单声道
 * @param audioFormat 音频格式，默认 PCM 16-bit
 * @param chunkMillis 每个音频块的时长（ms），默认 200ms
 */
class AudioCaptureManager(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val chunkMillis: Int = 200
) {
    private val bytesPerSample = 2 // 16bit mono PCM
    private val prefs by lazy { Prefs(context) }
    private val debugLoggingEnabled: Boolean by lazy {
        try {
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read debuggable flag", t)
            false
        }
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
    }

    /**
     * 检查录音权限
     *
     * @return 如果具有 RECORD_AUDIO 权限返回 true，否则返回 false
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 启动音频采集，返回音频数据流
     *
     * 该方法会：
     * 1. 检查录音权限
     * 2. 初始化 AudioRecord（优先 VOICE_RECOGNITION，失败时回退到 MIC）
     * 3. 执行预热逻辑（两帧小窗探测 + 仅在两帧近乎全零时回退为 MIC）
     * 4. 循环读取音频数据并通过 Flow emit
     *
     * @return Flow<ByteArray> 音频数据流，每个 ByteArray 是一个音频块（约 chunkMillis 时长）
     * @throws SecurityException 如果缺少录音权限
     * @throws IllegalStateException 如果 AudioRecord 初始化失败
     */
    fun startCapture(): Flow<ByteArray> = flow {
        try {
            DebugLogManager.log(
                category = "audio",
                event = "acm_start",
                data = mapOf(
                    "sr" to sampleRate,
                    "chunkMs" to chunkMillis
                )
            )
        } catch (_: Throwable) { }
        // 1. 权限检查
        if (!hasPermission()) {
            val error = SecurityException("Missing RECORD_AUDIO permission")
            Log.e(TAG, "Permission check failed", error)
            try { DebugLogManager.log("audio", "acm_error", mapOf("stage" to "perm", "msg" to error.message)) } catch (_: Throwable) { }
            throw error
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var scoStarted = false
        var scoWasOnBefore = false
        var audioModeChanged = false
        var previousAudioMode: Int? = null
        var commDeviceSet = false
        var commListener: Any? = null
        var preferredInputDevice: AudioDeviceInfo? = null
        var routePrepared = false

        // 1.5. 如开启“耳机麦克风优先”，在构建 AudioRecord 之前准备音频路由
        if (prefs.headsetMicPriorityEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 若已有通信设备（可能由预热设置），则不重复设置，也不在 finally 清理
                val cur = try { audioManager.getCommunicationDevice() } catch (_: Throwable) { null }
                if (cur != null && (cur.type == AudioDeviceInfo.TYPE_BLE_HEADSET || cur.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || cur.type == AudioDeviceInfo.TYPE_WIRED_HEADSET)) {
                    preferredInputDevice = cur
                    routePrepared = true
                    commDeviceSet = false
                } else {
                    val res = prepareCommunicationDevice(audioManager)
                    commDeviceSet = res.commDeviceSet
                    commListener = res.listenerToken
                    preferredInputDevice = res.selectedDevice
                    routePrepared = res.routeReady
                    if (!res.routeReady) {
                        Log.w(TAG, "Communication device set but route not confirmed within timeout")
                    }
                }
            }

            // 旧版/或现代 API 失败时：通过输入设备列表选择可用耳机，并在需要时启动 SCO
            if (preferredInputDevice == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    // 优先蓝牙（SCO/BLE），再有线耳机
                    preferredInputDevice = inputs.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        ?: (if (Build.VERSION.SDK_INT >= 34) inputs.find { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } else null)
                        ?: inputs.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }

                    if (preferredInputDevice != null) {
                        Log.i(TAG, "Preferred input device: ${preferredInputDevice!!.productName} (type=${preferredInputDevice!!.type})")
                    }

                    if (preferredInputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        // 通话模式可改善部分设备的路由与增益（仅在当前非通话模式时切换）
                        val curMode = try { audioManager.mode } catch (_: Throwable) { AudioManager.MODE_NORMAL }
                        if (curMode != AudioManager.MODE_IN_COMMUNICATION) {
                            previousAudioMode = curMode
                            try {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                                audioModeChanged = true
                            } catch (t: Throwable) {
                                Log.w(TAG, "Failed to set audio mode to IN_COMMUNICATION", t)
                            }
                        }

                        // 记录调用前是否已为 SCO，避免 finally 误停预热的 SCO
                        scoWasOnBefore = isScoOnCompat(audioManager)
                        val connected = startScoAndAwaitConnected(audioManager)
                        if (connected) {
                            scoStarted = true
                            Log.i(TAG, "Bluetooth SCO connected")
                            routePrepared = true
                        } else {
                            Log.w(TAG, "Bluetooth SCO did not connect in time; continue without SCO")
                        }
                    }
                }
            }
        }

        // 2. 计算缓冲区大小
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val chunkBytes = ((sampleRate * chunkMillis) / 1000) * bytesPerSample
        val bufferSize = maxOf(minBuffer, chunkBytes)

        // 3. 初始化 AudioRecord（优先 VOICE_RECOGNITION）
        var recorder: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord with VOICE_RECOGNITION", t)
            null
        }

        // 4. 回退到 MIC（如果 VOICE_RECOGNITION 失败：构造异常或未初始化）
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "VOICE_RECOGNITION source unavailable, falling back to MIC")
            try {
                recorder?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to release failed recorder", t)
            }
            recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create AudioRecord with MIC", t)
                null
            }
        }

        // 5. 最终校验
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            val error = IllegalStateException("AudioRecord initialization failed for both VOICE_RECOGNITION and MIC sources")
            Log.e(TAG, "AudioRecord initialization failed", error)
            throw error
        }

        var activeRecorder = recorder
        // 优先路由到选中的输入设备（若存在）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredInputDevice != null) {
            try {
                activeRecorder.preferredDevice = preferredInputDevice
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set preferred input device on AudioRecord", t)
            }
        }
        val buf = ByteArray(chunkBytes)

        try {
            // 6. 启动录音
            try {
                activeRecorder.startRecording()
                Log.d(TAG, "AudioRecord started successfully")
                try { DebugLogManager.log("audio", "recorder_started") } catch (_: Throwable) { }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException during startRecording", se)
                try { DebugLogManager.log("audio", "acm_error", mapOf("stage" to "start", "type" to "security", "msg" to se.message)) } catch (_: Throwable) { }
                throw se
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start recording", t)
                try { DebugLogManager.log("audio", "acm_error", mapOf("stage" to "start", "type" to "start_fail", "msg" to t.message)) } catch (_: Throwable) { }
                throw IllegalStateException("Failed to start recording", t)
            }

            // 7. 预热并获取可能替换的 recorder 和第一块数据
            val avoidMicFallback = prefs.headsetMicPriorityEnabled && (routePrepared || preferredInputDevice != null || scoStarted)
            val warmupResult = warmupRecorder(activeRecorder, buf, bufferSize, avoidMicFallback)
            activeRecorder = warmupResult.first
            val firstChunk = warmupResult.second

            // 8. Emit 第一块数据（如果预热产生了数据）
            if (firstChunk != null && firstChunk.isNotEmpty()) {
                emit(firstChunk)
            }

            // 9. 持续读取音频数据
            while (true) {
                val read = try {
                    activeRecorder.read(buf, 0, buf.size)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error reading audio data", t)
                    try { DebugLogManager.log("audio", "acm_error", mapOf("stage" to "read", "msg" to t.message)) } catch (_: Throwable) { }
                    throw IllegalStateException("Error reading audio data", t)
                }

                if (read > 0) {
                    // 复制有效数据并 emit
                    val chunk = buf.copyOf(read)
                    emit(chunk)
                } else if (read < 0) {
                    val error = IllegalStateException("AudioRecord read error: $read")
                    Log.e(TAG, "AudioRecord read returned error code", error)
                    throw error
                }
            }
        } finally {
            // 10. 清理资源
            try {
                activeRecorder.stop()
                Log.d(TAG, "AudioRecord stopped")
            } catch (t: Throwable) {
                Log.e(TAG, "Error stopping AudioRecord", t)
            }
            try {
                activeRecorder.release()
                Log.d(TAG, "AudioRecord released")
            } catch (t: Throwable) {
                Log.e(TAG, "Error releasing AudioRecord", t)
            }
            try {
                DebugLogManager.log(
                    category = "audio",
                    event = "acm_cleanup"
                )
            } catch (_: Throwable) { }
            // 清理通信设备与 SCO / 恢复模式
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && commDeviceSet) {
                    clearCommunicationDeviceSafely(audioManager, commListener)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed clearing communication device", t)
            }
            if (scoStarted && !scoWasOnBefore) {
                stopScoCompat(audioManager)
            }
            if (audioModeChanged && previousAudioMode != null) {
                try {
                    audioManager.mode = previousAudioMode!!
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to restore audio mode", t)
                }
            }
        }
    }

    /**
     * 预热 AudioRecord：两帧小窗探测 + 仅在确认“坏源”时回退为 MIC
     *
     * - 读取第1帧：若明显存在有效信号，直接返回该帧，避免额外时延
     * - 读取第2帧：与第1帧共同判断是否“近乎全零”
     * - 若两帧均近乎全零：停止并释放当前源，重建为 MIC 源，再读取一帧返回
     */
    private fun warmupRecorder(
        current: AudioRecord,
        buf: ByteArray,
        bufferSize: Int,
        avoidMicFallback: Boolean
    ): Pair<AudioRecord, ByteArray?> {
        if (!hasPermission()) {
            val error = SecurityException("RECORD_AUDIO permission was revoked during warmup")
            Log.e(TAG, "Permission check failed during warmup", error)
            throw error
        }

        var recorder = current
        if (debugLoggingEnabled) Log.d(TAG, "Warmup: probing 2 frames")

        // 第1帧
        val preRead1 = try {
            recorder.read(buf, 0, buf.size)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during warmup probe read #1", t)
            -1
        }
        var frame1Bytes: ByteArray? = null
        var frame1HasSignal = false
        var frame1IsNearZero = false
        if (preRead1 > 0) {
            val st1 = computeFrameStats16le(buf, preRead1, 30)
            val rms1sq = if (st1.sampleCount > 0) st1.sumSquares.toDouble() / st1.sampleCount else 0.0
            frame1HasSignal = st1.countAboveThreshold > 0
            frame1IsNearZero = (st1.maxAbs < 12 && rms1sq < 16.0 && st1.countAboveThreshold == 0)
            if (debugLoggingEnabled) {
                val rms1 = kotlin.math.sqrt(rms1sq)
                Log.d(TAG, "Warmup frame#1: read=$preRead1, max=${st1.maxAbs}, rms=${"%.1f".format(rms1)}, cnt>30=${st1.countAboveThreshold}, nearZero=$frame1IsNearZero")
            }
            if (frame1HasSignal) frame1Bytes = buf.copyOf(preRead1)
        }

        // 若第1帧已确认存在有效信号，则直接返回
        if (frame1HasSignal && frame1Bytes != null) {
            if (debugLoggingEnabled) Log.d(TAG, "Warmup: signal confirmed on frame#1, short-circuit")
            return Pair(recorder, frame1Bytes)
        }

        // 第2帧
        val preRead2 = try {
            recorder.read(buf, 0, buf.size)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during warmup probe read #2", t)
            -1
        }
        var frame2Bytes: ByteArray? = null
        var frame2HasSignal = false
        var frame2IsNearZero = false
        if (preRead2 > 0) {
            val st2 = computeFrameStats16le(buf, preRead2, 30)
            val rms2sq = if (st2.sampleCount > 0) st2.sumSquares.toDouble() / st2.sampleCount else 0.0
            frame2HasSignal = st2.countAboveThreshold > 0
            frame2IsNearZero = (st2.maxAbs < 12 && rms2sq < 16.0 && st2.countAboveThreshold == 0)
            if (debugLoggingEnabled) {
                val rms2 = kotlin.math.sqrt(rms2sq)
                Log.d(TAG, "Warmup frame#2: read=$preRead2, max=${st2.maxAbs}, rms=${"%.1f".format(rms2)}, cnt>30=${st2.countAboveThreshold}, nearZero=$frame2IsNearZero")
            }
            if (frame2HasSignal) frame2Bytes = buf.copyOf(preRead2)
        }

        val nearZeroBoth = frame1IsNearZero && frame2IsNearZero
        var firstChunk: ByteArray? = when {
            frame1HasSignal -> frame1Bytes
            frame2HasSignal -> frame2Bytes
            else -> null
        }

        if (nearZeroBoth) {
            // 若用户选择了耳机优先，并且已准备了路由（或正在使用耳机），不要贸然回退到 MIC
            if (avoidMicFallback) {
                if (debugLoggingEnabled) Log.i(TAG, "Warmup: near-zero on headset path, avoid MIC fallback; continue reading")
                return Pair(recorder, null)
            }
            // 两帧均近乎全零：重建为 MIC 源
            Log.i(TAG, "Warmup: near-zero on both frames, rebuilding with MIC source")
            try {
                recorder.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Error stopping recorder during rebuild", t)
            }
            try {
                recorder.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Error releasing recorder during rebuild", t)
            }

            val newRecorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create new AudioRecord with MIC during warmup", t)
                null
            }

            if (newRecorder == null || newRecorder.state != AudioRecord.STATE_INITIALIZED) {
                val error = IllegalStateException("Failed to rebuild AudioRecord with MIC source during warmup")
                Log.e(TAG, "AudioRecord rebuild failed", error)
                throw error
            }

            recorder = newRecorder
            try {
                recorder.startRecording()
                Log.d(TAG, "Warmup: MIC recorder started")
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException during MIC recorder start", se)
                try {
                    recorder.release()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error releasing recorder after SecurityException", t)
                }
                throw se
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MIC recorder", t)
                try {
                    recorder.release()
                } catch (releaseError: Throwable) {
                    Log.e(TAG, "Error releasing recorder after start failure", releaseError)
                }
                throw IllegalStateException("Failed to start MIC recorder", t)
            }

            // 重新读取一帧
            val pre2 = try {
                recorder.read(buf, 0, buf.size)
            } catch (t: Throwable) {
                Log.e(TAG, "Error reading from rebuilt MIC recorder", t)
                -1
            }
            if (pre2 > 0) {
                firstChunk = buf.copyOf(pre2)
                Log.d(TAG, "Warmup: MIC recorder read $pre2 bytes")
            }
        }

        return Pair(recorder, firstChunk)
    }

    // ===== 蓝牙/耳机路由辅助 =====

    /**
     * API 31+：尝试将通信设备切换到蓝牙/有线耳机，并等待回调确认。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun prepareCommunicationDevice(audioManager: AudioManager): CommRouteResult {
        var selected: AudioDeviceInfo? = null
        var listenerToken: Any? = null
        var setOk = false
        var routeReady = false
        val t0 = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L }
        try {
            val candidates = try {
                audioManager.getAvailableCommunicationDevices()
            } catch (se: SecurityException) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted or unavailable when listing comm devices", se)
                emptyList()
            } catch (t: Throwable) {
                Log.w(TAG, "getAvailableCommunicationDevices failed", t)
                emptyList()
            }

            if (candidates.isEmpty()) return CommRouteResult(false, null, null, false)

            // 选择优先级：BLE_HEADSET > BLUETOOTH_SCO > WIRED_HEADSET
            selected = candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }

            if (selected == null) return CommRouteResult(false, null, null, false)

            setOk = try {
                audioManager.setCommunicationDevice(selected)
            } catch (t: Throwable) {
                Log.w(TAG, "setCommunicationDevice failed", t)
                false
            }
            if (!setOk) return CommRouteResult(false, selected, null, false)

            val cur = try { audioManager.getCommunicationDevice() } catch (_: Throwable) { null }
            if (cur != null && selected.id == cur.id) {
                val dt = if (t0 > 0) (android.os.SystemClock.elapsedRealtime() - t0) else -1
                if (dt >= 0) Log.i(TAG, "Communication device ready immediately in ${dt}ms (id=${cur.id})")
                return CommRouteResult(true, selected, null, true)
            }

            // 等待通信设备切换
            routeReady = withTimeoutOrNull(2000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val exec = java.util.concurrent.Executor { r ->
                        try { r.run() } catch (t: Throwable) { Log.w(TAG, "CommDevice listener runnable error", t) }
                    }
                    val l = AudioManager.OnCommunicationDeviceChangedListener { dev ->
                        selected?.let { sel ->
                            if (dev != null && dev.id == sel.id) {
                                val dt = if (t0 > 0) (android.os.SystemClock.elapsedRealtime() - t0) else -1
                                if (dt >= 0) Log.i(TAG, "Communication device ready in ${dt}ms (id=${dev.id})")
                                if (cont.isActive) cont.resume(true)
                            }
                        }
                    }
                    listenerToken = l
                    try {
                        audioManager.addOnCommunicationDeviceChangedListener(exec, l)
                    } catch (t: Throwable) {
                        Log.w(TAG, "addOnCommunicationDeviceChangedListener failed", t)
                        if (cont.isActive) cont.resume(false)
                    }
                    cont.invokeOnCancellation {
                        try { audioManager.removeOnCommunicationDeviceChangedListener(l) } catch (_: Throwable) {}
                    }
                }
            } ?: false

            return CommRouteResult(true, selected, listenerToken, routeReady)
        } catch (t: Throwable) {
            Log.w(TAG, "prepareCommunicationDevice exception", t)
            return CommRouteResult(false, selected, listenerToken, false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun clearCommunicationDeviceSafely(audioManager: AudioManager, listenerToken: Any?) {
        try {
            if (listenerToken is AudioManager.OnCommunicationDeviceChangedListener) {
                try {
                    audioManager.removeOnCommunicationDeviceChangedListener(listenerToken)
                } catch (t: Throwable) {
                    Log.w(TAG, "removeOnCommunicationDeviceChangedListener failed", t)
                }
            }
            try {
                audioManager.clearCommunicationDevice()
            } catch (t: Throwable) {
                Log.w(TAG, "clearCommunicationDevice failed", t)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clearCommunicationDeviceSafely exception", t)
        }
    }

    /**
     * 旧版：启动 SCO 并等待 ACTION_SCO_AUDIO_STATE_UPDATED 变为 CONNECTED。
     */
    @Suppress("DEPRECATION")
    private suspend fun startScoAndAwaitConnected(am: AudioManager): Boolean {
        return try {
            if (!am.isBluetoothScoAvailableOffCall) {
                Log.w(TAG, "Bluetooth SCO not available off call on this device")
                return false
            }
            try {
                if (am.isBluetoothScoOn) {
                    Log.i(TAG, "Bluetooth SCO already on; treat as connected")
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Query isBluetoothScoOn failed", t)
            }

            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            var receiver: android.content.BroadcastReceiver? = null
            val t0 = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L }
            val ok = withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    receiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                            val state = intent.getIntExtra(
                                AudioManager.EXTRA_SCO_AUDIO_STATE,
                                AudioManager.SCO_AUDIO_STATE_ERROR
                            )
                            when (state) {
                                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                    val dt = if (t0 > 0) (android.os.SystemClock.elapsedRealtime() - t0) else -1
                                    if (dt >= 0) Log.i(TAG, "Bluetooth SCO connected in ${dt}ms")
                                    if (cont.isActive) cont.resume(true)
                                }
                                AudioManager.SCO_AUDIO_STATE_ERROR -> if (cont.isActive) cont.resume(false)
                            }
                        }
                    }
                    try { context.registerReceiver(receiver, filter) } catch (t: Throwable) { Log.w(TAG, "registerReceiver failed", t) }
                    try {
                        if (!am.isBluetoothScoOn) am.startBluetoothSco()
                    } catch (t: Throwable) {
                        Log.w(TAG, "startBluetoothSco failed", t)
                        if (cont.isActive) cont.resume(false)
                    }
                    cont.invokeOnCancellation {
                        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Throwable) {}
                    }
                }
            } ?: false
            try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Throwable) {}
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "startScoAndAwaitConnected exception", t)
            false
        }
    }

    // 仅用于兼容旧版 SCO 路径的封装，集中抑制弃用告警
    @Suppress("DEPRECATION")
    private fun isScoOnCompat(am: AudioManager): Boolean {
        return try {
            am.isBluetoothScoOn
        } catch (t: Throwable) {
            Log.w(TAG, "Query isBluetoothScoOn failed", t)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopScoCompat(am: AudioManager) {
        try {
            am.stopBluetoothSco()
            Log.i(TAG, "Bluetooth SCO stopped")
        } catch (t: Throwable) {
            Log.w(TAG, "stopBluetoothSco failed", t)
        }
    }

    private data class CommRouteResult(
        val commDeviceSet: Boolean,
        val selectedDevice: AudioDeviceInfo?,
        val listenerToken: Any?,
        val routeReady: Boolean
    )
}
