package com.brycewg.asrkb.ui.settings.asr

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for ASR Settings screen, managing all state and business logic.
 * UI observes StateFlows and reacts to state changes automatically.
 */
class AsrSettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AsrSettingsUiState())
    val uiState: StateFlow<AsrSettingsUiState> = _uiState.asStateFlow()

    private lateinit var prefs: Prefs
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        loadInitialState()
    }

    private fun loadInitialState() {
        fun isQwenOmniModel(model: String): Boolean {
            return model.startsWith("Qwen/Qwen3-Omni-30B-A3B-")
        }
        _uiState.value = AsrSettingsUiState(
            selectedVendor = prefs.asrVendor,
            autoStopSilenceEnabled = prefs.autoStopOnSilenceEnabled,
            silenceWindowMs = prefs.autoStopSilenceWindowMs,
            silenceSensitivity = prefs.autoStopSilenceSensitivity,
            aiEditPreferLastAsr = prefs.aiEditDefaultToLastAsr,
            // Volc settings
            volcStreamingEnabled = prefs.volcStreamingEnabled,
            volcBidiStreamingEnabled = prefs.volcBidiStreamingEnabled,
            volcDdcEnabled = prefs.volcDdcEnabled,
            volcVadEnabled = prefs.volcVadEnabled,
            volcNonstreamEnabled = prefs.volcNonstreamEnabled,
            volcFirstCharAccelEnabled = prefs.volcFirstCharAccelEnabled,
            volcLanguage = prefs.volcLanguage,
            // DashScope settings
            dashStreamingEnabled = prefs.dashStreamingEnabled,
            // SiliconFlow settings
            sfUseOmni = isQwenOmniModel(prefs.sfModel.ifBlank { com.brycewg.asrkb.store.Prefs.DEFAULT_SF_MODEL }),
            // Eleven settings
            elevenStreamingEnabled = prefs.elevenStreamingEnabled,
            // OpenAI settings
            oaAsrUsePrompt = prefs.oaAsrUsePrompt,
            // Soniox settings
            sonioxStreamingEnabled = prefs.sonioxStreamingEnabled,
            sonioxLanguages = prefs.getSonioxLanguages(),
            // SenseVoice settings
            svModelVariant = prefs.svModelVariant,
            svNumThreads = prefs.svNumThreads,
            svLanguage = prefs.svLanguage,
            svUseItn = prefs.svUseItn,
            svPreloadEnabled = prefs.svPreloadEnabled,
            svKeepAliveMinutes = prefs.svKeepAliveMinutes,
            svPseudoStreamEnabled = prefs.svPseudoStreamEnabled,
            // TeleSpeech settings
            tsModelVariant = prefs.tsModelVariant,
            tsNumThreads = prefs.tsNumThreads,
            tsKeepAliveMinutes = prefs.tsKeepAliveMinutes,
            tsPreloadEnabled = prefs.tsPreloadEnabled,
            tsUseItn = prefs.tsUseItn,
            tsPseudoStreamEnabled = prefs.tsPseudoStreamEnabled,
            // Paraformer settings
            pfModelVariant = prefs.pfModelVariant,
            pfNumThreads = prefs.pfNumThreads,
            pfKeepAliveMinutes = prefs.pfKeepAliveMinutes,
            pfPreloadEnabled = prefs.pfPreloadEnabled,
            pfUseItn = prefs.pfUseItn,
            // Zipformer settings
            zfModelVariant = prefs.zfModelVariant,
            zfNumThreads = prefs.zfNumThreads,
            zfKeepAliveMinutes = prefs.zfKeepAliveMinutes,
            zfPreloadEnabled = prefs.zfPreloadEnabled,
            zfUseItn = prefs.zfUseItn
        )
    }

    fun updateVendor(vendor: AsrVendor) {
        val oldVendor = prefs.asrVendor
        prefs.asrVendor = vendor
        _uiState.value = _uiState.value.copy(selectedVendor = vendor)

        // Handle local model lifecycle cleanup
        if (oldVendor == AsrVendor.SenseVoice && vendor != AsrVendor.SenseVoice) {
            try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload SenseVoice recognizer", e) }
        }
        if (oldVendor == AsrVendor.Telespeech && vendor != AsrVendor.Telespeech) {
            try { com.brycewg.asrkb.asr.unloadTelespeechRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload TeleSpeech recognizer", e) }
        }
        if (oldVendor == AsrVendor.Paraformer && vendor != AsrVendor.Paraformer) {
            try { com.brycewg.asrkb.asr.unloadParaformerRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload Paraformer recognizer", e) }
        }
        if (oldVendor == AsrVendor.Zipformer && vendor != AsrVendor.Zipformer) {
            try { com.brycewg.asrkb.asr.unloadZipformerRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload Zipformer recognizer", e) }
        }

        if (vendor == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice model", e)
                }
            }
        }
        if (vendor == AsrVendor.Telespeech && prefs.tsPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadTelespeechIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload TeleSpeech model", e)
                }
            }
        }

        if (vendor == AsrVendor.Paraformer && prefs.pfPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadParaformerIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload Paraformer model", e)
                }
            }
        }

        if (vendor == AsrVendor.Zipformer && prefs.zfPreloadEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadZipformerIfConfigured(appContext, prefs)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload Zipformer model", e)
                }
            }
        }
    }

    fun updateAutoStopSilence(enabled: Boolean) {
        prefs.autoStopOnSilenceEnabled = enabled
        _uiState.value = _uiState.value.copy(autoStopSilenceEnabled = enabled)
    }

    fun updateSilenceWindow(windowMs: Int) {
        prefs.autoStopSilenceWindowMs = windowMs
        _uiState.value = _uiState.value.copy(silenceWindowMs = windowMs)
    }

    fun updateSilenceSensitivity(sensitivity: Int) {
        prefs.autoStopSilenceSensitivity = sensitivity
        _uiState.value = _uiState.value.copy(silenceSensitivity = sensitivity)
    }

    fun updateVolcStreaming(enabled: Boolean) {
        prefs.volcStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(volcStreamingEnabled = enabled)
    }

    fun updateVolcDdc(enabled: Boolean) {
        prefs.volcDdcEnabled = enabled
        _uiState.value = _uiState.value.copy(volcDdcEnabled = enabled)
    }

    fun updateVolcVad(enabled: Boolean) {
        prefs.volcVadEnabled = enabled
        _uiState.value = _uiState.value.copy(volcVadEnabled = enabled)
    }

    fun updateVolcBidiStreaming(enabled: Boolean) {
        prefs.volcBidiStreamingEnabled = enabled
        // 若关闭双向流式，则强制关闭二遍识别
        if (!enabled) {
            if (prefs.volcNonstreamEnabled) prefs.volcNonstreamEnabled = false
            _uiState.value = _uiState.value.copy(
                volcBidiStreamingEnabled = false,
                volcNonstreamEnabled = false
            )
        } else {
            _uiState.value = _uiState.value.copy(volcBidiStreamingEnabled = true)
        }
    }

    fun updateVolcNonstream(enabled: Boolean) {
        prefs.volcNonstreamEnabled = enabled
        _uiState.value = _uiState.value.copy(volcNonstreamEnabled = enabled)
    }

    fun updateDashStreaming(enabled: Boolean) {
        prefs.dashStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(dashStreamingEnabled = enabled)
    }

    fun updateVolcFirstCharAccel(enabled: Boolean) {
        prefs.volcFirstCharAccelEnabled = enabled
        _uiState.value = _uiState.value.copy(volcFirstCharAccelEnabled = enabled)
    }

    fun updateVolcLanguage(language: String) {
        prefs.volcLanguage = language
        _uiState.value = _uiState.value.copy(volcLanguage = language)
    }

    fun updateSfUseOmni(enabled: Boolean) {
        prefs.sfUseOmni = enabled
        _uiState.value = _uiState.value.copy(sfUseOmni = enabled)
    }

    fun updateOpenAiUsePrompt(enabled: Boolean) {
        prefs.oaAsrUsePrompt = enabled
        _uiState.value = _uiState.value.copy(oaAsrUsePrompt = enabled)
    }

    fun updateElevenStreaming(enabled: Boolean) {
        prefs.elevenStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(elevenStreamingEnabled = enabled)
    }

    fun updateSonioxStreaming(enabled: Boolean) {
        prefs.sonioxStreamingEnabled = enabled
        _uiState.value = _uiState.value.copy(sonioxStreamingEnabled = enabled)
    }

    fun updateSonioxLanguages(languages: List<String>) {
        prefs.setSonioxLanguages(languages)
        _uiState.value = _uiState.value.copy(sonioxLanguages = languages)
    }

    fun updateSvModelVariant(variant: String) {
        prefs.svModelVariant = variant
        _uiState.value = _uiState.value.copy(svModelVariant = variant)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after variant change", e)
        }
        triggerSvPreloadIfEnabledAndActive("variant change")
    }

    fun updateSvNumThreads(threads: Int) {
        prefs.svNumThreads = threads
        _uiState.value = _uiState.value.copy(svNumThreads = threads)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after threads change", e)
        }
        triggerSvPreloadIfEnabledAndActive("threads change")
    }

    fun updateSvLanguage(language: String) {
        prefs.svLanguage = language
        _uiState.value = _uiState.value.copy(svLanguage = language)
        try {
            com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unload SenseVoice recognizer after language change", e)
        }
        triggerSvPreloadIfEnabledAndActive("language change")
    }

    fun updateSvUseItn(enabled: Boolean) {
        if (prefs.svUseItn != enabled) {
            prefs.svUseItn = enabled
            _uiState.value = _uiState.value.copy(svUseItn = enabled)
            try {
                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload SenseVoice recognizer after ITN change", e)
            }
            triggerSvPreloadIfEnabledAndActive("ITN change")
        }
    }

    fun updateTsUseItn(enabled: Boolean) {
        if (prefs.tsUseItn != enabled) {
            prefs.tsUseItn = enabled
            _uiState.value = _uiState.value.copy(tsUseItn = enabled)
            try {
                com.brycewg.asrkb.asr.unloadTelespeechRecognizer()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to unload TeleSpeech recognizer after ITN change", e)
            }
            triggerTsPreloadIfEnabledAndActive("ts ITN change")
        }
    }

    fun updatePfUseItn(enabled: Boolean) {
        if (prefs.pfUseItn != enabled) {
            prefs.pfUseItn = enabled
            _uiState.value = _uiState.value.copy(pfUseItn = enabled)
            try { com.brycewg.asrkb.asr.unloadParaformerRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload Paraformer recognizer after ITN change", e) }
            triggerPfPreloadIfEnabledAndActive("pf ITN change")
        }
    }

    fun updateSvPreload(enabled: Boolean) {
        prefs.svPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(svPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.SenseVoice) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice model", e)
                }
            }
        }
    }

    fun updateSvPseudoStream(enabled: Boolean) {
        prefs.svPseudoStreamEnabled = enabled
        _uiState.value = _uiState.value.copy(svPseudoStreamEnabled = enabled)
    }

    fun updateTsModelVariant(variant: String) {
        prefs.tsModelVariant = variant
        _uiState.value = _uiState.value.copy(tsModelVariant = variant)
        try { com.brycewg.asrkb.asr.unloadTelespeechRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload TeleSpeech recognizer after variant change", e) }
        triggerTsPreloadIfEnabledAndActive("variant change")
    }

    fun updateTsKeepAlive(minutes: Int) {
        prefs.tsKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(tsKeepAliveMinutes = minutes)
    }

    fun updateTsNumThreads(v: Int) {
        val vv = v.coerceIn(1, 8)
        prefs.tsNumThreads = vv
        _uiState.value = _uiState.value.copy(tsNumThreads = vv)
        try { com.brycewg.asrkb.asr.unloadTelespeechRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload TeleSpeech recognizer after threads change", e) }
        triggerTsPreloadIfEnabledAndActive("threads change")
    }

    fun updateTsPreload(enabled: Boolean) {
        prefs.tsPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(tsPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.Telespeech) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadTelespeechIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload TeleSpeech model", e)
                }
            }
        }
    }

    fun updateTsPseudoStream(enabled: Boolean) {
        prefs.tsPseudoStreamEnabled = enabled
        _uiState.value = _uiState.value.copy(tsPseudoStreamEnabled = enabled)
    }

    fun updateSvKeepAlive(minutes: Int) {
        prefs.svKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(svKeepAliveMinutes = minutes)
    }

    // ----- Paraformer -----
    fun updatePfModelVariant(variant: String) {
        prefs.pfModelVariant = variant
        _uiState.value = _uiState.value.copy(pfModelVariant = variant)
        try { com.brycewg.asrkb.asr.unloadParaformerRecognizer() } catch (_: Throwable) { }
        triggerPfPreloadIfEnabledAndActive("variant change")
    }

    fun updatePfKeepAlive(minutes: Int) {
        prefs.pfKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(pfKeepAliveMinutes = minutes)
    }

    fun updatePfNumThreads(v: Int) {
        val vv = v.coerceIn(1, 8)
        prefs.pfNumThreads = vv
        _uiState.value = _uiState.value.copy(pfNumThreads = vv)
        // 线程数变化后卸载已缓存识别器，必要时重新预加载
        try { com.brycewg.asrkb.asr.unloadParaformerRecognizer() } catch (_: Throwable) { }
        triggerPfPreloadIfEnabledAndActive("threads change")
    }

    // 统一预加载触发
    private fun triggerSvPreloadIfEnabledAndActive(reason: String) {
        if (prefs.svPreloadEnabled && prefs.asrVendor == AsrVendor.SenseVoice) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload SenseVoice after $reason", t)
                }
            }
        }
    }

    private fun triggerTsPreloadIfEnabledAndActive(reason: String) {
        if (prefs.tsPreloadEnabled && prefs.asrVendor == AsrVendor.Telespeech) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadTelespeechIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload TeleSpeech after $reason", t)
                }
            }
        }
    }

    private fun triggerPfPreloadIfEnabledAndActive(reason: String) {
        if (prefs.pfPreloadEnabled && prefs.asrVendor == AsrVendor.Paraformer) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadParaformerIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload Paraformer after $reason", t)
                }
            }
        }
    }

    private fun triggerZfPreloadIfEnabledAndActive(reason: String) {
        if (prefs.zfPreloadEnabled && prefs.asrVendor == AsrVendor.Zipformer) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadZipformerIfConfigured(appContext, prefs)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload Zipformer after $reason", t)
                }
            }
        }
    }

    fun updatePfPreload(enabled: Boolean) {
        prefs.pfPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(pfPreloadEnabled = enabled)

        if (enabled && prefs.asrVendor == AsrVendor.Paraformer) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    com.brycewg.asrkb.asr.preloadParaformerIfConfigured(
                        appContext,
                        prefs
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to preload Paraformer model", e)
                }
            }
        }
    }

    // ----- Zipformer -----
    fun updateZfModelVariant(variant: String) {
        prefs.zfModelVariant = variant
        _uiState.value = _uiState.value.copy(zfModelVariant = variant)
        try { com.brycewg.asrkb.asr.unloadZipformerRecognizer() } catch (_: Throwable) { }
        triggerZfPreloadIfEnabledAndActive("variant change")
    }

    fun updateZfKeepAlive(minutes: Int) {
        prefs.zfKeepAliveMinutes = minutes
        _uiState.value = _uiState.value.copy(zfKeepAliveMinutes = minutes)
    }

    fun updateZfNumThreads(v: Int) {
        val vv = v.coerceIn(1, 8)
        prefs.zfNumThreads = vv
        _uiState.value = _uiState.value.copy(zfNumThreads = vv)
        try { com.brycewg.asrkb.asr.unloadZipformerRecognizer() } catch (_: Throwable) { }
        triggerZfPreloadIfEnabledAndActive("threads change")
    }

    fun updateZfPreload(enabled: Boolean) {
        prefs.zfPreloadEnabled = enabled
        _uiState.value = _uiState.value.copy(zfPreloadEnabled = enabled)
        if (enabled && prefs.asrVendor == AsrVendor.Zipformer) {
            viewModelScope.launch(Dispatchers.Default) {
                try { com.brycewg.asrkb.asr.preloadZipformerIfConfigured(appContext, prefs) } catch (t: Throwable) {
                    Log.e(TAG, "Failed to preload Zipformer model", t)
                }
            }
        }
    }

    fun updateZfUseItn(enabled: Boolean) {
        if (prefs.zfUseItn != enabled) {
            prefs.zfUseItn = enabled
            _uiState.value = _uiState.value.copy(zfUseItn = enabled)
            try { com.brycewg.asrkb.asr.unloadZipformerRecognizer() } catch (e: Throwable) { Log.e(TAG, "Failed to unload Zipformer recognizer after ITN change", e) }
            triggerZfPreloadIfEnabledAndActive("zf ITN change")
        }
    }

    fun checkPfModelDownloaded(context: Context): Boolean {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = File(base, "paraformer")
        val group = if (prefs.pfModelVariant.startsWith("trilingual")) File(root, "trilingual") else File(root, "bilingual")
        val dir = com.brycewg.asrkb.asr.findPfModelDir(group)
        return dir != null &&
                File(dir, "tokens.txt").exists() &&
                (
                    (File(dir, "encoder.onnx").exists() && File(dir, "decoder.onnx").exists()) ||
                    (File(dir, "encoder.int8.onnx").exists() && File(dir, "decoder.int8.onnx").exists())
                )
    }

    fun checkSvModelDownloaded(context: Context): Boolean {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = File(base, "sensevoice")
        val variant = prefs.svModelVariant
        val dir = if (variant == "small-full") File(root, "small-full") else File(root, "small-int8")
        val modelDir = findModelDir(dir)
        return modelDir != null &&
                File(modelDir, "tokens.txt").exists() &&
                (File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())
    }

    fun checkTsModelDownloaded(context: Context): Boolean {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = File(base, "telespeech")
        val variant = prefs.tsModelVariant
        val dir = when (variant) {
            "full" -> File(root, "full")
            else -> File(root, "int8")
        }
        val modelDir = findModelDir(dir)
        return modelDir != null &&
                File(modelDir, "tokens.txt").exists() &&
                (File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())
    }

    fun checkZfModelDownloaded(context: Context): Boolean {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = File(base, "zipformer")
        val outDir = when {
            prefs.zfModelVariant.startsWith("zh-xl-") -> File(root, "zh-xlarge-2025-06-30")
            prefs.zfModelVariant.startsWith("zh-") -> File(root, "zh-2025-06-30")
            prefs.zfModelVariant.startsWith("bi-small-") -> File(root, "small-bilingual-zh-en-2023-02-16")
            else -> File(root, "bilingual-zh-en-2023-02-20")
        }
        val modelDir = findModelDir(outDir) ?: return false
        val files = modelDir.listFiles() ?: emptyArray()
        if (!File(modelDir, "tokens.txt").exists()) return false
        fun exists(regex: Regex): Boolean = files.any { it.isFile && regex.matches(it.name) }
        val hasEncoder = exists(Regex("^encoder(?:[.-].*)?\\.onnx$"))
        val hasDecoder = exists(Regex("^decoder(?:[.-].*)?\\.onnx$"))
        val hasJoiner = exists(Regex("^joiner(?:[.-].*)?\\.onnx$"))
        return hasEncoder && hasDecoder && hasJoiner
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

    companion object {
        private const val TAG = "AsrSettingsViewModel"
    }
}

/**
 * UI State for ASR Settings screen.
 * Contains all configuration values and visibility flags.
 */
data class AsrSettingsUiState(
    val selectedVendor: AsrVendor = AsrVendor.Volc,
    val autoStopSilenceEnabled: Boolean = false,
    val silenceWindowMs: Int = 1200,
    val silenceSensitivity: Int = 4,
    val aiEditPreferLastAsr: Boolean = false,
    // Volcengine settings
    val volcStreamingEnabled: Boolean = false,
    val volcBidiStreamingEnabled: Boolean = true,
    val volcDdcEnabled: Boolean = false,
    val volcVadEnabled: Boolean = false,
    val volcNonstreamEnabled: Boolean = false,
    val volcFirstCharAccelEnabled: Boolean = false,
    val volcLanguage: String = "",
    // DashScope settings
    val dashStreamingEnabled: Boolean = false,
    // SiliconFlow settings
    val sfUseOmni: Boolean = false,
    // ElevenLabs settings
    val elevenStreamingEnabled: Boolean = false,
    // OpenAI settings
    val oaAsrUsePrompt: Boolean = false,
    // Soniox settings
    val sonioxStreamingEnabled: Boolean = false,
    val sonioxLanguages: List<String> = emptyList(),
    // SenseVoice settings
    val svModelVariant: String = "small-int8",
    val svNumThreads: Int = 2,
    val svLanguage: String = "auto",
    val svUseItn: Boolean = true,
    val svPreloadEnabled: Boolean = false,
    val svKeepAliveMinutes: Int = -1,
    val svPseudoStreamEnabled: Boolean = false,
    // TeleSpeech settings
    val tsModelVariant: String = "int8",
    val tsNumThreads: Int = 2,
    val tsKeepAliveMinutes: Int = -1,
    val tsPreloadEnabled: Boolean = false,
    val tsUseItn: Boolean = true,
    val tsPseudoStreamEnabled: Boolean = false,
    // Paraformer settings
    val pfModelVariant: String = "bilingual-int8",
    val pfNumThreads: Int = 2,
    val pfKeepAliveMinutes: Int = -1,
    val pfPreloadEnabled: Boolean = false,
    val pfUseItn: Boolean = false,
    // Zipformer settings
    val zfModelVariant: String = "zh-xl-int8-20250630",
    val zfNumThreads: Int = 2,
    val zfKeepAliveMinutes: Int = -1,
    val zfPreloadEnabled: Boolean = false,
    val zfUseItn: Boolean = false
) {
    // Computed visibility properties based on selected vendor
    val isVolcVisible: Boolean get() = selectedVendor == AsrVendor.Volc
    val isSfVisible: Boolean get() = selectedVendor == AsrVendor.SiliconFlow
    val isElevenVisible: Boolean get() = selectedVendor == AsrVendor.ElevenLabs
    val isOpenAiVisible: Boolean get() = selectedVendor == AsrVendor.OpenAI
    val isDashVisible: Boolean get() = selectedVendor == AsrVendor.DashScope
    val isGeminiVisible: Boolean get() = selectedVendor == AsrVendor.Gemini
    val isSonioxVisible: Boolean get() = selectedVendor == AsrVendor.Soniox
    val isSenseVoiceVisible: Boolean get() = selectedVendor == AsrVendor.SenseVoice
    val isTelespeechVisible: Boolean get() = selectedVendor == AsrVendor.Telespeech
    val isParaformerVisible: Boolean get() = selectedVendor == AsrVendor.Paraformer
    val isZipformerVisible: Boolean get() = selectedVendor == AsrVendor.Zipformer
}
