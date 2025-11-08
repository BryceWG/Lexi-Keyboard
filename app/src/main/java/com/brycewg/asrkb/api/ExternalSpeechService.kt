package com.brycewg.asrkb.api

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.aidl.SpeechConfig
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

/**
 * 对外导出的语音服务（Binder 手写协议，兼容 AIDL 生成的代理）。
 * - 接口描述符需与 AIDL 一致：com.brycewg.asrkb.aidl.IExternalSpeechService。
 * - 方法顺序与 AIDL 保持一致，以匹配事务码。
 */
class ExternalSpeechService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val sessions = ConcurrentHashMap<Int, Session>()
    @Volatile private var nextId: Int = 1

    override fun onBind(intent: Intent?): IBinder? = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR_SVC)
                    return true
                }
                TRANSACTION_startSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val cfg = if (data.readInt() != 0) SpeechConfig.CREATOR.createFromParcel(data) else null
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    // 开关与权限检查：要求同时开启外部联动 + 悬浮球功能
                    if (!prefs.externalAidlEnabled || !prefs.floatingAsrEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    // 联通测试：当 vendorId == "mock" 时，无需录音权限，直接回调固定内容并结束
                    if (cfg?.vendorId == "mock") {
                        val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                        safe { cb.onState(sid, STATE_RECORDING, "recording") }
                        safe { cb.onPartial(sid, "【联通测试中】……") }
                        safe { cb.onFinal(sid, "言犀外部AIDL联通成功（mock）") }
                        safe { cb.onState(sid, STATE_IDLE, "final") }
                        reply?.apply { writeNoException(); writeInt(sid) }
                        return true
                    }

                    val permOk = ContextCompat.checkSelfPermission(
                        this@ExternalSpeechService,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!permOk) {
                        safe { cb.onError(-1, 401, "record permission denied") }
                        reply?.apply { writeNoException(); writeInt(-4) }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply { writeNoException(); writeInt(-2) }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = Session(sid, this@ExternalSpeechService, prefs, cb, cfg)
                    if (!s.prepare()) {
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    sessions[sid] = s
                    s.start()
                    reply?.apply { writeNoException(); writeInt(sid) }
                    return true
                }
                TRANSACTION_stopSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_cancelSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.cancel()
                    sessions.remove(sid)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_isRecording -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val r = sessions[sid]?.engine?.isRunning == true
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }
                TRANSACTION_isAnyRecording -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val r = sessions.values.any { it.engine?.isRunning == true }
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }
                TRANSACTION_getVersion -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    reply?.apply { writeNoException(); writeString(com.brycewg.asrkb.BuildConfig.VERSION_NAME) }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private class Session(
        private val id: Int,
        private val context: Context,
        private val prefs: Prefs,
        private val cb: CallbackProxy,
        private val cfg: SpeechConfig?
    ) : StreamingAsrEngine.Listener {
        var engine: StreamingAsrEngine? = null

        fun prepare(): Boolean {
            val vendor = try {
                val v = cfg?.vendorId
                if (v.isNullOrBlank()) prefs.asrVendor else AsrVendor.fromId(v)
            } catch (t: Throwable) { Log.w(TAG, "vendor parse failed", t); prefs.asrVendor }
            val streamingPref = cfg?.streamingPreferred ?: true
            engine = buildEngine(vendor, streamingPref)
            return engine != null
        }

        fun start() {
            safe { cb.onState(id, STATE_RECORDING, "recording") }
            engine?.start()
        }

        fun stop() {
            engine?.stop()
            safe { cb.onState(id, STATE_PROCESSING, "processing") }
        }

        fun cancel() {
            try { engine?.stop() } catch (_: Throwable) {}
            safe { cb.onState(id, STATE_IDLE, "canceled") }
        }

        private fun buildEngine(vendor: AsrVendor, streamingPreferred: Boolean): StreamingAsrEngine? {
            val scope = CoroutineScope(Dispatchers.Main)
            return when (vendor) {
                AsrVendor.Volc -> if (streamingPreferred) {
                    ProAsrHelper.createVolcStreamingEngine(context, scope, prefs, this)
                } else {
                    VolcFileAsrEngine(context, scope, prefs, this)
                }
                AsrVendor.SiliconFlow -> SiliconFlowFileAsrEngine(context, scope, prefs, this)
                AsrVendor.ElevenLabs -> ElevenLabsFileAsrEngine(context, scope, prefs, this)
                AsrVendor.OpenAI -> OpenAiFileAsrEngine(context, scope, prefs, this)
                AsrVendor.DashScope -> if (streamingPreferred) {
                    DashscopeStreamAsrEngine(context, scope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(context, scope, prefs, this)
                }
                AsrVendor.Gemini -> GeminiFileAsrEngine(context, scope, prefs, this)
                AsrVendor.Soniox -> if (streamingPreferred) {
                    SonioxStreamAsrEngine(context, scope, prefs, this)
                } else {
                    SonioxFileAsrEngine(context, scope, prefs, this)
                }
                AsrVendor.SenseVoice -> SenseVoiceFileAsrEngine(context, scope, prefs, this)
                AsrVendor.Paraformer -> ParaformerStreamAsrEngine(context, scope, prefs, this)
                AsrVendor.Zipformer -> ZipformerStreamAsrEngine(context, scope, prefs, this)
            }
        }

        override fun onFinal(text: String) {
            safe { cb.onFinal(id, text) }
            safe { cb.onState(id, STATE_IDLE, "final") }
            try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on final failed", t) }
        }

        override fun onError(message: String) {
            safe {
                cb.onError(id, 500, message)
                cb.onState(id, STATE_ERROR, message)
            }
            try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on error failed", t) }
        }

        override fun onPartial(text: String) { if (text.isNotEmpty()) safe { cb.onPartial(id, text) } }

        override fun onStopped() { safe { cb.onState(id, STATE_PROCESSING, "processing") } }

        override fun onAmplitude(amplitude: Float) { safe { cb.onAmplitude(id, amplitude) } }
    }

    private class CallbackProxy(private val remote: IBinder?) {
        fun onState(sessionId: Int, state: Int, msg: String) {
            transact(CB_onState) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(state)
                data.writeString(msg)
            }
        }
        fun onPartial(sessionId: Int, text: String) {
            transact(CB_onPartial) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onFinal(sessionId: Int, text: String) {
            transact(CB_onFinal) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onError(sessionId: Int, code: Int, message: String) {
            transact(CB_onError) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(code)
                data.writeString(message)
            }
        }
        fun onAmplitude(sessionId: Int, amp: Float) {
            transact(CB_onAmplitude) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeFloat(amp)
            }
        }

        private inline fun transact(code: Int, fill: (Parcel) -> Unit) {
            val b = remote ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                fill(data)
                b.transact(code, data, reply, 0)
                reply.readException()
            } catch (t: Throwable) {
                Log.w(TAG, "callback transact failed: code=$code", t)
            } finally {
                try { data.recycle() } catch (_: Throwable) {}
                try { reply.recycle() } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private const val TAG = "ExternalSpeechSvc"

        // 与 AIDL 生成的 Stub 保持一致的描述符与事务号
        private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
        private const val TRANSACTION_startSession = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_stopSession = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_cancelSession = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_isRecording = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_isAnyRecording = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_getVersion = IBinder.FIRST_CALL_TRANSACTION + 5

        private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
        private const val CB_onState = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val CB_onPartial = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CB_onFinal = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CB_onError = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val CB_onAmplitude = IBinder.FIRST_CALL_TRANSACTION + 4

        private const val STATE_IDLE = 0
        private const val STATE_RECORDING = 1
        private const val STATE_PROCESSING = 2
        private const val STATE_ERROR = 3

        private inline fun safe(block: () -> Unit) { try { block() } catch (t: Throwable) { Log.w(TAG, "callback failed", t) } }
    }

    // 统一的会话清理入口：在 onFinal/onError 触发后移除，避免内存泄漏
    private fun onSessionDone(sessionId: Int) {
        try {
            sessions.remove(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "sessions.remove failed for id=$sessionId", t)
        }
    }
}
