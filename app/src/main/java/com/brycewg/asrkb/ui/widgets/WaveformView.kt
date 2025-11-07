package com.brycewg.asrkb.ui.widgets

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import jaygoo.widget.wlv.WaveLineView

/**
 * 实时音频波形视图（封装第三方 WaveLineView）
 * - 保持原有 API：start/stop/updateAmplitude/setWaveformColor
 * - 使用 SurfaceView 提供更顺滑的波形动画
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var isActive = false
    private val waveView: WaveLineView = WaveLineView(context).apply {
        // 透明背景，融入容器
        setBackGroundColor(Color.TRANSPARENT)
        // 默认灵敏度与速度可根据需要调整
        setSensibility(8)
        setMoveSpeed(250f)
    }

    init {
        // 让 WaveLineView 充满容器
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(waveView, lp)
        // 初始隐藏由上层控制
        visibility = View.GONE
    }

    fun setWaveformColor(@ColorInt color: Int) {
        waveView.setLineColor(color)
        invalidate()
    }

    /** 更新振幅（0.0 - 1.0） */
    fun updateAmplitude(amplitude: Float) {
        if (!isActive) return
        // 映射到 [0,100] 并设置音量，WaveLineView 内部做平滑
        val vol = (amplitude.coerceIn(0f, 1f) * 100f).toInt()
        waveView.setVolume(vol)
    }

    /** 启动波形动画 */
    fun start() {
        if (isActive) return
        isActive = true
        try {
            waveView.startAnim()
        } catch (t: Throwable) {
            android.util.Log.w("WaveformView", "startAnim failed", t)
        }
    }

    /** 停止波形动画 */
    fun stop() {
        if (!isActive) return
        isActive = false
        try {
            waveView.stopAnim()
        } catch (t: Throwable) {
            android.util.Log.w("WaveformView", "stopAnim failed", t)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        try {
            waveView.onWindowFocusChanged(hasWindowFocus)
        } catch (t: Throwable) {
            android.util.Log.w("WaveformView", "onWindowFocusChanged proxy failed", t)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this && visibility != View.VISIBLE) {
            if (isActive) stop()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            stop()
            waveView.release()
        } catch (t: Throwable) {
            android.util.Log.w("WaveformView", "release failed", t)
        }
    }
}
