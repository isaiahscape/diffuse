package com.depthware.lwp.diffuse.recorder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

class EglCore(sharedContext: EGLContext? = null, flags: Int = 0) {

    companion object {
        private const val TAG = "Grafika"
        const val FLAG_RECORDABLE = 0x01
        const val FLAG_TRY_GLES3 = 0x02
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var glVersion = -1

    init {
        val ctx = sharedContext ?: EGL14.EGL_NO_CONTEXT
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        eglDisplay = display
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("unable to initialize EGL14")
        }
        if ((flags and FLAG_TRY_GLES3) != 0) {
            val config = getConfig(flags, 3)
            if (config != null) {
                val context = EGL14.eglCreateContext(
                    eglDisplay, config, ctx,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
                )
                if (EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                    eglConfig = config
                    eglContext = context
                    glVersion = 3
                }
            }
        }
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            val config = getConfig(flags, 2)
                ?: throw RuntimeException("Unable to find a suitable EGLConfig")
            val context = EGL14.eglCreateContext(
                eglDisplay, config, ctx,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            checkEglError("eglCreateContext")
            eglConfig = config
            eglContext = context
            glVersion = 2
        }
        val values = IntArray(1)
        EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0)
        Log.d(TAG, "EGLContext created, client version ${values[0]}")
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$msg: EGL error: 0x${Integer.toHexString(error)}")
        }
    }

    private fun getConfig(flags: Int, version: Int): EGLConfig? {
        val renderableType = if (version >= 3) {
            EGL14.EGL_OPENGL_ES2_BIT or EGL_OPENGL_ES3_BIT_KHR
        } else {
            EGL14.EGL_OPENGL_ES2_BIT
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL14.EGL_NONE, 0,
            EGL14.EGL_NONE
        )
        if ((flags and FLAG_RECORDABLE) != 0) {
            attribList[10] = EGL_RECORDABLE_ANDROID
            attribList[11] = 1
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            Log.w(TAG, "unable to find RGB8888 / $version EGLConfig")
            return null
        }
        return configs[0]
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        if (surface !is Surface && surface !is SurfaceTexture) {
            throw RuntimeException("invalid surface: $surface")
        }
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        checkEglError("eglCreateWindowSurface")
        return eglSurface ?: throw RuntimeException("surface was null")
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.d(TAG, "NOTE: makeCurrent w/o display")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean =
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
}
