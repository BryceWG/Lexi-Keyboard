package com.brycewg.asrkb.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.permission.PermissionActivity

/**
 * 一键设置流程的状态机管理器
 *
 * 负责管理设置流程的状态转换、权限检查和用户交互
 */
class SetupStateMachine(private val context: Context) {
    companion object {
        private const val TAG = "SetupStateMachine"
        private const val IME_PICKER_TIMEOUT_MS = 8000L
    }

    var currentState: SetupState = SetupState.NotStarted
        private set

    private val prefs = Prefs(context)

    /**
     * 推进状态机到下一个状态
     *
     * 根据当前状态和系统状态（输入法是否启用/选择、权限是否授予）决定下一步操作
     *
     * @return 下一个状态，如果无法继续则返回当前状态
     */
    fun advance(): SetupState {
        Log.d(TAG, "Advancing from state: $currentState")

        currentState = when (val state = currentState) {
            is SetupState.NotStarted -> {
                // 优化：将“选择输入法”作为最后一步；权限优先
                val needOptional = hasMissingOptionalPermissions()
                if (!isOurImeEnabled()) {
                    SetupState.EnablingIme()
                } else if (!hasAllRequiredPermissions() || needOptional) {
                    // 必需权限或可选权限（如通知权限）缺失，先请求权限
                    SetupState.RequestingPermissions()
                } else if (!isOurImeCurrent()) {
                    SetupState.SelectingIme()
                } else {
                    SetupState.Completed
                }
            }

            is SetupState.EnablingIme -> {
                if (isOurImeEnabled()) {
                    // 输入法已启用，下一步优先补齐权限，再进行“选择输入法”
                    Log.d(TAG, "IME enabled; prioritizing permissions before selecting IME")
                    val needOptional = hasMissingOptionalPermissions()
                    if (!hasAllRequiredPermissions() || needOptional) {
                        SetupState.RequestingPermissions()
                    } else if (!isOurImeCurrent()) {
                        SetupState.SelectingIme()
                    } else {
                        SetupState.Completed
                    }
                } else {
                    // 仍未启用，保持当前状态
                    state
                }
            }

            is SetupState.SelectingIme -> {
                if (isOurImeCurrent()) {
                    // 已选择为当前输入法，进入权限请求阶段
                    Log.d(TAG, "IME selected, moving to RequestingPermissions")
                    val needOptional = hasMissingOptionalPermissions()
                    if (!hasAllRequiredPermissions() || needOptional) {
                        SetupState.RequestingPermissions()
                    } else {
                        SetupState.Completed
                    }
                } else if (state.waitingSince > 0 &&
                           System.currentTimeMillis() - state.waitingSince > IME_PICKER_TIMEOUT_MS) {
                    // 超时，中止流程
                    Log.w(TAG, "IME selection timeout")
                    SetupState.Aborted("选择输入法超时")
                } else {
                    // 继续等待
                    state
                }
            }

            is SetupState.RequestingPermissions -> {
                if (hasAllRequiredPermissions()) {
                    // 所有权限已授予
                    Log.d(TAG, "All permissions granted, setup completed")
                    SetupState.Completed
                } else {
                    // 继续请求缺失的权限
                    state
                }
            }

            is SetupState.Completed, is SetupState.Aborted -> {
                // 终态，不再转换
                state
            }
        }

        Log.d(TAG, "Advanced to state: $currentState")
        return currentState
    }

    /**
     * 执行当前状态对应的操作（弹出系统设置、请求权限等）
     *
     * @return true 表示执行了操作，false 表示当前状态无需操作
     */
    fun executeCurrentStateAction(): Boolean {
        Log.d(TAG, "Executing action for state: $currentState")

        return when (val state = currentState) {
            is SetupState.EnablingIme -> {
                if (!state.askedOnce) {
                    // 引导用户前往系统设置启用输入法
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_setup_enable_keyboard_first),
                        Toast.LENGTH_SHORT
                    ).show()
                    try {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        // 更新状态标记已提示过
                        currentState = SetupState.EnablingIme(askedOnce = true)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open IME settings", e)
                        false
                    }
                } else {
                    // 已提示过，等待用户操作
                    false
                }
            }

            is SetupState.SelectingIme -> {
                if (!state.askedOnce) {
                    // 唤起输入法选择器
                    try {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                        // 更新状态：标记已唤起，并记录开始等待的时间
                        currentState = SetupState.SelectingIme(
                            askedOnce = true,
                            waitingSince = System.currentTimeMillis()
                        )
                        Log.d(TAG, "IME picker shown, waiting for user selection")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show IME picker", e)
                        false
                    }
                } else {
                    // 已唤起选择器，等待用户选择
                    false
                }
            }

            is SetupState.RequestingPermissions -> {
                // 按顺序请求缺失的权限
                requestNextMissingPermission(state)
            }

            is SetupState.Completed -> {
                // 显示完成提示
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_setup_all_done),
                    Toast.LENGTH_SHORT
                ).show()
                false
            }

            is SetupState.Aborted -> {
                // 显示中止提示
                Toast.makeText(
                    context,
                    "设置已中止: ${state.reason}",
                    Toast.LENGTH_LONG
                ).show()
                false
            }

            is SetupState.NotStarted -> {
                // 初始状态，无需操作
                false
            }
        }
    }

    /**
     * 请求下一个缺失的权限
     *
     * 按照优先级顺序请求：麦克风 > 悬浮窗 > 通知 > 无障碍
     *
     * @return true 表示发起了权限请求，false 表示所有权限已授予或已全部请求过
     */
    private fun requestNextMissingPermission(state: SetupState.RequestingPermissions): Boolean {
        // 1) 麦克风权限（必需）
        if (!state.askedMic && !hasMicrophonePermission()) {
            Log.d(TAG, "Requesting microphone permission")
            try {
                context.startActivity(Intent(context, PermissionActivity::class.java))
                currentState = state.copy(askedMic = true)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request microphone permission", e)
                currentState = state.copy(askedMic = true)
                return false
            }
        }

        // 2) 悬浮窗权限（当启用悬浮功能时需要）
        val needOverlay = prefs.floatingAsrEnabled
        if (!state.askedOverlay && needOverlay && !hasOverlayPermission()) {
            Log.d(TAG, "Requesting overlay permission")
            Toast.makeText(
                context,
                context.getString(R.string.toast_need_overlay_perm),
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
                currentState = state.copy(askedOverlay = true)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request overlay permission", e)
                currentState = state.copy(askedOverlay = true)
                return false
            }
        }

        // 3) 通知权限（Android 13+，增强项）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!state.askedNotif && !hasNotificationPermission()) {
                Log.d(TAG, "Requesting notification permission")
                // 注意：实际请求需要通过 Activity.requestPermissions()
                // 这里只更新状态，实际请求由 Activity 处理
                currentState = state.copy(askedNotif = true)
                return true
            }
        }

        // 4) 无障碍权限（当启用悬浮球或外部 AIDL 时需要）
        val needA11y = prefs.floatingAsrEnabled || prefs.externalAidlEnabled
        if (!state.askedA11y && needA11y && !hasAccessibilityPermission()) {
            Log.d(TAG, "Requesting accessibility permission")
            Toast.makeText(
                context,
                context.getString(R.string.toast_need_accessibility_perm),
                Toast.LENGTH_LONG
            ).show()
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                currentState = state.copy(askedA11y = true)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request accessibility permission", e)
                currentState = state.copy(askedA11y = true)
                return false
            }
        }

        // 所有权限都已请求过或已授予
        Log.d(TAG, "All permissions requested or granted")
        return false
    }

    /**
     * 重置状态机到初始状态
     */
    fun reset() {
        Log.d(TAG, "Resetting state machine")
        currentState = SetupState.NotStarted
    }

    /**
     * 检查是否所有必需权限已授予
     */
    private fun hasAllRequiredPermissions(): Boolean {
        val micGranted = hasMicrophonePermission()
        val needOverlay = prefs.floatingAsrEnabled
        val overlayGranted = !needOverlay || hasOverlayPermission()
        val needA11y = prefs.floatingAsrEnabled || prefs.externalAidlEnabled
        val a11yGranted = !needA11y || hasAccessibilityPermission()

        // Android 13+ 通知权限仅作为增强项，缺失不阻塞核心功能
        return micGranted && overlayGranted && a11yGranted
    }

    /**
     * 是否存在缺失的“可选权限”。
     * 目前仅包含 Android 13+ 的通知权限，用于在一键设置中补询。
     */
    private fun hasMissingOptionalPermissions(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13 以下默认已授予
        }
    }

    private fun hasAccessibilityPermission(): Boolean {
        val expectedComponentName = "${context.packageName}/com.brycewg.asrkb.ui.AsrAccessibilityService"
        val enabledServicesSetting = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accessibility permission", e)
            return false
        }
        return enabledServicesSetting?.contains(expectedComponentName) == true
    }

    private fun isOurImeEnabled(): Boolean {
        val imm = try {
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get InputMethodManager", e)
            return false
        }

        val enabledList = try {
            imm.enabledInputMethodList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get enabled IME list", e)
            null
        }

        if (enabledList?.any { it.packageName == context.packageName } == true) {
            return true
        }

        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            )
            val id = "${context.packageName}/.ime.AsrKeyboardService"
            enabled?.contains(id) == true ||
            (enabled?.split(':')?.any { it.startsWith(context.packageName) } == true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check IME enabled via Settings", e)
            false
        }
    }

    private fun isOurImeCurrent(): Boolean {
        return try {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val expectedId = "${context.packageName}/.ime.AsrKeyboardService"
            current == expectedId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check current IME", e)
            false
        }
    }

    /**
     * 获取当前状态对应的 RequestingPermissions，如果不是则返回 null
     * 用于从 Activity 的 onRequestPermissionsResult 回调中更新状态
     */
    fun getCurrentPermissionState(): SetupState.RequestingPermissions? {
        return currentState as? SetupState.RequestingPermissions
    }
}
