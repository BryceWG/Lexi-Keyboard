package com.brycewg.asrkb.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Window Insets 处理工具类
 * 用于适配 Android 15 的边缘到边缘显示
 */
object WindowInsetsHelper {

    /**
     * 为根视图应用系统栏 insets
     *
     * @param rootView 根视图
     * @param applyTop 是否为顶部添加状态栏高度的 padding
     * @param applyBottom 是否为底部添加导航栏高度的 padding
     */
    fun applySystemBarsInsets(
        rootView: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true
    ) {
        val initialPadding = Rect(
            rootView.paddingLeft,
            rootView.paddingTop,
            rootView.paddingRight,
            rootView.paddingBottom
        )

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                top = if (applyTop) initialPadding.top + insets.top else initialPadding.top,
                bottom = if (applyBottom) initialPadding.bottom + insets.bottom else initialPadding.bottom,
                left = initialPadding.left + insets.left,
                right = initialPadding.right + insets.right
            )

            windowInsets
        }
    }

    /**
     * 为 Toolbar 应用顶部状态栏 insets
     */
    fun applyTopInsets(view: View) {
        val initialPadding = Rect(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            view.paddingBottom
        )

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = initialPadding.top + insets.top
            )
            windowInsets
        }
    }

    /**
     * 为底部视图应用导航栏 insets
     */
    fun applyBottomInsets(view: View) {
        val initialPadding = Rect(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            view.paddingBottom
        )

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                bottom = initialPadding.bottom + insets.bottom
            )
            windowInsets
        }
    }

    private data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}
