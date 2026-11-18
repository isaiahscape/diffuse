package com.depthware.lwp.diffuse.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.depthware.lwp.diffuse.application.DiffuseApplication

object NotificationChannelManager {
    const val CHANNEL_MISC = "misc"

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = DiffuseApplication.appContext()
                .getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_MISC, "Misc", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
