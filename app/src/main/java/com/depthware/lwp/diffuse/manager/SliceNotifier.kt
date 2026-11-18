package com.depthware.lwp.diffuse.manager

import android.net.Uri
import com.depthware.lwp.diffuse.application.DiffuseApplication

object SliceNotifier {
    private val settingsSliceUri = Uri.parse("content://com.depthware.lwp.diffuse.provider/settings")

    fun notifyChange() {
        DiffuseApplication.appContext().contentResolver.notifyChange(settingsSliceUri, null)
    }
}
