package com.depthware.lwp.diffuse.manager

import android.widget.Toast
import com.depthware.lwp.diffuse.application.DiffuseApplication

object ToastManager {
    private var toast: Toast? = null

    fun show(message: String, longDuration: Boolean = false) {
        val duration = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        val t = Toast.makeText(DiffuseApplication.appContext(), message, duration)
        toast = t
        t.show()
    }

    fun showUpdated(message: String) {
        val t = toast ?: Toast.makeText(DiffuseApplication.appContext(), message, Toast.LENGTH_SHORT)
        toast = t
        t.setText(message)
        t.show()
    }
}
