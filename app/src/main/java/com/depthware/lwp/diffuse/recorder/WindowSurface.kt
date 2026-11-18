package com.depthware.lwp.diffuse.recorder

import android.view.Surface

class WindowSurface(
    eglCore: EglCore,
    private var surface: Surface?,
    private val releaseSurfaceOnDestroy: Boolean
) : EglSurfaceBase(eglCore) {

    init {
        createWindowSurface(surface!!)
    }

    fun recreate(newEglCore: EglCore) {
        val s = surface ?: throw RuntimeException("not yet implemented for SurfaceTexture")
        eglCore = newEglCore
        createWindowSurface(s)
    }

    fun release() {
        releaseEglSurface()
        surface?.let {
            if (releaseSurfaceOnDestroy) {
                it.release()
            }
            surface = null
        }
    }
}
