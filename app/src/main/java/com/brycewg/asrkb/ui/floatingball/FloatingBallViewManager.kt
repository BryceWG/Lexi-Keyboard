package com.brycewg.asrkb.ui.floatingball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.graphics.toColorInt
import com.brycewg.asrkb.R
import com.brycewg.asrkb.UiColors
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.widgets.ProcessingSpinnerView
import com.google.android.material.color.DynamicColors

/**
 * 悬浮球视图管理器
 * 负责管理 WindowManager、视图创建、位置管理和动画
 */
class FloatingBallViewManager(
    private val context: Context,
    private val prefs: Prefs,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "FloatingBallViewManager"
    }

    private var ballView: View? = null
    private var ballIcon: ImageView? = null
    private var processingSpinner: ProcessingSpinnerView? = null
    private var ripple1: View? = null
    private var ripple2: View? = null
    private var ripple3: View? = null
    private var lp: WindowManager.LayoutParams? = null

    // 动画
    
    private var rippleAnimators: MutableList<Animator> = mutableListOf()
    private var edgeAnimator: ValueAnimator? = null
    private var errorVisualActive: Boolean = false
    private var completionResetPosted: Boolean = false
    private var monetContext: Context? = null
    private var currentState: FloatingBallState = FloatingBallState.Idle
    
    // 贴边半隐可视比例（仅显示该比例的宽度）
    private val visibleFractionWhenHidden = 0.5f

    /** 获取悬浮球视图 */
    fun getBallView(): View? = ballView

    /** 获取布局参数 */
    fun getLayoutParams(): WindowManager.LayoutParams? = lp

    /** 显示悬浮球 */
    fun showBall(onClickListener: (View) -> Unit, onTouchListener: View.OnTouchListener, initialState: FloatingBallState): Boolean {
        if (ballView != null) {
            applyBallAlpha()
            applyBallSize()
            try { updateStateVisual(currentState) } catch (e: Throwable) { Log.w(TAG, "Failed to refresh state on existing view", e) }
            return true
        }

        try {
            val themedCtx = ContextThemeWrapper(context, R.style.Theme_ASRKeyboard)
            val dynCtx = DynamicColors.wrapContextIfAvailable(themedCtx)
            monetContext = dynCtx

            val view = LayoutInflater.from(dynCtx).inflate(R.layout.floating_asr_ball, null, false)
            ballIcon = view.findViewById(R.id.ballIcon)
            ripple1 = view.findViewById(R.id.ripple1)
            ripple2 = view.findViewById(R.id.ripple2)
            ripple3 = view.findViewById(R.id.ripple3)
            val ballContainer = try {
                view.findViewById<android.widget.FrameLayout>(R.id.ballContainer)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to find ballContainer", e)
                null
            }

            // 获取 Monet 动态颜色
            val colorSecondaryContainer = getMonetColor(
                com.google.android.material.R.attr.colorSecondaryContainer,
                0xFF6200EE.toInt()
            )
            val colorSecondary = getMonetColor(
                com.google.android.material.R.attr.colorSecondary,
                colorSecondaryContainer
            )

            // 将相对更重的初始化（波纹背景/自定义进度指示器）延后到下一帧，
            // 以降低 addView 当帧的主线程压力，避免与 IME 显示竞争导致掉帧。
            view.post {
                try { setupRippleBackgrounds(colorSecondary) } catch (e: Throwable) {
                    Log.w(TAG, "Deferred ripple setup failed", e)
                }
                try { setupProcessingSpinner(ballContainer, colorSecondary) } catch (e: Throwable) {
                    Log.w(TAG, "Deferred spinner setup failed", e)
                }
                // 延后初始化完成后，根据当前状态刷新一次，以确保 Processing 时能立刻显示动画
                try { updateStateVisual(currentState) } catch (e: Throwable) { Log.w(TAG, "Failed to apply state after deferred init", e) }
            }

            // 绑定点击和拖动监听
            ballIcon?.setOnClickListener(onClickListener)
            view.setOnTouchListener(onTouchListener)
            ballIcon?.setOnTouchListener(onTouchListener)

            // 创建 WindowManager.LayoutParams
            val params = createWindowLayoutParams()

            // 添加视图
            windowManager.addView(view, params)
            ballView = view
            lp = params
            applyBallAlpha()
            applyBallSize()
            // 应用初始状态
            try { updateStateVisual(initialState) } catch (e: Throwable) { Log.w(TAG, "Failed to apply initial state", e) }
            Log.d(TAG, "Ball view added successfully")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ball view", e)
            return false
        }
    }

    /** 隐藏悬浮球 */
    fun hideBall() {
        val v = ballView ?: return
        // 在移除视图前停止动画
        try {
            cleanup()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cleanup before hide", e)
        }
        try {
            persistBallPosition()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }
        try {
            windowManager.removeView(v)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to remove ball view", e)
        }
        // 释放所有与旧视图树绑定的引用，确保下次 show 时重新创建/挂载
        ballView = null
        ballIcon = null
        processingSpinner = null
        ripple1 = null
        ripple2 = null
        ripple3 = null
        lp = null
    }

    /** 应用悬浮球透明度 */
    fun applyBallAlpha() {
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha, using default", e)
            1.0f
        }
        ballView?.alpha = alpha
    }

    /** 应用悬浮球大小 */
    fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get size, using default", e)
            56
        }
        p.width = dp(size)
        p.height = dp(size)
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update view layout", e)
        }

        // 同步调整内部图标大小，保持随悬浮球缩放
        try {
            updateBallIconSize()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update ball icon size", e)
        }
    }

    /** 根据悬浮球窗口大小按比例调整麦克风图标尺寸 */
    private fun updateBallIconSize() {
        val icon = ballIcon ?: return
        val p = lp ?: return
        val ballSidePx = listOf(p.width, p.height).filter { it > 0 }.minOrNull() ?: return
        val target = (ballSidePx * 0.75f).toInt().coerceAtLeast(dp(20))
        val lpIcon = icon.layoutParams ?: return
        if (lpIcon.width != target || lpIcon.height != target) {
            lpIcon.width = target
            lpIcon.height = target
            icon.layoutParams = lpIcon
        }
    }

    /** 更新悬浮球状态显示 */
    fun updateStateVisual(state: FloatingBallState) {
        currentState = state
        // 清除颜色滤镜（错误视觉期间不清除）
        if (!errorVisualActive) ballIcon?.clearColorFilter()

        when (state) {
            is FloatingBallState.Recording -> {
                try { ballIcon?.setImageResource(R.drawable.microphone_fill) } catch (e: Throwable) { Log.w(TAG, "Failed to set ball icon (recording)", e) }
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                startRippleAnimation()
            }
            is FloatingBallState.Processing -> {
                try { ballIcon?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { Log.w(TAG, "Failed to set ball icon (processing)", e) }
                stopRippleAnimation()
                processingSpinner?.visibility = View.VISIBLE
                startProcessingSpinner()
            }
            is FloatingBallState.Error -> {
                stopRippleAnimation()
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                playErrorShakeAnimation()
            }
            else -> {
                // Idle, MoveMode
                try { ballIcon?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { Log.w(TAG, "Failed to set ball icon (idle/move)", e) }
                stopRippleAnimation()
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                resetIconScale()
            }
        }
    }

    /**
     * 若当前在左右边缘，执行“完全显示”的贴边浮现动画。
     * - 仅对左右边缘生效；底部贴边不处理（保持完全显示）。
     */
    fun animateRevealFromEdgeIfNeeded() {
        val p = lp ?: return
        val v = ballView ?: return

        val dock = detectDockSide()
        if (dock == DockSide.BOTTOM || dock == DockSide.NONE) return

        val target = fullyVisiblePositionForSide(dock)
        val startX = p.x
        val startY = p.y
        val dx = target.first - startX
        val dy = target.second - startY

        if (dx == 0 && dy == 0) return

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try {
                    windowManager.updateViewLayout(ballView ?: v, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update layout during reveal", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                }
            })
            start()
        }
    }

    /**
     * 若当前处于静息，应在左右贴边时执行半隐动画；
     * - 不对底部贴边执行半隐，保持完全可见。
     * - 若未贴边，则将自动吸附到就近左右边并半隐。
     */
    fun animateHideToEdgePartialIfNeeded() {
        val p = lp ?: return
        val v = ballView ?: return

        val dock = detectDockSide(allowChooseNearest = true)
        if (dock == DockSide.BOTTOM || dock == DockSide.NONE) return

        val target = partiallyHiddenPositionForSide(dock)
        val startX = p.x
        val startY = p.y
        val dx = target.first - startX
        val dy = target.second - startY

        if (dx == 0 && dy == 0) return

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try {
                    windowManager.updateViewLayout(ballView ?: v, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update layout during partial hide", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                }
            })
            start()
        }
    }

    /** 显示完成对勾 */
    fun showCompletionTick(durationMs: Long = 1000L) {
        val icon = ballIcon ?: return
        try {
            icon.setImageResource(R.drawable.check_circle)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set check icon", e)
            return
        }
        if (!completionResetPosted) {
            completionResetPosted = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    ballIcon?.setImageResource(R.drawable.microphone)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to reset icon", e)
                }
                completionResetPosted = false
            }, durationMs)
        }
    }

    /** 对勾展示是否仍在活动周期内（用于延后半隐） */
    fun isCompletionTickActive(): Boolean = completionResetPosted

    /** 吸附到边缘（带动画） */
    fun animateSnapToEdge(v: View, onComplete: (() -> Unit)? = null) {
        val p = lp ?: return
        val (targetX, targetY) = calculateSnapTarget(v)

        val startX = p.x
        val startY = p.y
        val dx = targetX - startX
        val dy = targetY - startY

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try {
                    windowManager.updateViewLayout(ballView ?: v, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update layout during snap animation", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    /** 吸附到边缘（无动画） */
    fun snapToEdge(v: View) {
        val p = lp ?: return
        val (targetX, targetY) = calculateSnapTarget(v)
        p.x = targetX
        p.y = targetY
        try {
            windowManager.updateViewLayout(ballView ?: v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update layout during snap", e)
        }
        persistBallPosition()
    }

    /** 获取悬浮球中心点 */
    fun getBallCenterSnapshot(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val sizeDp = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size, using default", e)
            56
        }
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (lp?.width ?: dp(sizeDp))
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (lp?.height ?: dp(sizeDp))
        val px = lp?.x ?: run {
            try {
                prefs.floatingBallPosX
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get ball X position, using default", e)
                (dm.widthPixels - vw) / 2
            }
        }
        val py = lp?.y ?: run {
            try {
                prefs.floatingBallPosY
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get ball Y position, using default", e)
                (dm.heightPixels - vh) / 2
            }
        }
        return (px + vw / 2) to (py + vh / 2)
    }

    /** 更新窗口位置 */
    fun updateViewLayout(v: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update view layout", e)
        }
    }

    /** 清理所有动画 */
    fun cleanup() {
        stopRippleAnimation()
        stopProcessingSpinner()
        edgeAnimator?.cancel()
        edgeAnimator = null
    }

    // ==================== 私有辅助方法 ====================

    private fun getMonetColor(attr: Int, fallback: Int): Int {
        val ctx = monetContext ?: context
        return try {
            val tv = android.util.TypedValue()
            val theme = ctx.theme
            if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve theme attribute $attr, using fallback", e)
            fallback
        }
    }

    private fun setupRippleBackgrounds(color: Int) {
        val rippleStrokeColor = applyAlpha(color, 1.0f)
        val strokeWidthPx = dp(4)
        val dashWidthPx = dp(8).toFloat()
        val dashGapPx = dp(6).toFloat()
        listOf(ripple1, ripple2, ripple3).forEach { ripple ->
            ripple?.let {
                try {
                    val drawable = android.graphics.drawable.GradientDrawable()
                    drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                    drawable.setColor(android.graphics.Color.TRANSPARENT)
                    drawable.setStroke(strokeWidthPx, rippleStrokeColor, dashWidthPx, dashGapPx)
                    it.background = drawable
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set ripple background", e)
                }
            }
        }
    }

    private fun setupProcessingSpinner(ballContainer: android.widget.FrameLayout?, color: Int) {
        try {
            if (ballContainer == null) return

            // 若为首次或已释放，则创建新实例
            if (processingSpinner == null) {
                processingSpinner = ProcessingSpinnerView(context)
            }

            // 统一进行属性配置
            processingSpinner?.apply {
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setSpinnerColor(applyAlpha(color, 0.6f))
                setStrokeWidth(dp(4).toFloat())
                setSweepAngle(110f)
                // 初始化时保持隐藏，待 Processing 态再显现
                visibility = View.GONE
            }

            // 若当前未挂载到新的容器，则挂载
            val parent = processingSpinner?.parent
            if (parent == null) {
                val lpSpinner = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
                ballContainer.addView(processingSpinner, lpSpinner)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to setup processing spinner", e)
        }
    }

    private fun createWindowLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size, using default", e)
            56
        }
        val params = WindowManager.LayoutParams(
            dp(size), dp(size),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        // 恢复上次位置
        try {
            val sx = prefs.floatingBallPosX
            val sy = prefs.floatingBallPosY
            val (screenW, screenH) = getUsableScreenSize()
            val vw = params.width
            val vh = params.height
            if (sx >= 0 && sy >= 0) {
                params.x = sx.coerceIn(0, (screenW - vw).coerceAtLeast(0))
                params.y = sy.coerceIn(0, (screenH - vh).coerceAtLeast(0))
            } else {
                params.x = dp(12)
                params.y = dp(180)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to restore ball position, using default", e)
            params.x = dp(12)
            params.y = dp(180)
        }

        return params
    }

    /** 重置位置为默认（左上偏下），并立即应用到当前视图（若存在） */
    fun resetPositionToDefault() {
        val p = lp ?: return
        val v = ballView ?: return
        try {
            p.x = dp(12)
            p.y = dp(180)
            windowManager.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to reset position to default", e)
        }
        // 覆盖并持久化当前位置
        try {
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist default position after reset", e)
        }
    }

    private fun calculateSnapTarget(v: View): Pair<Int, Int> {
        val p = lp ?: return 0 to 0
        val (screenW, screenH) = getUsableScreenSize()
        val def = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size for snap calculation", e)
            56
        }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val bottomSnapThreshold = dp(64)
        val bottomY = (screenH - vh - margin).coerceAtLeast(0)
        val bottomDist = bottomY - p.y

        return if (bottomDist <= bottomSnapThreshold) {
            val targetY = bottomY
            val minX = margin
            val maxX = (screenW - vw - margin).coerceAtLeast(minX)
            val targetX = p.x.coerceIn(minX, maxX)
            // 再次保护，避免任何计算差异导致越界
            val safeX = targetX.coerceIn(minX, maxX)
            val safeY = targetY.coerceIn(0, (screenH - vh - margin).coerceAtLeast(0))
            safeX to safeY
        } else {
            val centerX = p.x + vw / 2
            val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
            val minY = margin
            val maxY = (screenH - vh - margin).coerceAtLeast(minY)
            val targetY = p.y.coerceIn(minY, maxY)
            targetX to targetY
        }
    }

    // ============== 贴边/半隐计算 ==============

    private enum class DockSide { LEFT, RIGHT, BOTTOM, NONE }

    /**
     * 检测当前贴边方向；当 allowChooseNearest=true 且未贴边时，选择更近的左右边。
     */
    private fun detectDockSide(allowChooseNearest: Boolean = false): DockSide {
        val p = lp ?: return DockSide.NONE
        val (screenW, screenH) = getUsableScreenSize()
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (p.width)
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (p.height)
        val margin = dp(0)

        val bottomY = (screenH - vh - margin).coerceAtLeast(0)
        val bottomDist = bottomY - p.y
        val bottomSnapThreshold = dp(64)
        if (bottomDist <= bottomSnapThreshold || p.y >= bottomY) return DockSide.BOTTOM

        val leftX = margin
        val rightX = screenW - vw - margin
        val edgeThresholdX = dp(28)

        if (p.x <= leftX + edgeThresholdX) return DockSide.LEFT
        if (p.x >= rightX - edgeThresholdX) return DockSide.RIGHT

        if (!allowChooseNearest) return DockSide.NONE

        // 未贴边时，选择更近的一侧（不考虑顶部/底部，因为仅对左右生效）
        val centerX = p.x + vw / 2
        return if (centerX < screenW / 2) DockSide.LEFT else DockSide.RIGHT
    }

    /** 左/右侧完全可见位置 */
    private fun fullyVisiblePositionForSide(side: DockSide): Pair<Int, Int> {
        val p = lp ?: return 0 to 0
        val (screenW, screenH) = getUsableScreenSize()
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (p.width)
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (p.height)
        val margin = dp(0)
        val x = when (side) {
            DockSide.LEFT -> margin
            DockSide.RIGHT -> screenW - vw - margin
            else -> p.x
        }
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val y = p.y.coerceIn(minY, maxY)
        return x to y
    }

    /** 左/右侧半隐位置（仅显示一定比例的宽度） */
    private fun partiallyHiddenPositionForSide(side: DockSide): Pair<Int, Int> {
        val p = lp ?: return 0 to 0
        val (screenW, screenH) = getUsableScreenSize()
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (p.width)
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (p.height)
        val margin = dp(0)

        val visibleW = (vw * visibleFractionWhenHidden).toInt()
        val x = when (side) {
            DockSide.LEFT -> (margin - (vw - visibleW))
            DockSide.RIGHT -> (screenW - visibleW - margin)
            else -> p.x
        }
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val y = p.y.coerceIn(minY, maxY)
        return x to y
    }

    /**
     * 获取可用的屏幕宽高（排除系统状态栏/导航栏/切口），用于限制悬浮球不被放置到不可见区域。
     * 说明：横向半隐依赖 FLAG_LAYOUT_NO_LIMITS，仅在 X 轴允许越界；Y 轴一律限制在可见范围内。
     */
    private fun getUsableScreenSize(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val w = (bounds.width() - insets.left - insets.right).coerceAtLeast(0)
                val h = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(0)
                w to h
            } else {
                val dm = context.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get usable screen size, fallback to displayMetrics", e)
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun persistBallPosition() {
        val p = lp ?: return
        try {
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }
    }

    // ==================== 动画方法 ====================

    private fun startRippleAnimation() {
        stopRippleAnimation()

        val ripples = listOf(ripple1, ripple2, ripple3)
        ripples.forEachIndexed { index, ripple ->
            ripple ?: return@forEachIndexed

            val delay = index * 500L

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                startDelay = delay
                interpolator = android.view.animation.LinearInterpolator()

                addUpdateListener { anim ->
                    val progress = anim.animatedValue as Float
                    val scale = 0.10f + progress * 0.90f
                    ripple.scaleX = scale
                    ripple.scaleY = scale
                    val alpha = (1f - progress)
                    ripple.alpha = alpha
                    ripple.visibility = if (alpha > 0.01f) View.VISIBLE else View.INVISIBLE
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationRepeat(animation: Animator) {
                        ripple.scaleX = 0.10f
                        ripple.scaleY = 0.10f
                    }
                })

                start()
            }

            rippleAnimators.add(animator)
        }
    }

    private fun stopRippleAnimation() {
        rippleAnimators.forEach { it.cancel() }
        rippleAnimators.clear()
        ripple1?.visibility = View.INVISIBLE
        ripple2?.visibility = View.INVISIBLE
        ripple3?.visibility = View.INVISIBLE
        ripple1?.alpha = 0f
        ripple2?.alpha = 0f
        ripple3?.alpha = 0f
    }

    private fun startProcessingSpinner() {
        try {
            processingSpinner?.start()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start processing spinner", e)
        }
    }

    private fun stopProcessingSpinner() {
        try {
            processingSpinner?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stop processing spinner", e)
        }
    }

    private fun resetIconScale() {
        ballIcon?.let {
            it.scaleX = 1.0f
            it.scaleY = 1.0f
        }
    }

    private fun playErrorShakeAnimation() {
        val icon = ballIcon ?: return

        errorVisualActive = true
        try {
            icon.animate().cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel icon animation", e)
        }
        icon.setColorFilter(UiColors.error(icon))

        val shake = ValueAnimator.ofFloat(0f, -16f, 16f, -12f, 12f, -6f, 6f, 0f).apply {
            duration = 500
            addUpdateListener { anim ->
                icon.translationX = (anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    icon.translationX = 0f
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            icon.clearColorFilter()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to clear color filter", e)
                        }
                        errorVisualActive = false
                    }, 1000)
                }
            })
            start()
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(v: Int): Int {
        val d = context.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
