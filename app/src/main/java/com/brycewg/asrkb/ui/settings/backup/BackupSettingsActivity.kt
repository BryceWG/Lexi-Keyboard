package com.brycewg.asrkb.ui.settings.backup

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupSettingsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BackupSettingsActivity"
    }

    private lateinit var prefs: Prefs
    private val http by lazy { OkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<android.view.View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_backup_settings)
        toolbar.setNavigationOnClickListener { finish() }

        setupFileSection()
        setupWebdavSection()

        // Pro: 注入自动备份等专属 UI
        try {
            val root = findViewById<android.view.View>(android.R.id.content)
            com.brycewg.asrkb.ProUiInjector.injectIntoBackupSettings(this, root)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject pro UI", e)
        }
    }

    // ================= 文件导入/导出 =================
    private fun setupFileSection() {
        val btnExport = findViewById<MaterialButton>(R.id.btnExportToFile)
        val btnImport = findViewById<MaterialButton>(R.id.btnImportFromFile)

        val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) exportSettings(uri)
        }

        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) importSettings(uri)
        }

        btnExport.setOnClickListener {
            val fileName = "asr_keyboard_settings_" +
                SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) +
                ".json"
            exportLauncher.launch(fileName)
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
    }

    private fun exportSettings(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                val jsonString = com.brycewg.asrkb.ProUiInjector.buildBackupJson(this, prefs)
                os.write(jsonString.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val name = uri.lastPathSegment ?: "settings.json"
            Toast.makeText(this, getString(R.string.toast_export_success, name), Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Settings exported successfully to $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings", e)
            Toast.makeText(this, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSettings(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: ""

            val success = prefs.importJsonString(json)
            if (success) {
                try { com.brycewg.asrkb.ProUiInjector.applyProImport(this, json) } catch (e: Exception) { Log.e(TAG, "Failed to apply pro import", e) }
                // 导入完成后，通知 IME 即时刷新（包含高度与按钮交换等）
                try {
                    sendBroadcast(android.content.Intent(com.brycewg.asrkb.ime.AsrKeyboardService.ACTION_REFRESH_IME_UI))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send refresh broadcast", e)
                }
                Toast.makeText(this, getString(R.string.toast_import_success), Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Settings imported successfully from $uri")
            } else {
                Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Failed to import settings (invalid JSON or parsing error)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ================= WebDAV 同步 =================
    private fun setupWebdavSection() {
        val etUrl = findViewById<TextInputEditText>(R.id.etWebdavUrl)
        val etUser = findViewById<TextInputEditText>(R.id.etWebdavUsername)
        val etPass = findViewById<TextInputEditText>(R.id.etWebdavPassword)
        val btnUpload = findViewById<MaterialButton>(R.id.btnWebdavUpload)
        val btnDownload = findViewById<MaterialButton>(R.id.btnWebdavDownload)

        etUrl.setText(prefs.webdavUrl)
        etUser.setText(prefs.webdavUsername)
        etPass.setText(prefs.webdavPassword)

        etUrl.addTextChangedListener(SimpleTextWatcher { prefs.webdavUrl = it })
        etUser.addTextChangedListener(SimpleTextWatcher { prefs.webdavUsername = it })
        etPass.addTextChangedListener(SimpleTextWatcher { prefs.webdavPassword = it })

        btnUpload.setOnClickListener { uploadToWebdav() }
        btnDownload.setOnClickListener { downloadFromWebdav() }
    }

    private fun uploadToWebdav() {
        val rawUrl = prefs.webdavUrl.trim()
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_webdav_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val ok = WebDavBackupHelper.uploadSettings(this@BackupSettingsActivity, prefs)
            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_upload_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_upload_failed, "HTTP"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadFromWebdav() {
        val rawUrl = prefs.webdavUrl.trim()
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_webdav_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val text = WebDavBackupHelper.downloadSettings(prefs)
            val ok = text != null && prefs.importJsonString(text)
            withContext(Dispatchers.Main) {
                if (ok) {
                    try { com.brycewg.asrkb.ProUiInjector.applyProImport(this@BackupSettingsActivity, text!!) } catch (e: Exception) { Log.e(TAG, "Failed to apply pro import", e) }
                    try { sendBroadcast(android.content.Intent(com.brycewg.asrkb.ime.AsrKeyboardService.ACTION_REFRESH_IME_UI)) } catch (e: Exception) { Log.e(TAG, "Failed to send refresh broadcast", e) }
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_download_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_download_failed, getString(R.string.toast_import_failed)), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString() ?: "")
    }
}
