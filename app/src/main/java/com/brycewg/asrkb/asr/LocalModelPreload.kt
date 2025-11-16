package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs

/**
 * 统一的本地 ASR 预加载入口：根据供应商调用对应实现。
 * - 目前支持 SenseVoice / TeleSpeech / Paraformer / Zipformer
 */
fun preloadLocalAsrIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> preloadSenseVoiceIfConfigured(
                context, prefs, onLoadStart, onLoadDone, suppressToastOnStart, forImmediateUse
            )
            AsrVendor.Telespeech -> preloadTelespeechIfConfigured(
                context, prefs, onLoadStart, onLoadDone, suppressToastOnStart, forImmediateUse
            )
            AsrVendor.Paraformer -> preloadParaformerIfConfigured(
                context, prefs, onLoadStart, onLoadDone, suppressToastOnStart, forImmediateUse
            )
            AsrVendor.Zipformer -> {
                preloadZipformerIfConfigured(
                    context, prefs, onLoadStart, onLoadDone, suppressToastOnStart, forImmediateUse
                )
            }
            else -> { /* no-op for cloud vendors */ }
        }
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "preloadLocalAsrIfConfigured failed", t)
    }
}

/**
 * 统一检查本地 ASR 是否已准备（模型已加载）
 */
fun isLocalAsrPrepared(prefs: Prefs): Boolean {
    return try {
        when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> isSenseVoicePrepared()
            AsrVendor.Telespeech -> isTelespeechPrepared()
            AsrVendor.Paraformer -> ParaformerOnnxManager.getInstance().isPrepared()
            AsrVendor.Zipformer -> ZipformerOnnxManager.getInstance().isPrepared()
            else -> false
        }
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "isLocalAsrPrepared failed", t)
        false
    }
}
