package com.depthware.lwp.diffuse.model

import android.graphics.drawable.Drawable

data class AppInfo(
    var packageName: String = "",
    var label: String = "",
    var icon: Drawable? = null
)
