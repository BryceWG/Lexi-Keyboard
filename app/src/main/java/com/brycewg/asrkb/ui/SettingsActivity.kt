package com.brycewg.asrkb.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.setup.SetupState
import com.brycewg.asrkb.ui.setup.SetupStateMachine
import com.brycewg.asrkb.ui.update.UpdateChecker
import com.brycewg.asrkb.ui.update.ApkDownloadService
import com.brycewg.asrkb.ui.about.AboutActivity
import com.brycewg.asrkb.ui.settings.input.InputSettingsActivity
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsActivity
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsActivity
import com.brycewg.asrkb.ui.settings.other.OtherSettingsActivity
import com.brycewg.asrkb.ui.settings.floating.FloatingSettingsActivity
import com.brycewg.asrkb.ui.settings.backup.BackupSettingsActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import androidx.core.content.FileProvider

/**
 * 主设置页面
 *
 * 提供：
 * - 一键设置流程（基于状态机）
 * - 更新检查（通过 UpdateChecker）
 * - 设置导入/导出
 * - 子设置页导航
 * - 测试输入体验
 */
class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SettingsActivity"
        const val EXTRA_AUTO_SHOW_IME_PICKER = "extra_auto_show_ime_picker"
    }

    // 一键设置状态机
    private lateinit var setupStateMachine: SetupStateMachine

    // 更新检查器
    private lateinit var updateChecker: UpdateChecker

    // 无障碍服务状态（用于检测服务刚刚被启用）
    private var wasAccessibilityEnabled = false

    // Handler 用于延迟任务
    private val handler = Handler(Looper.getMainLooper())

    // IME 选择器相关状态（用于"外部切换"模式）
    private var autoCloseAfterImePicker = false
    private var imePickerShown = false
    private var imePickerLostFocusOnce = false
    private var autoShownImePicker = false

    // 一键设置轮询任务（用于等待用户选择输入法）
    private var setupPollingRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<android.view.View>(android.R.id.content).let { rootView ->
            WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        // 初始化状态机和工具类
        setupStateMachine = SetupStateMachine(this)
        updateChecker = UpdateChecker(this)

        // 记录初始无障碍服务状态
        wasAccessibilityEnabled = isAccessibilityServiceEnabled()

        // 设置按钮点击事件
        setupButtonListeners()

        // 显示识别字数统计
        updateAsrTotalChars()
    }

    override fun onResume() {
        super.onResume()

        // 授权返回后：若已下载APK且具备安装权限，自动继续安装
        maybeResumePendingApkInstall()

        // 检查无障碍服务是否刚刚被启用，给予用户反馈
        checkAccessibilityServiceJustEnabled()

        // 每天首次进入设置页时，静默检查一次更新（仅在有新版本时弹窗提示）
        maybeAutoCheckUpdatesDaily()

        // 更新识别字数统计
        updateAsrTotalChars()

        // 若处于一键设置流程中，返回后继续推进
        advanceSetupIfInProgress()

        // 首次进入时自动展示快速上手指南（首次需等待 5s 才能关闭）
        maybeAutoShowQuickGuideOnFirstOpen()
    }

    /**
     * 授予“未知来源应用安装”权限后，自动继续安装已下载的APK
     */
    private fun maybeResumePendingApkInstall() {
        try {
            // 仅当系统允许从本应用安装且存在待安装APK路径时尝试
            if (!packageManager.canRequestPackageInstalls()) return

            val prefs = Prefs(this)
            val path = prefs.pendingApkPath
            if (path.isBlank()) return

            val apkFile = File(path)
            if (!apkFile.exists()) {
                // 文件不存在，清理状态
                prefs.pendingApkPath = ""
                return
            }

            // 构造安装意图并触发系统安装器
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // 避免重复弹出：在启动安装前即清空标记
            prefs.pendingApkPath = ""
            startActivity(intent)
            Log.d(TAG, "Resumed pending APK install: ${apkFile.path}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to resume pending APK install", t)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // 处理"外部切换"模式：通过焦点变化判断 IME 选择器是否关闭
        handleExternalImeSwitchMode(hasFocus)

        // 处理自动弹出 IME 选择器（由 Intent Extra 触发）
        handleAutoShowImePicker(hasFocus)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 权限请求结果返回后，继续推进一键设置流程
        if (setupStateMachine.currentState is SetupState.RequestingPermissions) {
            Log.d(TAG, "Permission result received, advancing setup")
            // 小延迟，等待系统状态稳定
            handler.postDelayed({ advanceSetupIfInProgress() }, 200)
        }
    }

    /**
     * 设置所有按钮的点击监听器
     */
    private fun setupButtonListeners() {
        // 一键设置
        findViewById<Button>(R.id.btnOneClickSetup)?.setOnClickListener {
            startOneClickSetup()
        }

        // 快速指南
        findViewById<Button>(R.id.btnShowGuide)?.setOnClickListener {
            showQuickGuide()
        }

        // 检查更新
        findViewById<Button>(R.id.btnCheckUpdate)?.setOnClickListener {
            checkForUpdates()
        }

        // 测试输入
        findViewById<Button>(R.id.btnTestInput)?.setOnClickListener {
            showTestInputBottomSheet()
        }

        // 关于
        findViewById<Button>(R.id.btnAbout)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 子设置页导航
        findViewById<Button>(R.id.btnOpenInputSettings)?.setOnClickListener {
            startActivity(Intent(this, InputSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenAsrSettings)?.setOnClickListener {
            startActivity(Intent(this, AsrSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenAiSettings)?.setOnClickListener {
            startActivity(Intent(this, AiPostSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenOtherSettings)?.setOnClickListener {
            startActivity(Intent(this, OtherSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenFloatingSettings)?.setOnClickListener {
            startActivity(Intent(this, FloatingSettingsActivity::class.java))
        }

        // 配置备份页入口
        findViewById<Button>(R.id.btnExportSettings)?.setOnClickListener {
            startActivity(Intent(this, BackupSettingsActivity::class.java))
        }

        // 识别历史页入口
        findViewById<Button>(R.id.btnOpenAsrHistory)?.setOnClickListener {
            try {
                startActivity(Intent(this, com.brycewg.asrkb.ui.history.AsrHistoryActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open AsrHistoryActivity", e)
                Toast.makeText(this, getString(R.string.toast_debug_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 一键设置相关 ====================

    /**
     * 启动一键设置流程
     */
    private fun startOneClickSetup() {
        Log.d(TAG, "Starting one-click setup")

        // 重置状态机
        setupStateMachine.reset()
        stopSetupPolling()

        // 推进到第一个状态
        advanceSetupStateMachine()
    }

    /**
     * 推进一键设置状态机
     *
     * 1. 调用状态机的 advance() 方法获取下一个状态
     * 2. 执行该状态对应的操作
     * 3. 如果是 SelectingIme 状态，启动轮询等待用户选择
     */
    private fun advanceSetupStateMachine() {
        val newState = setupStateMachine.advance()
        val didExecute = setupStateMachine.executeCurrentStateAction()

        Log.d(TAG, "Setup state: $newState, executed action: $didExecute")

        when (newState) {
            is SetupState.SelectingIme -> {
                // 启动轮询，等待用户选择输入法
                if (newState.askedOnce) {
                    startSetupPolling()
                }
            }

            is SetupState.Completed, is SetupState.Aborted -> {
                // 设置完成或中止，停止轮询
                stopSetupPolling()
            }

            is SetupState.RequestingPermissions -> {
                // 权限请求阶段，某些权限需要通过 Activity 的回调处理
                if (didExecute) {
                    val state = setupStateMachine.getCurrentPermissionState()
                    if (state?.askedNotif == true && !state.askedA11y) {
                        // Android 13+ 通知权限请求
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                requestPermissions(
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                    1001
                                )
                            } else {
                                // 已授予，继续推进
                                handler.postDelayed({ advanceSetupStateMachine() }, 200)
                            }
                        } else {
                            // Android 12 及以下，跳过
                            handler.postDelayed({ advanceSetupStateMachine() }, 200)
                        }
                    }
                }
            }

            else -> {
                // 其他状态，无需特殊处理
            }
        }
    }

    /**
     * 如果正在一键设置流程中，继续推进
     */
    private fun advanceSetupIfInProgress() {
        if (setupStateMachine.currentState !is SetupState.NotStarted &&
            setupStateMachine.currentState !is SetupState.Completed &&
            setupStateMachine.currentState !is SetupState.Aborted
        ) {
            Log.d(TAG, "Resuming setup flow")
            handler.post { advanceSetupStateMachine() }
        }
    }

    /**
     * 启动轮询，等待用户选择输入法
     *
     * 轮询间隔 300ms，最长等待 8 秒
     */
    private fun startSetupPolling() {
        stopSetupPolling()

        Log.d(TAG, "Starting setup polling for IME selection")

        val runnable = object : Runnable {
            override fun run() {
                val state = setupStateMachine.currentState as? SetupState.SelectingIme
                    ?: return

                // 再次推进状态机（检查是否已选择）
                setupStateMachine.advance()

                val newState = setupStateMachine.currentState

                when (newState) {
                    is SetupState.RequestingPermissions -> {
                        // 用户已选择输入法，进入权限阶段
                        Log.d(TAG, "IME selected during polling, advancing to permissions")
                        stopSetupPolling()
                        advanceSetupStateMachine()
                    }

                    is SetupState.Aborted -> {
                        // 超时或其他原因中止
                        Log.d(TAG, "Setup aborted during polling")
                        stopSetupPolling()
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.toast_setup_choose_keyboard),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    is SetupState.Completed -> {
                        // 已完成（不太可能在这个阶段发生）
                        stopSetupPolling()
                    }

                    else -> {
                        // 继续轮询
                        handler.postDelayed(this, 300)
                    }
                }
            }
        }

        setupPollingRunnable = runnable
        handler.postDelayed(runnable, 350)
    }

    /**
     * 停止轮询
     */
    private fun stopSetupPolling() {
        setupPollingRunnable?.let { handler.removeCallbacks(it) }
        setupPollingRunnable = null
    }

    // ==================== 更新检查相关 ====================

    /**
     * 检查更新（主动触发，显示进度对话框）
     */
    private fun checkForUpdates() {
        Log.d(TAG, "User initiated update check")

        // 清理旧的安装包
        cleanOldApkFiles()

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    updateChecker.checkGitHubRelease()
                }
                progressDialog.dismiss()

                if (result.hasUpdate) {
                    Log.d(TAG, "Update available: ${result.latestVersion}")
                    showUpdateDialog(result)
                } else {
                    Log.d(TAG, "No update available")
                    showCurrentVersionInfoDialog(result)
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e(TAG, "Update check failed", e)
                showUpdateCheckFailedDialog(e)
            }
        }
    }

    /**
     * 每天首次进入设置页时，静默检查更新
     *
     * 不显示"正在检查"或"已是最新版本"提示，仅在有更新时弹窗
     */
    private fun maybeAutoCheckUpdatesDaily() {
        try {
            val prefs = Prefs(this)
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

            if (prefs.lastUpdateCheckDate == today) {
                Log.d(TAG, "Already checked update today, skipping auto check")
                return
            }

            // 记录为今日已检查，避免同日重复触发
            prefs.lastUpdateCheckDate = today
            Log.d(TAG, "Starting daily auto update check")
        } catch (e: Exception) {
            // 读取或写入失败则不自动检查
            Log.e(TAG, "Failed to check/update last update check date", e)
            return
        }

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    updateChecker.checkGitHubRelease()
                }

                if (result.hasUpdate) {
                    Log.d(TAG, "Auto check found update: ${result.latestVersion}")
                    showUpdateDialog(result)
                } else {
                    Log.d(TAG, "Auto check: no update available")
                }
            } catch (e: Exception) {
                // 静默检查失败，不弹窗提示
                Log.d(TAG, "Auto update check failed (silent): ${e.message}")
            }
        }
    }

    /**
     * 显示更新对话框
     */
    private fun showUpdateDialog(result: UpdateChecker.UpdateCheckResult) {
        val messageBuilder = StringBuilder()
        messageBuilder.append(
            getString(
                R.string.update_dialog_message,
                result.currentVersion,
                result.latestVersion
            )
        )

        // 添加重要提示（如果有）
        result.importantNotice?.let { notice ->
            messageBuilder.append("\n\n")
            // 使用占位符，稍后替换为带样式的文本
            messageBuilder.append("{{IMPORTANT_NOTICE_START}}")
            messageBuilder.append(notice)
            messageBuilder.append("{{IMPORTANT_NOTICE_END}}")
        }

        // 添加更新时间（如果有）
        result.updateTime?.let { updateTime ->
            messageBuilder.append("\n\n")
            val formattedTime = formatUpdateTime(updateTime)
            messageBuilder.append(getString(R.string.update_timestamp_label, formattedTime))
        }

        // 添加发布说明
        result.releaseNotes?.let { notes ->
            messageBuilder.append("\n\n")
            messageBuilder.append(getString(R.string.update_release_notes_label, notes))
        }

        // 创建带样式的文本（如果有重要提示）
        val messageText = if (result.importantNotice != null) {
            createStyledMessage(
                messageBuilder.toString(),
                result.importantNotice,
                result.noticeLevel
            )
        } else {
            messageBuilder.toString()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(messageText)
            .setPositiveButton(R.string.btn_download) { _, _ ->
                showDownloadSourceDialog(result.downloadUrl, result.latestVersion)
            }
            .setNeutralButton(R.string.btn_view_release_page) { _, _ ->
                // 跳转到 GitHub Release 页面
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open release page", e)
                    Toast.makeText(
                        this,
                        getString(R.string.error_open_browser),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 显示当前版本信息对话框（无更新时）
     */
    private fun showCurrentVersionInfoDialog(result: UpdateChecker.UpdateCheckResult) {
        val messageBuilder = StringBuilder()
        messageBuilder.append(
            getString(
                R.string.current_version_message,
                result.currentVersion
            )
        )

        // 添加重要提示（如果有）
        result.importantNotice?.let { notice ->
            messageBuilder.append("\n\n")
            // 使用占位符，稍后替换为带样式的文本
            messageBuilder.append("{{IMPORTANT_NOTICE_START}}")
            messageBuilder.append(notice)
            messageBuilder.append("{{IMPORTANT_NOTICE_END}}")
        }

        // 添加更新时间（如果有）
        result.updateTime?.let { updateTime ->
            messageBuilder.append("\n\n")
            val formattedTime = formatUpdateTime(updateTime)
            messageBuilder.append(getString(R.string.update_timestamp_label, formattedTime))
        }

        // 添加发布说明
        result.releaseNotes?.let { notes ->
            messageBuilder.append("\n\n")
            messageBuilder.append(getString(R.string.update_release_notes_label, notes))
        }

        // 创建带样式的文本（如果有重要提示）
        val messageText = if (result.importantNotice != null) {
            createStyledMessage(
                messageBuilder.toString(),
                result.importantNotice,
                result.noticeLevel
            )
        } else {
            messageBuilder.toString()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.current_version_info_title)
            .setMessage(messageText)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.btn_view_release_page) { _, _ ->
                // 跳转到 GitHub Release 页面
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open release page", e)
                    Toast.makeText(
                        this,
                        getString(R.string.error_open_browser),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    /**
     * 创建带颜色样式的消息文本
     *
     * @param message 原始消息文本
     * @param notice 重要提示内容
     * @param level 提示级别
     * @return 带样式的 SpannableString
     */
    private fun createStyledMessage(
        message: String,
        notice: String,
        level: UpdateChecker.NoticeLevel
    ): SpannableString {
        val spannable = SpannableString(
            message.replace("{{IMPORTANT_NOTICE_START}}", "")
                .replace("{{IMPORTANT_NOTICE_END}}", "")
        )

        // 查找重要提示在文本中的位置
        val noticeStart = spannable.indexOf(notice)
        if (noticeStart == -1) return spannable

        val noticeEnd = noticeStart + notice.length

        // 根据级别选择颜色（使用 Material3 动态颜色）
        val color = when (level) {
            UpdateChecker.NoticeLevel.INFO -> {
                // 使用 tertiary 颜色（Monet 第三色）
                getThemeColor(com.google.android.material.R.attr.colorTertiary, 0xFF6750A4.toInt())
            }
            UpdateChecker.NoticeLevel.WARNING -> {
                // 使用 secondary 颜色（Monet 第二色）
                getThemeColor(com.google.android.material.R.attr.colorSecondary, 0xFFE8DEF8.toInt())
            }
            UpdateChecker.NoticeLevel.CRITICAL -> {
                // 使用 error 颜色
                getThemeColor(android.R.attr.colorError, 0xFFB3261E.toInt())
            }
        }

        // 应用颜色和加粗样式
        spannable.setSpan(
            ForegroundColorSpan(color),
            noticeStart,
            noticeEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            noticeStart,
            noticeEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return spannable
    }

    /**
     * 从主题属性获取颜色
     *
     * @param attrId 属性 ID
     * @param defaultColor 默认颜色（如果属性不存在）
     * @return 颜色值
     */
    private fun getThemeColor(attrId: Int, defaultColor: Int): Int {
        val typedValue = android.util.TypedValue()
        return if (theme.resolveAttribute(attrId, typedValue, true)) {
            typedValue.data
        } else {
            defaultColor
        }
    }

    /**
     * 显示更新检查失败对话框
     */
    private fun showUpdateCheckFailedDialog(error: Exception) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_check_failed)
            .setMessage(error.message ?: "Unknown error")
            .setPositiveButton(R.string.btn_manual_check) { _, _ ->
                try {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/BryceWG/LexiSharp-Keyboard/releases")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open browser", e)
                    Toast.makeText(
                        this,
                        getString(R.string.error_open_browser),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 显示下载源选择对话框
     */
    private fun showDownloadSourceDialog(originalUrl: String, version: String) {
        val downloadSources = arrayOf(
            getString(R.string.download_source_github_official),
            getString(R.string.download_source_mirror_ghproxy),
            getString(R.string.download_source_mirror_gitmirror),
            getString(R.string.download_source_mirror_gh_proxynet)
        )

        // 根据 release 页面构造 APK 直链
        val directApkUrl = buildDirectApkUrl(originalUrl, version)

        // 生成对应的 URL：所有源都使用 APK 直链
        val downloadUrls = arrayOf(
            directApkUrl,
            convertToMirrorUrl(directApkUrl, "https://ghproxy.net/"),
            convertToMirrorUrl(directApkUrl, "https://hub.gitmirror.com/"),
            convertToMirrorUrl(directApkUrl, "https://gh-proxy.net/")
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_source_title)
            .setItems(downloadSources) { _, which ->
                try {
                    // 启动下载服务
                    ApkDownloadService.startDownload(this, downloadUrls[which], version)
                    Toast.makeText(
                        this,
                        getString(R.string.apk_download_started),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start download", e)
                    Toast.makeText(
                        this,
                        getString(R.string.apk_download_start_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 格式化更新时间
     *
     * 尝试解析 ISO 8601 格式并转换为本地时间，失败则返回原始字符串
     */
    private fun formatUpdateTime(updateTime: String): String {
        return try {
            val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            utcFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = utcFormat.parse(updateTime)

            val localFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            if (date != null) localFormat.format(date) else updateTime
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse update time: $updateTime", e)
            updateTime
        }
    }

    /**
     * 构造 APK 直链
     *
     * 输入: https://github.com/{owner}/{repo}/releases/tag/{tag}
     * 输出: https://github.com/{owner}/{repo}/releases/download/v{version}/lexisharp-keyboard-{version}-release.apk
     */
    private fun buildDirectApkUrl(originalUrl: String, version: String): String {
        val baseEnd = originalUrl.indexOf("/releases/tag/")
        val base = if (baseEnd > 0) {
            originalUrl.substring(0, baseEnd)
        } else {
            "https://github.com/BryceWG/LexiSharp-Keyboard"
        }
        val tag = "v$version"
        val apkName = "lexisharp-keyboard-$version-release.apk"
        return "$base/releases/download/$tag/$apkName"
    }

    /**
     * 转换为镜像 URL
     *
     * 仅对 GitHub 链接加镜像前缀
     */
    private fun convertToMirrorUrl(originalUrl: String, mirrorPrefix: String): String {
        return if (originalUrl.startsWith("https://github.com/")) {
            mirrorPrefix + originalUrl
        } else {
            originalUrl
        }
    }


    /**
     * 清理旧的 APK 安装包
     */
    private fun cleanOldApkFiles() {
        try {
            ApkDownloadService.cleanOldApks(this)
            Log.d(TAG, "Old APK files cleaned")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old APK files", e)
        }
    }

    // ==================== 其他辅助功能 ====================

    /**
     * 显示快速指南对话框
     */
    private fun showQuickGuide(minCloseDelayMs: Long = 0L) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null, false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_quick_guide)
            .setView(view)
            .setPositiveButton(R.string.btn_close, null)
            .create()

        var countdownJob: kotlinx.coroutines.Job? = null

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (minCloseDelayMs > 0L) {
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                positive.isEnabled = false

                val totalSeconds = (minCloseDelayMs / 1000L).toInt().coerceAtLeast(1)
                countdownJob = lifecycleScope.launch(Dispatchers.Main) {
                    var remain = totalSeconds
                    while (remain > 0 && dialog.isShowing) {
                        positive.text = getString(R.string.btn_close_with_seconds, remain)
                        kotlinx.coroutines.delay(1000)
                        remain -= 1
                    }
                    positive.text = getString(R.string.btn_close)
                    positive.isEnabled = true
                    dialog.setCancelable(true)
                    dialog.setCanceledOnTouchOutside(true)
                }
            }
        }

        dialog.setOnDismissListener {
            try {
                countdownJob?.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to cancel countdown job", t)
            }
        }

        dialog.show()
    }

    private fun maybeAutoShowQuickGuideOnFirstOpen() {
        try {
            val prefs = Prefs(this)
            if (!prefs.hasShownQuickGuideOnce) {
                // 记录为已展示，避免重复弹出
                prefs.hasShownQuickGuideOnce = true
                showQuickGuide(minCloseDelayMs = 5000L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to maybe auto show quick guide", t)
        }
    }

    /**
     * 显示测试输入底部浮层
     */
    private fun showTestInputBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_test_input, null, false)
        dialog.setContentView(view)

        val edit = view.findViewById<TextInputEditText>(R.id.etBottomTestInput)
        // 自动聚焦并弹出输入法
        edit?.post {
            try {
                edit.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show soft input", e)
            }
        }

        dialog.show()
    }

    /**
     * 更新识别字数统计显示
     */
    private fun updateAsrTotalChars() {
        try {
            val tvAsrTotalChars = findViewById<TextView>(R.id.tvAsrTotalChars)
            val prefs = Prefs(this)
            tvAsrTotalChars?.text = getString(R.string.label_asr_total_chars, prefs.totalAsrChars)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update ASR total chars display", e)
        }
    }

    /**
     * 检查无障碍服务是否刚刚被启用，给予用户反馈
     */
    private fun checkAccessibilityServiceJustEnabled() {
        val isNowEnabled = isAccessibilityServiceEnabled()

        if (!wasAccessibilityEnabled && isNowEnabled) {
            Log.d(TAG, "Accessibility service just enabled")
            Toast.makeText(
                this,
                getString(R.string.toast_accessibility_enabled),
                Toast.LENGTH_SHORT
            ).show()
        }

        wasAccessibilityEnabled = isNowEnabled
    }

    /**
     * 检查无障碍服务是否已启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName =
            "$packageName/com.brycewg.asrkb.ui.AsrAccessibilityService"
        val enabledServicesSetting = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accessibility services", e)
            return false
        }

        Log.d(TAG, "Expected accessibility service: $expectedComponentName")
        Log.d(TAG, "Enabled accessibility services: $enabledServicesSetting")

        val result = enabledServicesSetting?.contains(expectedComponentName) == true
        Log.d(TAG, "Accessibility service enabled: $result")
        return result
    }

    /**
     * 处理"外部切换"模式的 IME 选择器逻辑
     *
     * 当从浮动球或其他外部入口进入设置页并自动弹出 IME 选择器时，
     * 利用窗口焦点变化来检测选择器是否关闭，关闭后自动退出设置页。
     *
     * 焦点变化信号：
     * 1. 选择器弹出时，本页失去焦点 (hasFocus=false)
     * 2. 选择器关闭后，本页重新获得焦点 (hasFocus=true)
     * 3. 检测到"失去焦点→恢复焦点"的完整循环后，延迟 250ms 关闭设置页
     */
    private fun handleExternalImeSwitchMode(hasFocus: Boolean) {
        if (!autoCloseAfterImePicker || !imePickerShown) {
            return
        }

        if (!hasFocus) {
            // 系统输入法选择器置前，导致本页失去焦点
            imePickerLostFocusOnce = true
            Log.d(TAG, "IME picker shown, activity lost focus")
        } else if (imePickerLostFocusOnce) {
            // 选择器关闭，本页重新获得焦点 -> 可安全收尾
            Log.d(TAG, "IME picker closed, activity regained focus, finishing")
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    finish()
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            // Android 14+ 使用新的过渡覆盖 API，替代已废弃的 overridePendingTransition
                            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
                        } else {
                            @Suppress("DEPRECATION")
                            overridePendingTransition(0, 0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to override pending transition", e)
                    }
                }
            }, 250L)

            // 只触发一次
            imePickerShown = false
            imePickerLostFocusOnce = false
        }
    }

    /**
     * 处理自动弹出 IME 选择器（由 Intent Extra 触发）
     *
     * 用于从浮动球等外部入口快速切换输入法，进入设置页后自动弹出选择器
     */
    private fun handleAutoShowImePicker(hasFocus: Boolean) {
        if (!hasFocus) return
        if (autoShownImePicker) return
        if (intent?.getBooleanExtra(EXTRA_AUTO_SHOW_IME_PICKER, false) != true) return

        autoShownImePicker = true
        autoCloseAfterImePicker = true

        Log.d(TAG, "Auto-showing IME picker from intent extra")

        // 延迟到窗口获得焦点后调用，稳定性更好
        handler.post {
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
                // 标记：已弹出选择器
                imePickerShown = true
                imePickerLostFocusOnce = false
                Log.d(TAG, "IME picker shown successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show IME picker", e)
            }
        }
    }
}
