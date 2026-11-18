package com.depthware.lwp.diffuse.engine

import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration

object GdxConfig {
    fun create(): AndroidApplicationConfiguration = AndroidApplicationConfiguration().apply {
        useWakelock = false
        useGyroscope = false
        useAccelerometer = false
        useCompass = false
        useImmersiveMode = true
        depth = 0
        stencil = 0
        numSamples = 0
        disableAudio = false
        a = 8
        b = 8
        g = 8
        r = 8
    }
}
