package com.brycewg.asrkb.ui.settings.floating

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingServiceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.util.Log
import kotlinx.coroutines.launch

class FloatingSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FloatingSettingsActivity"
    }

    private lateinit var viewModel: FloatingSettingsViewModel
    private lateinit var serviceManager: FloatingServiceManager
    private lateinit var prefs: Prefs

    // UI 组件
    private lateinit var switchFloatingOnlyWhenImeVisible: MaterialSwitch
    private lateinit var sliderFloatingAlpha: Slider
    private lateinit var sliderFloatingSize: Slider
    private lateinit var switchFloatingAsr: MaterialSwitch
    private lateinit var switchFloatingWriteCompat: MaterialSwitch
    private lateinit var etFloatingWriteCompatPkgs: TextInputEditText
    private lateinit var switchFloatingWritePaste: MaterialSwitch
    private lateinit var etFloatingWritePastePkgs: TextInputEditText
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<android.view.View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        // 初始化工具类
        viewModel = ViewModelProvider(this)[FloatingSettingsViewModel::class.java]
        serviceManager = FloatingServiceManager(this)
        prefs = Prefs(this)

        // 设置工具栏
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_floating_settings)
        toolbar.setNavigationOnClickListener { finish() }

        // 初始化 UI 组件
        initializeViews()

        // 加载初始状态
        viewModel.initialize(this)

        // 绑定状态到 UI
        bindStateToViews()

        // 设置监听器
        setupListeners()
    }

    /**
     * 初始化所有 UI 组件
     */
    private fun initializeViews() {
        switchFloatingOnlyWhenImeVisible = findViewById(R.id.switchFloatingOnlyWhenImeVisible)
        sliderFloatingAlpha = findViewById(R.id.sliderFloatingAlpha)
        sliderFloatingSize = findViewById(R.id.sliderFloatingSize)
        switchFloatingAsr = findViewById(R.id.switchFloatingAsr)
        switchFloatingWriteCompat = findViewById(R.id.switchFloatingWriteCompat)
        etFloatingWriteCompatPkgs = findViewById(R.id.etFloatingWriteCompatPkgs)
        switchFloatingWritePaste = findViewById(R.id.switchFloatingWritePaste)
        etFloatingWritePastePkgs = findViewById(R.id.etFloatingWritePastePkgs)
    }

    /**
     * 绑定 ViewModel 状态到 UI
     */
    private fun bindStateToViews() {
        lifecycleScope.launch {
            // 语音识别球开关
            viewModel.asrEnabled.collect { enabled ->
                if (switchFloatingAsr.isChecked != enabled) {
                    switchFloatingAsr.isChecked = enabled
                }
            }
        }

        lifecycleScope.launch {
            // 仅在键盘显示时显示
            viewModel.onlyWhenImeVisible.collect { enabled ->
                if (switchFloatingOnlyWhenImeVisible.isChecked != enabled) {
                    switchFloatingOnlyWhenImeVisible.isChecked = enabled
                }
            }
        }

        lifecycleScope.launch {
            // 透明度
            viewModel.alpha.collect { alpha ->
                if (sliderFloatingAlpha.value != alpha) {
                    sliderFloatingAlpha.value = alpha
                }
            }
        }

        lifecycleScope.launch {
            // 大小
            viewModel.sizeDp.collect { size ->
                if (sliderFloatingSize.value != size.toFloat()) {
                    sliderFloatingSize.value = size.toFloat()
                }
            }
        }

        lifecycleScope.launch {
            // 写入兼容模式
            viewModel.writeCompatEnabled.collect { enabled ->
                if (switchFloatingWriteCompat.isChecked != enabled) {
                    switchFloatingWriteCompat.isChecked = enabled
                }
            }
        }

        lifecycleScope.launch {
            // 写入粘贴方案
            viewModel.writePasteEnabled.collect { enabled ->
                if (switchFloatingWritePaste.isChecked != enabled) {
                    switchFloatingWritePaste.isChecked = enabled
                }
            }
        }

        // 初始化文本输入框（从 Prefs 加载）
        etFloatingWriteCompatPkgs.setText(prefs.floatingWriteCompatPackages)
        etFloatingWritePastePkgs.setText(prefs.floatingWritePastePackages)
    }

    /**
     * 设置所有监听器
     */
    private fun setupListeners() {
        // 仅在键盘显示时显示悬浮球
        switchFloatingOnlyWhenImeVisible.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            handleOnlyWhenImeVisibleToggle(isChecked)
        }

        // 悬浮窗透明度
        sliderFloatingAlpha.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.updateAlpha(this, value, serviceManager)
            }
        }
        sliderFloatingAlpha.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                hapticTapIfEnabled(slider)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                hapticTapIfEnabled(slider)
            }
        })

        // 悬浮球大小
        sliderFloatingSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.updateSize(this, value.toInt(), serviceManager)
            }
        }
        sliderFloatingSize.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                hapticTapIfEnabled(slider)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                hapticTapIfEnabled(slider)
            }
        })

        // 语音识别悬浮球开关
        switchFloatingAsr.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            handleAsrToggle(isChecked)
        }

        // 悬浮球写入文字兼容性模式
        switchFloatingWriteCompat.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            viewModel.handleWriteCompatToggle(this, isChecked)
        }

        // 悬浮球写入文字采取粘贴方案
        switchFloatingWritePaste.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            viewModel.handleWritePasteToggle(this, isChecked)
        }

        // 兼容目标包名（写入兼容）
        etFloatingWriteCompatPkgs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateWriteCompatPackages(this@FloatingSettingsActivity, s?.toString() ?: "")
            }
        })

        // 目标包名（写入粘贴方案）
        etFloatingWritePastePkgs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateWritePastePackages(this@FloatingSettingsActivity, s?.toString() ?: "")
            }
        })

    }

    /**
     * 处理语音识别球开关变化
     */
    private fun handleAsrToggle(enabled: Boolean) {
        val permissionRequest = viewModel.handleAsrToggle(this, enabled, serviceManager)
        when (permissionRequest) {
            FloatingSettingsViewModel.PermissionRequest.OVERLAY -> {
                showOverlayPermissionToast()
                requestOverlayPermission()
            }
            FloatingSettingsViewModel.PermissionRequest.ACCESSIBILITY -> {
                showAccessibilityPermissionToast()
                requestAccessibilityPermission()
            }
            null -> {
                // 成功，无需额外操作
            }
        }
    }

    /**
     * 处理"仅在键盘显示时显示"开关变化
     */
    private fun handleOnlyWhenImeVisibleToggle(enabled: Boolean) {
        val permissionRequest = viewModel.handleOnlyWhenImeVisibleToggle(this, enabled, serviceManager)
        if (permissionRequest == FloatingSettingsViewModel.PermissionRequest.ACCESSIBILITY) {
            showAccessibilityPermissionToast()
            requestAccessibilityPermission()
        }
    }

    /**
     * 显示需要悬浮窗权限的提示
     */
    private fun showOverlayPermissionToast() {
        Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
    }

    /**
     * 显示需要无障碍权限的提示
     */
    private fun showAccessibilityPermissionToast() {
        Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
    }

    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request overlay permission", e)
        }
    }

    /**
     * 请求无障碍权限
     */
    private fun requestAccessibilityPermission() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request accessibility permission", e)
        }
    }

    /**
     * 触发触觉反馈（如果已启用）
     */
    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (prefs.micHapticEnabled) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to perform haptic feedback", e)
        }
    }
}
