package com.brycewg.asrkb.ui.settings.other

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OtherSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OtherSettingsActivity"
    }

    private lateinit var viewModel: OtherSettingsViewModel
    private lateinit var prefs: Prefs

    // Flag to prevent circular updates when programmatically setting text
    private var updatingFieldsFromViewModel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_other_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<android.view.View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)
        viewModel = OtherSettingsViewModel(prefs)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_other_settings)
        toolbar.setNavigationOnClickListener { finish() }

        setupPunctuationButtons()
        setupSpeechPresets()
        setupSyncClipboard()

        // Observe ViewModel state
        observeViewModel()
    }

    // ========== Punctuation Buttons ==========

    private fun setupPunctuationButtons() {
        val etP1 = findViewById<EditText>(R.id.etPunct1)
        val etP2 = findViewById<EditText>(R.id.etPunct2)
        val etP3 = findViewById<EditText>(R.id.etPunct3)
        val etP4 = findViewById<EditText>(R.id.etPunct4)

        etP1.setText(prefs.punct1)
        etP2.setText(prefs.punct2)
        etP3.setText(prefs.punct3)
        etP4.setText(prefs.punct4)

        fun EditText.bind(onChange: (String) -> Unit) {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            })
        }

        etP1.bind { prefs.punct1 = it }
        etP2.bind { prefs.punct2 = it }
        etP3.bind { prefs.punct3 = it }
        etP4.bind { prefs.punct4 = it }
    }

    // ========== Speech Presets ==========

    private fun setupSpeechPresets() {
        val tvSpeechPresets = findViewById<TextView>(R.id.tvSpeechPresetsValue)
        val tilSpeechPresetName = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSpeechPresetName)
        val tilSpeechPresetContent = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSpeechPresetContent)
        val etSpeechPresetName = findViewById<TextInputEditText>(R.id.etSpeechPresetName)
        val etSpeechPresetContent = findViewById<TextInputEditText>(R.id.etSpeechPresetContent)
        val btnSpeechPresetAdd = findViewById<MaterialButton>(R.id.btnSpeechPresetAdd)
        val btnSpeechPresetDelete = findViewById<MaterialButton>(R.id.btnSpeechPresetDelete)

        // Setup preset name field
        etSpeechPresetName?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFieldsFromViewModel) return
                val value = s?.toString() ?: ""
                viewModel.updateActivePresetName(value)
            }
        })

        // Setup preset content field
        etSpeechPresetContent?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFieldsFromViewModel) return
                val value = s?.toString() ?: ""
                viewModel.updateActivePresetContent(value)
            }
        })

        // Setup preset selector
        tvSpeechPresets.setOnClickListener {
            val state = viewModel.speechPresetsState.value
            if (state.presets.isEmpty()) return@setOnClickListener

            val displayNames = state.presets.map {
                it.name.ifBlank { getString(R.string.speech_preset_untitled) }
            }
            val idx = state.presets.indexOfFirst { it.id == state.activePresetId }
                .let { if (it < 0) 0 else it }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.label_speech_preset_section)
                .setSingleChoiceItems(displayNames.toTypedArray(), idx) { dlg, which ->
                    val preset = state.presets.getOrNull(which)
                    if (preset != null) {
                        viewModel.setActivePreset(preset.id)
                    }
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // Setup add button
        btnSpeechPresetAdd?.setOnClickListener {
            val state = viewModel.speechPresetsState.value
            val defaultName = getString(R.string.speech_preset_default_name, state.presets.size + 1)
            viewModel.addSpeechPreset(defaultName)

            // Focus on name field after adding
            etSpeechPresetName.post {
                etSpeechPresetName.requestFocus()
                etSpeechPresetName.setSelection(etSpeechPresetName.text?.length ?: 0)
            }
            Toast.makeText(this, getString(R.string.toast_speech_preset_added), Toast.LENGTH_SHORT).show()
        }

        // Setup delete button
        btnSpeechPresetDelete?.setOnClickListener {
            val state = viewModel.speechPresetsState.value
            if (state.presets.isEmpty()) return@setOnClickListener

            val current = state.currentPreset ?: return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_speech_preset_delete_title)
                .setMessage(getString(
                    R.string.dialog_speech_preset_delete_message,
                    current.name.ifBlank { getString(R.string.speech_preset_untitled) }
                ))
                .setPositiveButton(R.string.btn_speech_preset_delete) { _, _ ->
                    viewModel.deleteSpeechPreset(current.id)
                    Toast.makeText(this, getString(R.string.toast_speech_preset_deleted), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun observeViewModel() {
        // Observe speech presets state
        lifecycleScope.launch {
            viewModel.speechPresetsState.collect { state ->
                updateSpeechPresetsUI(state)
            }
        }

        // Observe sync clipboard state
        lifecycleScope.launch {
            viewModel.syncClipboardState.collect { state ->
                updateSyncClipboardUI()
            }
        }
    }

    private fun updateSpeechPresetsUI(state: OtherSettingsViewModel.SpeechPresetsState) {
        val tvSpeechPresets = findViewById<TextView>(R.id.tvSpeechPresetsValue)
        val tilSpeechPresetName = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSpeechPresetName)
        val tilSpeechPresetContent = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSpeechPresetContent)
        val etSpeechPresetName = findViewById<TextInputEditText>(R.id.etSpeechPresetName)
        val etSpeechPresetContent = findViewById<TextInputEditText>(R.id.etSpeechPresetContent)
        val spSpeechPresets = findViewById<android.widget.Spinner>(R.id.spSpeechPresets)
        val btnSpeechPresetDelete = findViewById<MaterialButton>(R.id.btnSpeechPresetDelete)

        updatingFieldsFromViewModel = true

        // Update display text
        if (state.presets.isNotEmpty()) {
            val displayName = state.currentPreset?.name?.trim()?.ifEmpty {
                getString(R.string.speech_preset_untitled)
            } ?: getString(R.string.speech_preset_untitled)
            tvSpeechPresets.text = displayName
        } else {
            tvSpeechPresets.text = getString(R.string.speech_preset_empty_placeholder)
        }

        // Update text fields
        val currentName = state.currentPreset?.name ?: ""
        if (etSpeechPresetName.text?.toString() != currentName) {
            etSpeechPresetName.setText(currentName)
        }

        val currentContent = state.currentPreset?.content ?: ""
        if (etSpeechPresetContent.text?.toString() != currentContent) {
            etSpeechPresetContent.setText(currentContent)
        }

        tilSpeechPresetName.error = null

        // Update enabled state
        val enable = state.isEnabled
        spSpeechPresets.isEnabled = enable
        tilSpeechPresetName.isEnabled = enable
        tilSpeechPresetContent.isEnabled = enable
        etSpeechPresetName.isEnabled = enable
        etSpeechPresetContent.isEnabled = enable
        btnSpeechPresetDelete.isEnabled = enable

        updatingFieldsFromViewModel = false
    }

    // ========== Sync Clipboard ==========

    private fun setupSyncClipboard() {
        val switchSync = findViewById<MaterialSwitch>(R.id.switchSyncClipboard)
        val layoutSync = findViewById<View>(R.id.layoutSyncClipboard)
        val etServer = findViewById<TextInputEditText>(R.id.etScServerBase)
        val etUser = findViewById<TextInputEditText>(R.id.etScUsername)
        val etPass = findViewById<TextInputEditText>(R.id.etScPassword)
        val switchAutoPull = findViewById<MaterialSwitch>(R.id.switchScAutoPull)
        val etInterval = findViewById<TextInputEditText>(R.id.etScPullInterval)
        val btnTestPull = findViewById<MaterialButton>(R.id.btnScTestPull)
        val btnProjectHome = findViewById<MaterialButton>(R.id.btnScProjectHome)

        // Initialize UI values
        switchSync.isChecked = prefs.syncClipboardEnabled
        etServer.setText(prefs.syncClipboardServerBase)
        etUser.setText(prefs.syncClipboardUsername)
        etPass.setText(prefs.syncClipboardPassword)
        switchAutoPull.isChecked = prefs.syncClipboardAutoPullEnabled
        etInterval.setText(prefs.syncClipboardPullIntervalSec.toString())

        refreshSyncVisibility(switchSync.isChecked, layoutSync)

        // Setup listeners
        switchSync.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSyncClipboardEnabled(checked)
            refreshSyncVisibility(checked, layoutSync)
        }

        switchAutoPull.setOnCheckedChangeListener { _, checked ->
            viewModel.updateSyncClipboardAutoPullEnabled(checked)
        }

        etServer.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateSyncClipboardServerBase(s?.toString() ?: "")
            }
        })

        etUser.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateSyncClipboardUsername(s?.toString() ?: "")
            }
        })

        etPass.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateSyncClipboardPassword(s?.toString() ?: "")
            }
        })

        etInterval.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = (s?.toString() ?: "").trim()
                val sec = v.toIntOrNull()?.coerceIn(1, 600)
                if (sec != null) {
                    viewModel.updateSyncClipboardPullIntervalSec(sec)
                }
            }
        })

        btnTestPull.setOnClickListener {
            testClipboardSync()
        }

        btnProjectHome.setOnClickListener {
            openProjectHomePage()
        }
    }

    private fun updateSyncClipboardUI() {
        // Currently, sync clipboard state is updated via direct UI bindings
        // This method is kept for potential future reactive updates
    }

    private fun refreshSyncVisibility(enabled: Boolean, layoutSync: View) {
        layoutSync.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun testClipboardSync() {
        // Test clipboard sync by performing a GET request without updating system clipboard
        val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(this, prefs, lifecycleScope)
        lifecycleScope.launch(Dispatchers.IO) {
            val (ok, _) = try {
                mgr.pullNow(updateClipboard = false)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to test clipboard sync", e)
                false to null
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(
                        this@OtherSettingsActivity,
                        getString(R.string.sc_test_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@OtherSettingsActivity,
                        getString(R.string.sc_test_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun openProjectHomePage() {
        try {
            val uri = android.net.Uri.parse("https://github.com/Jeric-X/SyncClipboard")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open project home page", e)
            Toast.makeText(
                this@OtherSettingsActivity,
                getString(R.string.sc_open_browser_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
