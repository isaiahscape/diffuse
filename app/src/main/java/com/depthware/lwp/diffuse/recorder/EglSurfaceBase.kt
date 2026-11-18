package com.depthware.lwp.diffuse.recorder

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.util.Log

open class EglSurfaceBase(protected var eglCore: EglCore) {

    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun createWindowSurface(surface: Any) {
        check(eglSurface == EGL14.EGL_NO_SURFACE) { "surface already created" }
        eglSurface = eglCore.createWindowSurface(surface)
    }

    fun makeCurrent() {
        eglCore.makeCurrent(eglSurface)
    }

    fun releaseEglSurface() {
        eglCore.releaseSurface(eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun setPresentationTime(nsecs: Long) {
        eglCore.setPresentationTime(eglSurface, nsecs)
    }

    fun swapBuffers(): Boolean {
        val result = eglCore.swapBuffers(eglSurface)
        if (!result) {
            Log.d("Grafika", "WARNING: swapBuffers() failed")
        }
        return result
    }
}
