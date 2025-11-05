package com.brycewg.asrkb.ui.settings.input

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class InputSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InputSettingsActivity"
        private const val REQ_BT_CONNECT = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        val prefs = Prefs(this)

        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchMicHaptic = findViewById<MaterialSwitch>(R.id.switchMicHaptic)
        val switchMicTapToggle = findViewById<MaterialSwitch>(R.id.switchMicTapToggle)
        val switchMicSwipeUpAutoEnter = findViewById<MaterialSwitch>(R.id.switchMicSwipeUpAutoEnter)
        val switchAutoStartRecordingOnShow = findViewById<MaterialSwitch>(R.id.switchAutoStartRecordingOnShow)
        val switchFcitx5ReturnOnSwitcher = findViewById<MaterialSwitch>(R.id.switchFcitx5ReturnOnSwitcher)
        val switchReturnPrevImeOnHide = findViewById<MaterialSwitch>(R.id.switchReturnPrevImeOnHide)
        val switchHideRecentTasks = findViewById<MaterialSwitch>(R.id.switchHideRecentTasks)
        val switchDuckMediaOnRecord = findViewById<MaterialSwitch>(R.id.switchDuckMediaOnRecord)
        val switchHeadsetMicPriority = findViewById<MaterialSwitch>(R.id.switchHeadsetMicPriority)
        val tvKeyboardHeight = findViewById<TextView>(R.id.tvKeyboardHeightValue)
        val tvLanguage = findViewById<TextView>(R.id.tvLanguageValue)
        val sliderBottomPadding = findViewById<com.google.android.material.slider.Slider>(R.id.sliderBottomPadding)
        val tvBottomPaddingValue = findViewById<TextView>(R.id.tvBottomPaddingValue)

        fun applyPrefsToUi() {
            switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
            switchMicHaptic.isChecked = prefs.micHapticEnabled
            switchMicTapToggle.isChecked = prefs.micTapToggleEnabled
            switchFcitx5ReturnOnSwitcher.isChecked = prefs.fcitx5ReturnOnImeSwitch
            switchReturnPrevImeOnHide.isChecked = prefs.returnPrevImeOnHide
            switchHideRecentTasks.isChecked = prefs.hideRecentTaskCard
            switchDuckMediaOnRecord.isChecked = prefs.duckMediaOnRecordEnabled
            switchHeadsetMicPriority.isChecked = prefs.headsetMicPriorityEnabled
            switchMicSwipeUpAutoEnter.isChecked = prefs.micSwipeUpAutoEnterEnabled
            switchAutoStartRecordingOnShow.isChecked = prefs.autoStartRecordingOnShow
        }
        applyPrefsToUi()

        // 键盘高度：三档（点击弹出单选对话框）
        setupKeyboardHeightSelection(prefs, tvKeyboardHeight)

        // 底部间距调节
        setupBottomPaddingSlider(prefs, sliderBottomPadding, tvBottomPaddingValue)

        // 应用语言选择（点击弹出单选对话框）
        setupLanguageSelection(prefs, tvLanguage)

        // 监听与保存
        switchTrimTrailingPunct.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.trimFinalTrailingPunct = isChecked
        }
        switchMicHaptic.setOnCheckedChangeListener { btn, isChecked ->
            prefs.micHapticEnabled = isChecked
            try {
                btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to perform haptic feedback for mic haptic switch", e)
            }
        }
        switchMicTapToggle.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.micTapToggleEnabled = isChecked
            if (isChecked) {
                // 互斥：启用点按控制后，关闭“上滑自动发送”
                if (prefs.micSwipeUpAutoEnterEnabled) {
                    prefs.micSwipeUpAutoEnterEnabled = false
                    try { switchMicSwipeUpAutoEnter.isChecked = false } catch (_: Throwable) { }
                }
            }
        }
        switchMicSwipeUpAutoEnter.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.micSwipeUpAutoEnterEnabled = isChecked
            if (isChecked) {
                // 互斥：启用"上滑自动发送"后，关闭点按控制
                if (prefs.micTapToggleEnabled) {
                    prefs.micTapToggleEnabled = false
                    try { switchMicTapToggle.isChecked = false } catch (_: Throwable) { }
                }
            }
        }
        switchAutoStartRecordingOnShow.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.autoStartRecordingOnShow = isChecked
        }
        switchReturnPrevImeOnHide.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.returnPrevImeOnHide = isChecked
        }
        switchFcitx5ReturnOnSwitcher.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.fcitx5ReturnOnImeSwitch = isChecked
        }
        switchHideRecentTasks.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.hideRecentTaskCard = isChecked
            applyExcludeFromRecents(isChecked)
        }
        switchDuckMediaOnRecord.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.duckMediaOnRecordEnabled = isChecked
        }
        switchHeadsetMicPriority.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        try {
                            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_CONNECT)
                        } catch (t: Throwable) {
                            Log.w(TAG, "requestPermissions BLUETOOTH_CONNECT failed", t)
                            Toast.makeText(this, getString(R.string.toast_bt_connect_permission_required), Toast.LENGTH_SHORT).show()
                        }
                        // 临时回退 UI，待授权结果再更新偏好
                        try { switchHeadsetMicPriority.isChecked = false } catch (_: Throwable) {}
                        return@setOnCheckedChangeListener
                    }
                }
            }
            prefs.headsetMicPriorityEnabled = isChecked
            if (!isChecked) {
                // 若用户关闭耳机优先，立刻撤销可能存在的预热连接
                try {
                    com.brycewg.asrkb.asr.BluetoothRouteManager.onRecordingStopped(this)
                    com.brycewg.asrkb.asr.BluetoothRouteManager.setImeActive(this, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to converge route after disabling headset priority", t)
                }
            }
        }

        // 初始应用一次"从最近任务中排除"设置
        applyExcludeFromRecents(prefs.hideRecentTaskCard)

        // Pro：注入输入设置额外 UI
        try {
            val root = findViewById<View>(android.R.id.content)
            com.brycewg.asrkb.ProUiInjector.injectIntoInputSettings(this, root)
        } catch (_: Throwable) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT) {
            val switchHeadsetMicPriority = findViewById<MaterialSwitch>(R.id.switchHeadsetMicPriority)
            val prefs = Prefs(this)
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                prefs.headsetMicPriorityEnabled = true
                try { switchHeadsetMicPriority.isChecked = true } catch (_: Throwable) {}
            } else {
                prefs.headsetMicPriorityEnabled = false
                try { switchHeadsetMicPriority.isChecked = false } catch (_: Throwable) {}
                try { Toast.makeText(this, getString(R.string.toast_bt_connect_permission_denied), Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 设置键盘高度选择对话框
     */
    private fun setupKeyboardHeightSelection(prefs: Prefs, tvKeyboardHeight: TextView) {
        val options = listOf(
            getString(R.string.keyboard_height_small),
            getString(R.string.keyboard_height_medium),
            getString(R.string.keyboard_height_large)
        )

        fun updateSummary() {
            val idx = (prefs.keyboardHeightTier - 1).coerceIn(0, 2)
            tvKeyboardHeight.text = options[idx]
        }
        updateSummary()

        tvKeyboardHeight.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val currentIndex = (prefs.keyboardHeightTier - 1).coerceIn(0, 2)
            showSingleChoiceDialog(
                titleRes = R.string.label_keyboard_height,
                items = options,
                currentIndex = currentIndex,
                onSelected = { selectedIndex ->
                    val tier = (selectedIndex + 1).coerceIn(1, 3)
                    if (prefs.keyboardHeightTier != tier) {
                        prefs.keyboardHeightTier = tier
                        updateSummary()
                        sendRefreshBroadcast()
                    }
                }
            )
        }
    }

    /**
     * 设置底部间距调节滑动条
     */
    private fun setupBottomPaddingSlider(
        prefs: Prefs,
        slider: com.google.android.material.slider.Slider,
        tvValue: TextView
    ) {
        // 初始化滑动条值
        slider.value = prefs.keyboardBottomPaddingDp.toFloat()
        tvValue.text = getString(R.string.keyboard_bottom_padding_value, prefs.keyboardBottomPaddingDp)

        // 监听滑动条变化
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val dp = value.toInt()
                prefs.keyboardBottomPaddingDp = dp
                tvValue.text = getString(R.string.keyboard_bottom_padding_value, dp)
                sendRefreshBroadcast()
            }
        }
    }

    /**
     * 设置应用语言选择对话框
     */
    private fun setupLanguageSelection(prefs: Prefs, tvLanguage: TextView) {
        val options = listOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_zh_tw),
            getString(R.string.lang_ja),
            getString(R.string.lang_en)
        )

        fun getLanguageIndex(tag: String): Int = when (tag) {
            "zh", "zh-CN", "zh-Hans" -> 1
            "zh-TW", "zh-Hant" -> 2
            "ja" -> 3
            "en" -> 4
            else -> 0
        }

        fun updateSummary() {
            tvLanguage.text = options[getLanguageIndex(prefs.appLanguageTag)]
        }
        updateSummary()

        tvLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val currentIndex = getLanguageIndex(prefs.appLanguageTag)
            showSingleChoiceDialog(
                titleRes = R.string.label_language,
                items = options,
                currentIndex = currentIndex,
                onSelected = { selectedIndex ->
                    val newTag = when (selectedIndex) {
                        1 -> "zh-CN"
                        2 -> "zh-TW"
                        3 -> "ja"
                        4 -> "en"
                        else -> ""
                    }
                    if (newTag != prefs.appLanguageTag) {
                        prefs.appLanguageTag = newTag
                        updateSummary()
                        val locales = if (newTag.isBlank()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(newTag)
                        }
                        AppCompatDelegate.setApplicationLocales(locales)
                    }
                }
            )
        }
    }

    /**
     * 通用单选对话框显示函数
     *
     * @param titleRes 对话框标题资源 ID
     * @param items 选项列表
     * @param currentIndex 当前选中的索引
     * @param onSelected 选中回调，参数为选中的索引
     */
    private fun showSingleChoiceDialog(
        titleRes: Int,
        items: List<String>,
        currentIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(items.toTypedArray(), currentIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * 发送刷新 IME UI 的广播
     */
    private fun sendRefreshBroadcast() {
        try {
            sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI))
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send refresh IME UI broadcast", e)
        }
    }

    /**
     * 根据设置执行触觉反馈
     */
    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (Prefs(this).micHapticEnabled) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to perform haptic feedback", e)
        }
    }

    /**
     * 应用"从最近任务中排除"设置
     */
    private fun applyExcludeFromRecents(enabled: Boolean) {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply exclude from recents setting", e)
        }
    }
}
