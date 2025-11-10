package com.brycewg.asrkb.asr

import android.content.Context

/**
 * OSS 变体占位：提供与 Pro 版相同的 API，但不注入任何额外上下文/热词。
 * 这样主工程可以无条件调用，而不会在 OSS 变体产生依赖错误。
 */
object ProAsrHelper {
    fun buildPromptWithContext(
        context: Context,
        basePrompt: String,
        compact: Boolean = false
    ): String {
        return basePrompt
    }

    fun buildVolcContext(context: Context): String? {
        return null
    }

    fun buildSonioxContext(context: Context): org.json.JSONObject? {
        return null
    }

    /**
     * 创建 Volc 流式引擎（OSS：直接返回原始引擎；Pro：可能返回双重识别包装引擎）。
     */
    fun createVolcStreamingEngine(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        prefs: com.brycewg.asrkb.store.Prefs,
        listener: StreamingAsrEngine.Listener
    ): StreamingAsrEngine {
        return VolcStreamAsrEngine(context, scope, prefs, listener)
    }

    /**
     * 推送PCM版本（OSS：直接回落到原始流式 externalPcmMode=true）。
     */
    fun createVolcStreamingEngineForPushPcm(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        prefs: com.brycewg.asrkb.store.Prefs,
        listener: StreamingAsrEngine.Listener
    ): StreamingAsrEngine {
        return VolcStreamAsrEngine(context, scope, prefs, listener, externalPcmMode = true)
    }
}
