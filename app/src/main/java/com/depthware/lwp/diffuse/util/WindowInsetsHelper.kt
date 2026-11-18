package com.depthware.lwp.diffuse.util

import android.view.View

object WindowInsetsHelper {

    @Suppress("DEPRECATION")
    fun applySystemWindowInsetsPadding(
        view: View,
        left: Boolean = false,
        top: Boolean = false,
        right: Boolean = false,
        bottom: Boolean = false
    ) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                if (left) insets.systemWindowInsetLeft else 0,
                if (top) insets.systemWindowInsetTop else 0,
                if (right) insets.systemWindowInsetRight else 0,
                if (bottom) insets.systemWindowInsetBottom else 0
            )
            insets
        }
    }
}
