package com.depthware.lwp.diffuse.application

import android.app.Application
import android.content.Context
import com.depthware.lwp.diffuse.manager.AudioVisualizerManager
import com.depthware.lwp.diffuse.manager.GdxPlayerManager
import com.depthware.lwp.diffuse.manager.NotificationChannelManager
import com.depthware.lwp.diffuse.manager.NotificationRegulatorManager
import com.depthware.lwp.diffuse.ui.lyrics.AccordUiUtils
import com.google.android.material.color.DynamicColors
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger
import com.orhanobut.logger.PrettyFormatStrategy

class DiffuseApplication : Application() {

    companion object {
        private lateinit var instance: DiffuseApplication

        fun appContext(): Context = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AccordUiUtils.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        val formatStrategy = PrettyFormatStrategy.newBuilder()
            .tag("Diffuse")
            .methodCount(1)
            .methodOffset(5)
            .build()
        Logger.addLogAdapter(AndroidLogAdapter(formatStrategy))

        GdxPlayerManager.initObservers()
        AudioVisualizerManager.init()
        NotificationRegulatorManager.schedule()
        NotificationChannelManager.createChannels()
    }
}
