package com.brycewg.asrkb.ui.settings.asr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.asr.VadDetector
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * ASR Settings Activity - Refactored version with ViewModel pattern.
 *
 * Key improvements:
 * 1. Introduced AsrSettingsViewModel to manage state and business logic
 * 2. Split giant onCreate into focused setup methods
 * 3. Created reusable showSingleChoiceDialog function
 * 4. Added proper logging to all catch blocks
 * 5. Vendor-specific settings organized in separate methods
 */
class AsrSettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: AsrSettingsViewModel
    private lateinit var prefs: Prefs

    // View references grouped by function
    private lateinit var tvAsrVendor: TextView

    // Silence detection views
    private lateinit var switchAutoStopSilence: MaterialSwitch
    private lateinit var tvSilenceWindowLabel: View
    private lateinit var sliderSilenceWindow: Slider
    private lateinit var tvSilenceSensitivityLabel: View
    private lateinit var sliderSilenceSensitivity: Slider

    // Vendor group containers
    private lateinit var groupVolc: View
    private lateinit var groupSf: View
    private lateinit var groupEleven: View
    private lateinit var groupOpenAi: View
    private lateinit var groupDash: View
    private lateinit var groupGemini: View
    private lateinit var groupSoniox: View
    private lateinit var groupSenseVoice: View
    private lateinit var groupParaformer: View
    private lateinit var groupZipformer: View

    // Vendor title views
    private lateinit var titleVolc: View
    private lateinit var titleSf: View
    private lateinit var titleEleven: View
    private lateinit var titleOpenAi: View
    private lateinit var titleDash: View
    private lateinit var titleGemini: View
    private lateinit var titleSoniox: View
    private lateinit var titleSenseVoice: View
    private lateinit var titleParaformer: View
    private lateinit var titleZipformer: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)
        viewModel = ViewModelProvider(this)[AsrSettingsViewModel::class.java]
        viewModel.initialize(this)

        setupToolbar()
        initializeViews()
        setupVendorSelection()
        setupSilenceDetection()
        setupVendorSpecificSettings()
        observeViewModel()

        // Pro：注入“个性化识别热词管理”入口
        try {
            val root = findViewById<android.view.View>(android.R.id.content)
            com.brycewg.asrkb.ProUiInjector.injectIntoAsrSettings(this, root)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to inject pro UI (ASR)", e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSvDownloadUiVisibility()
        updatePfDownloadUiVisibility()
        updateZfDownloadUiVisibility()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_asr_settings)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initializeViews() {
        // ASR Vendor
        tvAsrVendor = findViewById(R.id.tvAsrVendorValue)

        // Silence auto-stop controls
        switchAutoStopSilence = findViewById(R.id.switchAutoStopSilence)
        tvSilenceWindowLabel = findViewById(R.id.tvSilenceWindowLabel)
        sliderSilenceWindow = findViewById(R.id.sliderSilenceWindow)
        tvSilenceSensitivityLabel = findViewById(R.id.tvSilenceSensitivityLabel)
        sliderSilenceSensitivity = findViewById(R.id.sliderSilenceSensitivity)

        // Vendor groups
        groupVolc = findViewById(R.id.groupVolc)
        groupSf = findViewById(R.id.groupSf)
        groupEleven = findViewById(R.id.groupEleven)
        groupOpenAi = findViewById(R.id.groupOpenAI)
        groupDash = findViewById(R.id.groupDashScope)
        groupGemini = findViewById(R.id.groupGemini)
        groupSoniox = findViewById(R.id.groupSoniox)
        groupSenseVoice = findViewById(R.id.groupSenseVoice)
        groupParaformer = findViewById(R.id.groupParaformer)
        groupZipformer = findViewById(R.id.groupZipformer)

        // Vendor titles
        titleVolc = findViewById(R.id.titleVolc)
        titleSf = findViewById(R.id.titleSf)
        titleEleven = findViewById(R.id.titleEleven)
        titleOpenAi = findViewById(R.id.titleOpenAI)
        titleDash = findViewById(R.id.titleDash)
        titleGemini = findViewById(R.id.titleGemini)
        titleSoniox = findViewById(R.id.titleSoniox)
        titleSenseVoice = findViewById(R.id.titleSenseVoice)
        titleParaformer = findViewById(R.id.titleParaformer)
        titleZipformer = findViewById(R.id.titleZipformer)
    }

    private fun setupVendorSelection() {
        val vendorOrder = listOf(
            AsrVendor.Volc, AsrVendor.SiliconFlow, AsrVendor.ElevenLabs,
            AsrVendor.OpenAI, AsrVendor.DashScope, AsrVendor.Gemini,
            AsrVendor.Soniox, AsrVendor.SenseVoice, AsrVendor.Paraformer, AsrVendor.Zipformer
        )
        val vendorItems = listOf(
            getString(R.string.vendor_volc), getString(R.string.vendor_sf),
            getString(R.string.vendor_eleven), getString(R.string.vendor_openai),
            getString(R.string.vendor_dashscope), getString(R.string.vendor_gemini),
            getString(R.string.vendor_soniox), getString(R.string.vendor_sensevoice), getString(R.string.vendor_paraformer), getString(R.string.vendor_zipformer)
        )

        tvAsrVendor.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val curIdx = vendorOrder.indexOf(prefs.asrVendor).coerceAtLeast(0)
            showSingleChoiceDialog(
                titleResId = R.string.label_asr_vendor,
                items = vendorItems.toTypedArray(),
                currentIndex = curIdx
            ) { selectedIdx ->
                val vendor = vendorOrder.getOrNull(selectedIdx) ?: AsrVendor.Volc
                viewModel.updateVendor(vendor)
            }
        }
    }

    private fun setupSilenceDetection() {
        // Initial values
        switchAutoStopSilence.isChecked = prefs.autoStopOnSilenceEnabled
        sliderSilenceWindow.value = prefs.autoStopSilenceWindowMs.toFloat()
        sliderSilenceSensitivity.value = prefs.autoStopSilenceSensitivity.toFloat()

        // Switch listener
        switchAutoStopSilence.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            viewModel.updateAutoStopSilence(isChecked)
            // 若开启，则立即预加载全局 VAD，避免下次录音首次加载
            if (isChecked) {
                try { VadDetector.preload(applicationContext, 16000, prefs.autoStopSilenceSensitivity) } catch (_: Throwable) { }
            }
        }

        // Sliders
        setupSlider(sliderSilenceWindow) { value ->
            viewModel.updateSilenceWindow(value.toInt().coerceIn(300, 5000))
        }

        setupSlider(sliderSilenceSensitivity) { value ->
            viewModel.updateSilenceSensitivity(value.toInt().coerceIn(1, 10))
        }
        // 在松手时“立即生效”：重建全局 VAD，以新的灵敏度用于后续会话
        sliderSilenceSensitivity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { /* no-op */ }
            override fun onStopTrackingTouch(slider: Slider) {
                try {
                    if (prefs.autoStopOnSilenceEnabled) {
                        VadDetector.rebuildGlobal(applicationContext, 16000, prefs.autoStopSilenceSensitivity)
                        Toast.makeText(this@AsrSettingsActivity, R.string.toast_vad_sensitivity_applied, Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) { }
            }
        })

    }

    private fun setupVendorSpecificSettings() {
        setupVolcengineSettings()
        setupSiliconFlowSettings()
        setupElevenLabsSettings()
        setupOpenAISettings()
        setupDashScopeSettings()
        setupGeminiSettings()
        setupSonioxSettings()
        setupSenseVoiceSettings()
        setupParaformerSettings()
        setupZipformerSettings()
    }

    private fun setupVolcengineSettings() {
        // Pro：在火山引擎分组内注入“双重识别”
        try {
            com.brycewg.asrkb.ProUiInjector.injectIntoVolcStreamingExtras(this, groupVolc)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to inject pro UI (Volc streaming in-group)", e)
        }

        // EditTexts
        findViewById<EditText>(R.id.etAppKey).apply {
            setText(prefs.appKey)
            bindString { prefs.appKey = it }
        }
        findViewById<EditText>(R.id.etAccessKey).apply {
            setText(prefs.accessKey)
            bindString { prefs.accessKey = it }
        }

        // Switches
        findViewById<MaterialSwitch>(R.id.switchVolcStreaming).apply {
            isChecked = prefs.volcStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcStreaming(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcBidiStreaming).apply {
            isChecked = prefs.volcBidiStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcBidiStreaming(isChecked)
                // 若关闭双向流式，自动关闭并隐藏“二遍识别”
                if (!isChecked) {
                    try {
                        findViewById<MaterialSwitch>(R.id.switchVolcNonstream).isChecked = false
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to turn off two-pass switch when bidi disabled", e)
                    }
                }
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcDdc).apply {
            isChecked = prefs.volcDdcEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcDdc(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcVad).apply {
            isChecked = prefs.volcVadEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcVad(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcNonstream).apply {
            isChecked = prefs.volcNonstreamEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcNonstream(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcFirstCharAccel).apply {
            isChecked = prefs.volcFirstCharAccelEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcFirstCharAccel(isChecked)
            }
        }

        // Language selection
        setupVolcLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVolcGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/lexisharp-keyboard-providers-guide")
        }
    }

    private fun setupVolcLanguageSelection() {
        val langLabels = listOf(
            getString(R.string.volc_lang_auto), getString(R.string.volc_lang_en_us),
            getString(R.string.volc_lang_ja_jp), getString(R.string.volc_lang_id_id),
            getString(R.string.volc_lang_es_mx), getString(R.string.volc_lang_pt_br),
            getString(R.string.volc_lang_de_de), getString(R.string.volc_lang_fr_fr),
            getString(R.string.volc_lang_ko_kr), getString(R.string.volc_lang_fil_ph),
            getString(R.string.volc_lang_ms_my), getString(R.string.volc_lang_th_th),
            getString(R.string.volc_lang_ar_sa)
        )
        val langCodes = listOf(
            "", "en-US", "ja-JP", "id-ID", "es-MX", "pt-BR",
            "de-DE", "fr-FR", "ko-KR", "fil-PH", "ms-MY", "th-TH", "ar-SA"
        )
        val tvVolcLanguage = findViewById<TextView>(R.id.tvVolcLanguageValue)

        fun updateVolcLangSummary() {
            val idx = langCodes.indexOf(prefs.volcLanguage).coerceAtLeast(0)
            tvVolcLanguage.text = langLabels[idx]
        }

        updateVolcLangSummary()
        tvVolcLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.volcLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_volc_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                viewModel.updateVolcLanguage(code)
                updateVolcLangSummary()
            }
        }
    }

    private fun setupSiliconFlowSettings() {
        findViewById<EditText>(R.id.etSfApiKey).apply {
            setText(prefs.sfApiKey)
            bindString { prefs.sfApiKey = it }
        }
        // 模型选项：提供四种可用模型
        val tvSfModelValue = findViewById<TextView>(R.id.tvSfModelValue)
        val sfModels = listOf(
            "Qwen/Qwen3-Omni-30B-A3B-Instruct",
            "Qwen/Qwen3-Omni-30B-A3B-Thinking",
            "TeleAI/TeleSpeechASR",
            "FunAudioLLM/SenseVoiceSmall"
        )
        fun isOmni(model: String): Boolean {
            return model.startsWith("Qwen/Qwen3-Omni-30B-A3B-")
        }
        fun ensureValidModel(current: String): String {
            // 若现有值不在可选列表内，则根据是否可能是旧 Omni 开关选择合适默认
            return if (current in sfModels) current else {
                if (prefs.sfUseOmni) Prefs.DEFAULT_SF_OMNI_MODEL else Prefs.DEFAULT_SF_MODEL
            }
        }
        // 初始化显示与容错
        val initialModel = ensureValidModel(prefs.sfModel.ifBlank { Prefs.DEFAULT_SF_MODEL })
        if (initialModel != prefs.sfModel) prefs.sfModel = initialModel
        tvSfModelValue.text = initialModel

        tvSfModelValue.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val curIdx = sfModels.indexOf(prefs.sfModel).coerceAtLeast(0)
            showSingleChoiceDialog(
                titleResId = R.string.label_sf_model_select,
                items = sfModels.toTypedArray(),
                currentIndex = curIdx
            ) { which ->
                val selected = sfModels.getOrNull(which) ?: Prefs.DEFAULT_SF_MODEL
                if (selected != prefs.sfModel) {
                    prefs.sfModel = selected
                    tvSfModelValue.text = selected
                    // 根据模型自动决定是否显示 Omni 提示词框
                    viewModel.updateSfUseOmni(isOmni(selected))
                }
            }
        }

        findViewById<EditText>(R.id.etSfOmniPrompt).apply {
            setText(prefs.sfOmniPrompt)
            bindString { prefs.sfOmniPrompt = it }
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSfGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/lexisharp-keyboard-providers-guide")
        }
    }

    private fun setupElevenLabsSettings() {
        findViewById<EditText>(R.id.etElevenApiKey).apply {
            setText(prefs.elevenApiKey)
            bindString { prefs.elevenApiKey = it }
        }
        findViewById<EditText>(R.id.etElevenModel).apply {
            setText(prefs.elevenModelId)
            bindString { prefs.elevenModelId = it }
        }

        setupElevenLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnElevenGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/lexisharp-keyboard-providers-guide")
        }
    }

    private fun setupElevenLanguageSelection() {
        val elLabels = listOf(
            getString(R.string.eleven_lang_auto), getString(R.string.eleven_lang_zh),
            getString(R.string.eleven_lang_en), getString(R.string.eleven_lang_ja),
            getString(R.string.eleven_lang_ko), getString(R.string.eleven_lang_de),
            getString(R.string.eleven_lang_fr), getString(R.string.eleven_lang_es),
            getString(R.string.eleven_lang_pt), getString(R.string.eleven_lang_ru),
            getString(R.string.eleven_lang_it)
        )
        val elCodes = listOf("", "zh", "en", "ja", "ko", "de", "fr", "es", "pt", "ru", "it")
        val tvElevenLanguage = findViewById<TextView>(R.id.tvElevenLanguageValue)

        fun updateElSummary() {
            val idx = elCodes.indexOf(prefs.elevenLanguageCode).coerceAtLeast(0)
            tvElevenLanguage.text = elLabels[idx]
        }

        updateElSummary()
        tvElevenLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = elCodes.indexOf(prefs.elevenLanguageCode).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_eleven_language, elLabels.toTypedArray(), cur) { which ->
                val code = elCodes.getOrNull(which) ?: ""
                if (code != prefs.elevenLanguageCode) prefs.elevenLanguageCode = code
                updateElSummary()
            }
        }
    }

    private fun setupOpenAISettings() {
        findViewById<EditText>(R.id.etOpenAiAsrEndpoint).apply {
            setText(prefs.oaAsrEndpoint)
            bindString { prefs.oaAsrEndpoint = it }
        }
        findViewById<EditText>(R.id.etOpenAiApiKey).apply {
            setText(prefs.oaAsrApiKey)
            bindString { prefs.oaAsrApiKey = it }
        }
        findViewById<EditText>(R.id.etOpenAiModel).apply {
            setText(prefs.oaAsrModel)
            bindString { prefs.oaAsrModel = it }
        }
        findViewById<EditText>(R.id.etOpenAiPrompt).apply {
            setText(prefs.oaAsrPrompt)
            bindString { prefs.oaAsrPrompt = it }
        }

        findViewById<MaterialSwitch>(R.id.switchOpenAiUsePrompt).apply {
            isChecked = prefs.oaAsrUsePrompt
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateOpenAiUsePrompt(isChecked)
            }
        }

        setupOpenAILanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenAiGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/lexisharp-keyboard-providers-guide")
        }
    }

    private fun setupOpenAILanguageSelection() {
        val langLabels = listOf(
            getString(R.string.dash_lang_auto), getString(R.string.dash_lang_zh),
            getString(R.string.dash_lang_en), getString(R.string.dash_lang_ja),
            getString(R.string.dash_lang_de), getString(R.string.dash_lang_ko),
            getString(R.string.dash_lang_ru), getString(R.string.dash_lang_fr),
            getString(R.string.dash_lang_pt), getString(R.string.dash_lang_ar),
            getString(R.string.dash_lang_it), getString(R.string.dash_lang_es)
        )
        val langCodes = listOf("", "zh", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es")
        val tvOpenAiLanguage = findViewById<TextView>(R.id.tvOpenAiLanguageValue)

        fun updateOaLangSummary() {
            val idx = langCodes.indexOf(prefs.oaAsrLanguage).coerceAtLeast(0)
            tvOpenAiLanguage.text = langLabels[idx]
        }

        updateOaLangSummary()
        tvOpenAiLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.oaAsrLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_openai_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != prefs.oaAsrLanguage) prefs.oaAsrLanguage = code
                updateOaLangSummary()
            }
        }
    }

    private fun setupDashScopeSettings() {
        findViewById<EditText>(R.id.etDashApiKey).apply {
            setText(prefs.dashApiKey)
            bindString { prefs.dashApiKey = it }
        }
        findViewById<EditText>(R.id.etDashPrompt).apply {
            setText(prefs.dashPrompt)
            bindString { prefs.dashPrompt = it }
        }

        setupDashLanguageSelection()
        setupDashRegionSelection()

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchDashStreaming).apply {
            isChecked = prefs.dashStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateDashStreaming(isChecked)
            }
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDashGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/lexisharp-keyboard-providers-guide")
        }
    }

    private fun setupDashLanguageSelection() {
        val langLabels = listOf(
            getString(R.string.dash_lang_auto), getString(R.string.dash_lang_zh),
            getString(R.string.dash_lang_en), getString(R.string.dash_lang_ja),
            getString(R.string.dash_lang_de), getString(R.string.dash_lang_ko),
            getString(R.string.dash_lang_ru), getString(R.string.dash_lang_fr),
            getString(R.string.dash_lang_pt), getString(R.string.dash_lang_ar),
            getString(R.string.dash_lang_it), getString(R.string.dash_lang_es)
        )
        val langCodes = listOf("", "zh", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es")
        val tvDashLanguage = findViewById<TextView>(R.id.tvDashLanguageValue)

        fun updateDashLangSummary() {
            val idx = langCodes.indexOf(prefs.dashLanguage).coerceAtLeast(0)
            tvDashLanguage.text = langLabels[idx]
        }

        updateDashLangSummary()
        tvDashLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.dashLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_dash_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != prefs.dashLanguage) prefs.dashLanguage = code
                updateDashLangSummary()
            }
        }
    }

    private fun setupDashRegionSelection() {
        val regionLabels = listOf(
            getString(R.string.dash_region_cn),
            getString(R.string.dash_region_intl)
        )
        val regionValues = listOf("cn", "intl")
        val tvDashRegion = findViewById<TextView>(R.id.tvDashRegionValue)

        fun updateRegionSummary() {
            val idx = regionValues.indexOf(prefs.dashRegion.ifBlank { "cn" }).coerceAtLeast(0)
            tvDashRegion.text = regionLabels[idx]
        }

        updateRegionSummary()
        tvDashRegion.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = regionValues.indexOf(prefs.dashRegion.ifBlank { "cn" }).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_dash_region, regionLabels.toTypedArray(), cur) { which ->
                val value = regionValues.getOrNull(which) ?: "cn"
                if (value != prefs.dashRegion) prefs.dashRegion = value
                updateRegionSummary()
            }
        }
    }

    private fun setupGeminiSettings() {
        findViewById<EditText>(R.id.etGeminiApiKey).apply {
            setText(prefs.gemApiKey)
            bindString { prefs.gemApiKey = it }
        }
        findViewById<EditText>(R.id.etGeminiModel).apply {
            setText(prefs.gemModel)
            bindString { prefs.gemModel = it }
        }
        findViewById<EditText>(R.id.etGeminiPrompt).apply {
            setText(prefs.gemPrompt)
            bindString { prefs.gemPrompt = it }
        }

        findViewById<MaterialSwitch>(R.id.switchGeminiDisableThinking).apply {
            isChecked = prefs.geminiDisableThinking
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                prefs.geminiDisableThinking = isChecked
            }
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGeminiGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/lexisharp-keyboard-providers-guide")
        }
    }

    private fun setupSonioxSettings() {
        findViewById<EditText>(R.id.etSonioxApiKey).apply {
            setText(prefs.sonioxApiKey)
            bindString { prefs.sonioxApiKey = it }
        }

        findViewById<MaterialSwitch>(R.id.switchSonioxStreaming).apply {
            isChecked = prefs.sonioxStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSonioxStreaming(isChecked)
            }
        }

        setupSonioxLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSonioxGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://brycewg.notion.site/lexisharp-keyboard-providers-guide")
        }
    }

    private fun setupSonioxLanguageSelection() {
        val sonioxLangLabels = listOf(
            getString(R.string.soniox_lang_auto), getString(R.string.soniox_lang_en),
            getString(R.string.soniox_lang_zh), getString(R.string.soniox_lang_ja),
            getString(R.string.soniox_lang_ko), getString(R.string.soniox_lang_es),
            getString(R.string.soniox_lang_pt), getString(R.string.soniox_lang_de),
            getString(R.string.soniox_lang_fr), getString(R.string.soniox_lang_id),
            getString(R.string.soniox_lang_ru), getString(R.string.soniox_lang_ar),
            getString(R.string.soniox_lang_hi), getString(R.string.soniox_lang_vi),
            getString(R.string.soniox_lang_th), getString(R.string.soniox_lang_ms),
            getString(R.string.soniox_lang_fil)
        )
        val sonioxLangCodes = listOf(
            "", "en", "zh", "ja", "ko", "es", "pt", "de",
            "fr", "id", "ru", "ar", "hi", "vi", "th", "ms", "fil"
        )
        val tvSonioxLanguage = findViewById<TextView>(R.id.tvSonioxLanguageValue)

        fun updateSonioxLangSummary() {
            val selected = prefs.getSonioxLanguages()
            if (selected.isEmpty()) {
                tvSonioxLanguage.text = getString(R.string.soniox_lang_auto)
                return
            }
            val names = selected.mapNotNull { code ->
                val idx = sonioxLangCodes.indexOf(code)
                if (idx >= 0) sonioxLangLabels[idx] else null
            }
            tvSonioxLanguage.text = if (names.isEmpty()) {
                getString(R.string.soniox_lang_auto)
            } else {
                names.joinToString(separator = "、")
            }
        }

        updateSonioxLangSummary()
        tvSonioxLanguage.setOnClickListener {
            val saved = prefs.getSonioxLanguages()
            val checked = BooleanArray(sonioxLangCodes.size) { idx ->
                if (idx == 0) saved.isEmpty() else sonioxLangCodes[idx] in saved
            }
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.label_soniox_language)
                .setMultiChoiceItems(sonioxLangLabels.toTypedArray(), checked) { _, which, isChecked ->
                    if (which == 0) {
                        if (isChecked) {
                            for (i in 1 until checked.size) checked[i] = false
                        }
                    } else if (isChecked) {
                        checked[0] = false
                    }
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val codes = mutableListOf<String>()
                    for (i in checked.indices) {
                        if (checked[i]) {
                            val code = sonioxLangCodes[i]
                            if (code.isNotEmpty()) codes.add(code)
                        }
                    }
                    viewModel.updateSonioxLanguages(codes)
                    updateSonioxLangSummary()
                }
                .setNegativeButton(R.string.btn_cancel, null)
            builder.show()
        }
    }

    private fun setupSenseVoiceSettings() {
        // Model variant
        setupSvModelVariantSelection()

        // Language
        setupSvLanguageSelection()

        // Thread count
        findViewById<Slider>(R.id.sliderSvThreads).apply {
            value = prefs.svNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != prefs.svNumThreads) {
                        viewModel.updateSvNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            })
        }

        // Switches
        findViewById<MaterialSwitch>(R.id.switchSvUseItn).apply {
            isChecked = prefs.svUseItn
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSvUseItn(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchSvPreload).apply {
            isChecked = prefs.svPreloadEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSvPreload(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchSvPseudoStreaming).apply {
            isChecked = prefs.svPseudoStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSvPseudoStreaming(isChecked)
            }
        }

        // Keep alive
        setupSvKeepAliveSelection()

        // Download/Clear buttons
        setupSvDownloadButtons()
    }

    private fun setupParaformerSettings() {
        // 变体选择（四种）
        val tvPfVariant = findViewById<TextView>(R.id.tvPfModelVariantValue)
        val btnPfDownload = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfDownloadModel)
        val variantLabels = listOf(
            getString(R.string.pf_variant_bilingual_int8),
            getString(R.string.pf_variant_bilingual_fp32),
            getString(R.string.pf_variant_trilingual_int8),
            getString(R.string.pf_variant_trilingual_fp32)
        )
        val variantCodes = listOf(
            "bilingual-int8", "bilingual-fp32", "trilingual-int8", "trilingual-fp32"
        )
        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(prefs.pfModelVariant).coerceAtLeast(0)
            tvPfVariant.text = variantLabels[idx]
        }
        updateVariantSummary()
        tvPfVariant.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(prefs.pfModelVariant).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_pf_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "bilingual-int8"
                if (code != prefs.pfModelVariant) {
                    viewModel.updatePfModelVariant(code)
                }
                updateVariantSummary()
                updatePfDownloadUiVisibility()
            }
        }

        // 保留时长
        val tvKeep = findViewById<TextView>(R.id.tvPfKeepAliveValue)
        fun updateKeepAliveSummary() {
            val values = listOf(0, 5, 15, 30, -1)
            val labels = listOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val idx = values.indexOf(prefs.pfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvKeep.text = labels[idx]
        }
        updateKeepAliveSummary()
        tvKeep.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val labels = arrayOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val values = listOf(0, 5, 15, 30, -1)
            val cur = values.indexOf(prefs.pfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            showSingleChoiceDialog(R.string.label_pf_keep_alive, labels, cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != prefs.pfKeepAliveMinutes) {
                    prefs.pfKeepAliveMinutes = vv
                }
                updateKeepAliveSummary()
            }
        }

        // 线程数滑块（1-8）
        findViewById<com.google.android.material.slider.Slider>(R.id.sliderPfThreads).apply {
            value = prefs.pfNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != prefs.pfNumThreads) {
                        viewModel.updatePfNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
            })
        }

        // 首次显示时加载模型
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPfPreload).apply {
            isChecked = prefs.pfPreloadEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updatePfPreload(isChecked)
            }
        }

        // ITN 开关
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPfItn).apply {
            isChecked = try { prefs.pfUseItn } catch (_: Throwable) { false }
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updatePfUseItn(isChecked)
            }
        }

        // 下载/清理
        setupPfDownloadButtons()
    }

    private fun setupPfDownloadButtons() {
        val btnDl = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfDownloadModel)
        val btnClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfClearModel)
        val tvStatus = findViewById<TextView>(R.id.tvPfDownloadStatus)
        btnDl.setOnClickListener { v ->
            v.isEnabled = false
            tvStatus.text = ""
            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            val variant = prefs.pfModelVariant
            val isTri = variant.startsWith("trilingual")
            val urlOfficial = if (isTri) {
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en.tar.bz2"
            } else {
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        // 统一下载服务
                        ModelDownloadService.startDownload(this, url, variant, "paraformer")
                        tvStatus.text = getString(R.string.pf_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start paraformer download", e)
                        tvStatus.text = getString(R.string.pf_download_status_failed)
                    } finally { v.isEnabled = true }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.pf_clear_confirm_title)
                .setMessage(R.string.pf_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val base = getExternalFilesDir(null) ?: filesDir
                            val root = java.io.File(base, "paraformer")
                            val group = if (prefs.pfModelVariant.startsWith("trilingual")) "trilingual" else "bilingual"
                            val outDir = java.io.File(root, group)
                            if (outDir.exists()) withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            try { com.brycewg.asrkb.asr.unloadParaformerRecognizer() } catch (_: Throwable) { }
                            tvStatus.text = getString(R.string.pf_clear_done)
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear paraformer model", e)
                            tvStatus.text = getString(R.string.pf_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updatePfDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun updatePfDownloadUiVisibility() {
        val ready = viewModel.checkPfModelDownloaded(this)
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfDownloadModel)
        val btnClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPfClearModel)
        val tv = findViewById<TextView>(R.id.tvPfDownloadStatus)
        btn.visibility = if (ready) View.GONE else View.VISIBLE
        btnClear.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready && tv.text.isNullOrBlank()) {
            tv.text = getString(R.string.pf_download_status_done)
        }
    }

    // ===== Zipformer (Local streaming) =====
    private fun setupZipformerSettings() {
        // 变体选择（八种）
        val tvZfVariant = findViewById<TextView>(R.id.tvZfModelVariantValue)
        val variantLabels = listOf(
            getString(R.string.zf_variant_zh_xl_int8_20250630),
            getString(R.string.zf_variant_zh_xl_fp16_20250630),
            getString(R.string.zf_variant_zh_int8_20250630),
            getString(R.string.zf_variant_zh_fp16_20250630),
            getString(R.string.zf_variant_bi_int8_20230220),
            getString(R.string.zf_variant_bi_fp32_20230220),
            getString(R.string.zf_variant_small_bi_int8_20230216),
            getString(R.string.zf_variant_small_bi_fp32_20230216)
        )
        val variantCodes = listOf(
            "zh-xl-int8-20250630",
            "zh-xl-fp16-20250630",
            "zh-int8-20250630",
            "zh-fp16-20250630",
            "bi-20230220-int8",
            "bi-20230220-fp32",
            "bi-small-20230216-int8",
            "bi-small-20230216-fp32"
        )
        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(prefs.zfModelVariant).coerceAtLeast(0)
            tvZfVariant.text = variantLabels[idx]
        }
        updateVariantSummary()
        tvZfVariant.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(prefs.zfModelVariant).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_zf_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "zh-xl-int8-20250630"
                if (code != prefs.zfModelVariant) {
                    viewModel.updateZfModelVariant(code)
                }
                updateVariantSummary()
                updateZfDownloadUiVisibility()
            }
        }

        // 保留时长
        val tvKeep = findViewById<TextView>(R.id.tvZfKeepAliveValue)
        fun updateKeepAliveSummary() {
            val values = listOf(0, 5, 15, 30, -1)
            val labels = listOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val idx = values.indexOf(prefs.zfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvKeep.text = labels[idx]
        }
        updateKeepAliveSummary()
        tvKeep.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val labels = arrayOf(
                getString(R.string.sv_keep_alive_immediate),
                getString(R.string.sv_keep_alive_5m),
                getString(R.string.sv_keep_alive_15m),
                getString(R.string.sv_keep_alive_30m),
                getString(R.string.sv_keep_alive_always)
            )
            val values = listOf(0, 5, 15, 30, -1)
            val cur = values.indexOf(prefs.zfKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            showSingleChoiceDialog(R.string.label_zf_keep_alive, labels, cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != prefs.zfKeepAliveMinutes) {
                    prefs.zfKeepAliveMinutes = vv
                }
                updateKeepAliveSummary()
            }
        }

        // 线程数滑块（1-8）
        findViewById<com.google.android.material.slider.Slider>(R.id.sliderZfThreads).apply {
            value = prefs.zfNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != prefs.zfNumThreads) {
                        viewModel.updateZfNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) = hapticTapIfEnabled(slider)
            })
        }

        // 首次显示时加载模型
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchZfPreload).apply {
            isChecked = prefs.zfPreloadEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateZfPreload(isChecked)
            }
        }

        // ITN 开关
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchZfItn).apply {
            isChecked = prefs.zfUseItn
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateZfUseItn(isChecked)
            }
        }

        setupZfDownloadButtons()
    }

    private fun setupZfDownloadButtons() {
        val btnDl = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZfDownloadModel)
        val btnClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZfClearModel)
        val tvStatus = findViewById<TextView>(R.id.tvZfDownloadStatus)
        btnDl.setOnClickListener { v ->
            v.isEnabled = false
            tvStatus.text = ""
            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            val variant = prefs.zfModelVariant
            val urlOfficial = when (variant) {
                "zh-xl-int8-20250630" -> "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30.tar.bz2"
                "zh-xl-fp16-20250630" -> "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-xlarge-fp16-2025-06-30.tar.bz2"
                "zh-int8-20250630" -> "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"
                "zh-fp16-20250630" -> "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-fp16-2025-06-30.tar.bz2"
                "bi-20230220-int8", "bi-20230220-fp32" -> "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2"
                else -> "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        ModelDownloadService.startDownload(this, url, variant, "zipformer")
                        tvStatus.text = getString(R.string.zf_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start zipformer download", e)
                        tvStatus.text = getString(R.string.zf_download_status_failed)
                    } finally { v.isEnabled = true }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.zf_clear_confirm_title)
                .setMessage(R.string.zf_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val base = getExternalFilesDir(null) ?: filesDir
                            val root = java.io.File(base, "zipformer")
                            val outDir = when {
                                prefs.zfModelVariant.startsWith("zh-xl-") -> java.io.File(root, "zh-xlarge-2025-06-30")
                                prefs.zfModelVariant.startsWith("zh-") -> java.io.File(root, "zh-2025-06-30")
                                prefs.zfModelVariant.startsWith("bi-small-") -> java.io.File(root, "small-bilingual-zh-en-2023-02-16")
                                else -> java.io.File(root, "bilingual-zh-en-2023-02-20")
                            }
                            if (outDir.exists()) withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            tvStatus.text = getString(R.string.zf_clear_done)
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear zipformer model", e)
                            tvStatus.text = getString(R.string.zf_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updateZfDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun updateZfDownloadUiVisibility() {
        val ready = viewModel.checkZfModelDownloaded(this)
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZfDownloadModel)
        val btnClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZfClearModel)
        val tv = findViewById<TextView>(R.id.tvZfDownloadStatus)
        btn.visibility = if (ready) View.GONE else View.VISIBLE
        btnClear.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready && tv.text.isNullOrBlank()) {
            tv.text = getString(R.string.zf_download_status_done)
        }
    }

    private fun setupSvModelVariantSelection() {
        val variantLabels = listOf(
            getString(R.string.sv_model_small_int8),
            getString(R.string.sv_model_small_full)
        )
        val variantCodes = listOf("small-int8", "small-full")
        val tvSvModelVariant = findViewById<TextView>(R.id.tvSvModelVariantValue)
        val btnSvDownload = findViewById<MaterialButton>(R.id.btnSvDownloadModel)

        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(prefs.svModelVariant).coerceAtLeast(0)
            tvSvModelVariant.text = variantLabels[idx]
        }

        fun updateDownloadButtonText() {
            val variant = prefs.svModelVariant
            val text = if (variant == "small-full") {
                getString(R.string.btn_sv_download_model_full)
            } else {
                getString(R.string.btn_sv_download_model_int8)
            }
            btnSvDownload.text = text
        }

        updateVariantSummary()
        updateDownloadButtonText()

        tvSvModelVariant.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(prefs.svModelVariant).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_sv_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "small-int8"
                if (code != prefs.svModelVariant) {
                    viewModel.updateSvModelVariant(code)
                }
                updateVariantSummary()
                updateDownloadButtonText()
                updateSvDownloadUiVisibility()
            }
        }
    }

    private fun setupSvLanguageSelection() {
        val labels = listOf(
            getString(R.string.sv_lang_auto), getString(R.string.sv_lang_zh),
            getString(R.string.sv_lang_en), getString(R.string.sv_lang_ja),
            getString(R.string.sv_lang_ko), getString(R.string.sv_lang_yue)
        )
        val codes = listOf("auto", "zh", "en", "ja", "ko", "yue")
        val tvSvLanguage = findViewById<TextView>(R.id.tvSvLanguageValue)

        fun updateSvLangSummary() {
            val idx = codes.indexOf(prefs.svLanguage).coerceAtLeast(0)
            tvSvLanguage.text = labels[idx]
        }

        updateSvLangSummary()
        tvSvLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = codes.indexOf(prefs.svLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_sv_language, labels.toTypedArray(), cur) { which ->
                val code = codes.getOrNull(which) ?: "auto"
                if (code != prefs.svLanguage) {
                    viewModel.updateSvLanguage(code)
                }
                updateSvLangSummary()
            }
        }
    }

    private fun setupSvKeepAliveSelection() {
        val labels = listOf(
            getString(R.string.sv_keep_alive_immediate),
            getString(R.string.sv_keep_alive_5m),
            getString(R.string.sv_keep_alive_15m),
            getString(R.string.sv_keep_alive_30m),
            getString(R.string.sv_keep_alive_always)
        )
        val values = listOf(0, 5, 15, 30, -1)
        val tvSvKeepAlive = findViewById<TextView>(R.id.tvSvKeepAliveValue)

        fun updateKeepAliveSummary() {
            val idx = values.indexOf(prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvSvKeepAlive.text = labels[idx]
        }

        updateKeepAliveSummary()
        tvSvKeepAlive.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = values.indexOf(prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            showSingleChoiceDialog(R.string.label_sv_keep_alive, labels.toTypedArray(), cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != prefs.svKeepAliveMinutes) {
                    viewModel.updateSvKeepAlive(vv)
                }
                updateKeepAliveSummary()
            }
        }
    }

    private fun setupSvDownloadButtons() {
        val btnSvDownload = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
        val btnSvClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
        val tvSvDownloadStatus = findViewById<TextView>(R.id.tvSvDownloadStatus)

        btnSvDownload.setOnClickListener { v ->
            v.isEnabled = false
            tvSvDownloadStatus.text = ""

            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            val variant = prefs.svModelVariant
            val urlOfficial = if (variant == "small-full") {
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
            } else {
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        ModelDownloadService.startDownload(this, url, variant)
                        tvSvDownloadStatus.text = getString(R.string.sv_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start model download", e)
                        tvSvDownloadStatus.text = getString(R.string.sv_download_status_failed)
                    } finally {
                        v.isEnabled = true
                    }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnSvClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.sv_clear_confirm_title)
                .setMessage(R.string.sv_clear_confirm_message)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    v.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val base = getExternalFilesDir(null) ?: filesDir
                            val variant = prefs.svModelVariant
                            val outDirRoot = File(base, "sensevoice")
                            val outDir = if (variant == "small-full") {
                                File(outDirRoot, "small-full")
                            } else {
                                File(outDirRoot, "small-int8")
                            }
                            if (outDir.exists()) {
                                withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            }
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (e: Throwable) {
                                android.util.Log.e(TAG, "Failed to unload SenseVoice recognizer", e)
                            }
                            tvSvDownloadStatus.text = getString(R.string.sv_clear_done)
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear model", e)
                            tvSvDownloadStatus.text = getString(R.string.sv_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updateSvDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateVendorSummary(state.selectedVendor)
                updateVendorVisibility(state)
                updateSilenceOptionsVisibility(state.autoStopSilenceEnabled)
                updateSfOmniVisibility(state.sfUseOmni)
                updateOpenAiPromptVisibility(state.oaAsrUsePrompt)
                updateVolcStreamOptionsVisibility(state.volcStreamingEnabled)
                updateVolcTwoPassVisibility(state.volcStreamingEnabled, state.volcBidiStreamingEnabled)
            }
        }
    }

    private fun updateVendorSummary(vendor: AsrVendor) {
        val vendorOrder = listOf(
            AsrVendor.Volc, AsrVendor.SiliconFlow, AsrVendor.ElevenLabs,
            AsrVendor.OpenAI, AsrVendor.DashScope, AsrVendor.Gemini,
            AsrVendor.Soniox, AsrVendor.SenseVoice, AsrVendor.Paraformer, AsrVendor.Zipformer
        )
        val vendorItems = listOf(
            getString(R.string.vendor_volc), getString(R.string.vendor_sf),
            getString(R.string.vendor_eleven), getString(R.string.vendor_openai),
            getString(R.string.vendor_dashscope), getString(R.string.vendor_gemini),
            getString(R.string.vendor_soniox), getString(R.string.vendor_sensevoice), getString(R.string.vendor_paraformer), getString(R.string.vendor_zipformer)
        )
        val idx = vendorOrder.indexOf(vendor).coerceAtLeast(0)
        tvAsrVendor.text = vendorItems[idx]
    }

    private fun updateVendorVisibility(state: AsrSettingsUiState) {
        val visMap = mapOf(
            AsrVendor.Volc to listOf(titleVolc, groupVolc),
            AsrVendor.SiliconFlow to listOf(titleSf, groupSf),
            AsrVendor.ElevenLabs to listOf(titleEleven, groupEleven),
            AsrVendor.OpenAI to listOf(titleOpenAi, groupOpenAi),
            AsrVendor.DashScope to listOf(titleDash, groupDash),
            AsrVendor.Gemini to listOf(titleGemini, groupGemini),
            AsrVendor.Soniox to listOf(titleSoniox, groupSoniox),
            AsrVendor.SenseVoice to listOf(titleSenseVoice, groupSenseVoice),
            AsrVendor.Paraformer to listOf(titleParaformer, groupParaformer),
            AsrVendor.Zipformer to listOf(titleZipformer, groupZipformer)
        )
        visMap.forEach { (vendor, views) ->
            val vis = if (vendor == state.selectedVendor) View.VISIBLE else View.GONE
            views.forEach { v ->
                if (v.visibility != vis) v.visibility = vis
            }
        }
    }

    private fun updateSilenceOptionsVisibility(enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (tvSilenceWindowLabel.visibility != vis) tvSilenceWindowLabel.visibility = vis
        if (sliderSilenceWindow.visibility != vis) sliderSilenceWindow.visibility = vis
        if (tvSilenceSensitivityLabel.visibility != vis) tvSilenceSensitivityLabel.visibility = vis
        if (sliderSilenceSensitivity.visibility != vis) sliderSilenceSensitivity.visibility = vis
    }

    private fun updateSfOmniVisibility(enabled: Boolean) {
        val til = findViewById<View>(R.id.tilSfOmniPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateOpenAiPromptVisibility(enabled: Boolean) {
        val til = findViewById<View>(R.id.tilOpenAiPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateVolcStreamOptionsVisibility(enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        fun setIfChanged(v: View) { if (v.visibility != vis) v.visibility = vis }
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcVad))
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcFirstCharAccel))
        setIfChanged(findViewById<TextView>(R.id.tvVolcLanguageValue))
        setIfChanged(findViewById<View>(R.id.tvVolcLanguageLabel))
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcBidiStreaming))
    }

    private fun updateVolcTwoPassVisibility(streamingEnabled: Boolean, bidiEnabled: Boolean) {
        val vis = if (streamingEnabled && bidiEnabled) View.VISIBLE else View.GONE
        val v = findViewById<View>(R.id.switchVolcNonstream)
        if (v.visibility != vis) v.visibility = vis
    }

    private fun updateSvDownloadUiVisibility() {
        val ready = viewModel.checkSvModelDownloaded(this)
        val btn = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
        val btnClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
        val tv = findViewById<TextView>(R.id.tvSvDownloadStatus)
        btn.visibility = if (ready) View.GONE else View.VISIBLE
        btnClear.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready && tv.text.isNullOrBlank()) {
            tv.text = getString(R.string.sv_download_status_done)
        }
    }

    // ====== Helper Functions ======

    /**
     * Reusable function for showing single-choice dialogs.
     * Reduces repetitive code across vendor language selections.
     */
    private fun showSingleChoiceDialog(
        titleResId: Int,
        items: Array<String>,
        currentIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleResId)
            .setSingleChoiceItems(items, currentIndex) { dlg, which ->
                onSelected(which)
                dlg.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Extension function to bind EditText changes to Prefs.
     * Simplifies two-way binding setup.
     */
    private fun EditText.bindString(onChange: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
    }

    /**
     * Helper to setup slider with haptic feedback and value change listener.
     */
    private fun setupSlider(slider: Slider, onValueChange: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                onValueChange(value)
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (prefs.micHapticEnabled) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to perform haptic feedback", e)
        }
    }

    private fun openUrlSafely(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to open url: $url", e)
            Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
        }
    }


    companion object {
        private const val TAG = "AsrSettingsActivity"
    }
}
