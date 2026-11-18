package com.depthware.lwp.diffuse.recorder

import android.graphics.SurfaceTexture
import android.opengl.EGLContext
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

class TextureMovieEncoder : Runnable, SurfaceTexture.OnFrameAvailableListener {

    interface SurfaceListener {
        fun onSurfaceReady(surface: Surface)
    }

    data class EncoderConfig(
        val outputFile: File,
        val width: Int,
        val height: Int,
        val topCrop: Float,
        val bottomCrop: Float,
        val leftCrop: Float,
        val rightCrop: Float,
        val bitRate: Int,
        val eglContext: EGLContext
    )

    companion object {
        private const val TAG = "TextureMovieEncoder"
        private const val MSG_START_RECORDING = 0
        private const val MSG_STOP_RECORDING = 1
        private const val MSG_FRAME_AVAILABLE = 2
        private const val MSG_SET_TEXTURE_ID = 3
        private const val MSG_UPDATE_SHARED_CONTEXT = 4
        private const val MSG_DRAIN_AUDIO = 5
        private const val MSG_QUIT = 6
    }

    private class EncoderHandler(encoder: TextureMovieEncoder) : Handler(Looper.myLooper()!!) {
        private val encoderRef = WeakReference(encoder)

        override fun handleMessage(msg: Message) {
            val encoder = encoderRef.get() ?: run {
                Log.w(TAG, "EncoderHandler.handleMessage: encoder is null")
                return
            }
            when (msg.what) {
                MSG_START_RECORDING -> encoder.handleStartRecording(msg.obj as EncoderConfig)
                MSG_STOP_RECORDING -> encoder.handleStopRecording()
                MSG_FRAME_AVAILABLE -> {
                    val timestamp = (msg.arg1.toLong() shl 32) or (msg.arg2.toLong() and 0xFFFFFFFFL)
                    encoder.handleFrameAvailable(msg.obj as FloatArray, timestamp)
                }
                MSG_SET_TEXTURE_ID -> encoder.handleSetTexture(msg.arg1)
                MSG_UPDATE_SHARED_CONTEXT -> encoder.handleUpdateSharedContext(msg.obj as EGLContext)
                MSG_DRAIN_AUDIO -> encoder.handleDrainAudio(msg.arg1 == 1)
                MSG_QUIT -> {
                    Log.d(TAG, "Exit encoder loop")
                    Looper.myLooper()?.quit()
                }
                else -> throw RuntimeException("Unhandled msg what=${msg.what}")
            }
        }
    }

    private var inputWindowSurface: WindowSurface? = null
    private var eglCore: EglCore? = null
    private var fullScreen: FullFrameRect? = null
    private var textureId = 0
    private var videoEncoder: VideoEncoderCore? = null

    @Volatile
    private var handler: EncoderHandler? = null
    private val readyFence = Object()
    private var ready = false
    private var running = false

    private var surfaceListener: SurfaceListener? = null
    private var senderThread: HandlerThread? = null
    private var senderHandler: Handler? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var texMatrix: FloatArray? = null

    private var topCrop = 0f
    private var bottomCrop = 0f
    private var leftCrop = 0f
    private var rightCrop = 0f
    private var callback: RecorderCallback? = null

    private val updateTexImageRunnable = Runnable {
        surfaceTexture?.updateTexImage()
    }

    fun setSurfaceListener(listener: SurfaceListener?) {
        surfaceListener = listener
    }

    fun setCallback(cb: RecorderCallback?) {
        callback = cb
    }

    fun isRecording(): Boolean = synchronized(readyFence) { running }

    fun startRecording(config: EncoderConfig) {
        Log.d(TAG, "Encoder: startRecording()")
        synchronized(readyFence) {
            if (running) {
                Log.w(TAG, "Encoder thread already running")
                return
            }
            running = true
            Thread(this, "TextureMovieEncoder").start()
            while (!ready) {
                try {
                    readyFence.wait()
                } catch (_: InterruptedException) {
                }
            }
            handler?.sendMessage(handler!!.obtainMessage(MSG_START_RECORDING, config))
        }
    }

    fun stopRecording() {
        synchronized(readyFence) {
            if (ready) {
                handler?.removeCallbacks(updateTexImageRunnable)
                synchronized(this) {
                    handler?.sendMessage(handler!!.obtainMessage(MSG_STOP_RECORDING))
                    handler?.sendMessage(handler!!.obtainMessage(MSG_QUIT))
                }
            }
        }
    }

    fun enqueueAudio(buffer: ByteBuffer?, length: Int, endOfStream: Boolean) {
        synchronized(readyFence) {
            if (ready) {
                videoEncoder?.queueAudioSample(buffer, length, endOfStream)
            }
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        handler?.postDelayed(updateTexImageRunnable, 16L)
        frameAvailable(st)
    }

    fun frameAvailable(st: SurfaceTexture) {
        frameAvailable(st, st.timestamp)
    }

    fun frameAvailable(st: SurfaceTexture, timestampNanos: Long) {
        synchronized(readyFence) {
            if (!ready) return
            val matrix = texMatrix ?: FloatArray(16).also { texMatrix = it }
            st.getTransformMatrix(matrix)
            if (timestampNanos == 0L) {
                Log.w(TAG, "HEY: got SurfaceTexture with timestamp of zero")
                return
            }
            handler?.sendMessage(
                handler!!.obtainMessage(
                    MSG_FRAME_AVAILABLE,
                    (timestampNanos shr 32).toInt(),
                    timestampNanos.toInt(),
                    matrix
                )
            )
        }
    }

    override fun run() {
        Looper.prepare()
        synchronized(readyFence) {
            handler = EncoderHandler(this)
            ready = true
            readyFence.notify()
        }
        Looper.loop()
        Log.d(TAG, "Encoder thread exiting")
        synchronized(readyFence) {
            running = false
            ready = false
            handler = null
        }
    }

    private fun handleDrainAudio(eos: Boolean) {
        videoEncoder?.drainAudio(eos)
    }

    private fun handleFrameAvailable(transform: FloatArray, timestampNanos: Long) {
        videoEncoder?.drainEncoder(false)
        fullScreen?.drawFrame(textureId, transform)
        inputWindowSurface?.setPresentationTime(timestampNanos)
        inputWindowSurface?.swapBuffers()
    }

    private fun handleSetTexture(id: Int) {
        textureId = id
    }

    private fun handleStartRecording(config: EncoderConfig) {
        Log.d(TAG, "handleStartRecording $config")
        topCrop = config.topCrop
        bottomCrop = config.bottomCrop
        leftCrop = config.leftCrop
        rightCrop = config.rightCrop

        var croppedHeight = (config.height * (1.0f - topCrop - bottomCrop)).toInt()
        if (croppedHeight % 2 != 0) croppedHeight++
        var croppedWidth = (config.width * (1.0f - leftCrop - rightCrop)).toInt()
        if (croppedWidth % 2 != 0) croppedWidth++

        try {
            val core = VideoEncoderCore(croppedWidth, croppedHeight, config.bitRate, config.outputFile)
            core.setCallback(callback)
            videoEncoder = core

            val egl = EglCore(config.eglContext, EglCore.FLAG_RECORDABLE)
            eglCore = egl
            val winSurface = WindowSurface(egl, core.getInputSurface(), true)
            winSurface.makeCurrent()
            inputWindowSurface = winSurface

            val rect = FullFrameRect(Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)).apply {
                setTopCrop(topCrop)
                setBottomCrop(bottomCrop)
                setLeftCrop(leftCrop)
                setRightCrop(rightCrop)
            }
            fullScreen = rect
            textureId = rect.createTextureObject()

            val thread = HandlerThread("SurfaceFrameSender").apply { start() }
            senderThread = thread
            val sHandler = Handler(thread.looper)
            senderHandler = sHandler

            val st = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener(this@TextureMovieEncoder, sHandler)
                setDefaultBufferSize(config.width, config.height)
            }
            surfaceTexture = st
            val surface = Surface(st)
            inputSurface = surface
            surfaceListener?.onSurfaceReady(surface)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun handleStopRecording() {
        Log.d(TAG, "handleStopRecording")
        videoEncoder?.drainEncoder(true)
        releaseEncoder()
    }

    private fun handleUpdateSharedContext(newContext: EGLContext) {
        Log.d(TAG, "handleUpdatedSharedContext $newContext")
        inputWindowSurface?.releaseEglSurface()
        fullScreen?.release(false)
        eglCore?.release()

        val egl = EglCore(newContext, EglCore.FLAG_RECORDABLE)
        eglCore = egl
        inputWindowSurface?.recreate(egl)
        inputWindowSurface?.makeCurrent()

        fullScreen = FullFrameRect(Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)).apply {
            setTopCrop(topCrop)
            setBottomCrop(bottomCrop)
            setLeftCrop(leftCrop)
            setRightCrop(rightCrop)
        }
    }

    private fun releaseEncoder() {
        videoEncoder?.release()
        videoEncoder = null
        inputSurface?.release()
        inputSurface = null
        inputWindowSurface?.release()
        inputWindowSurface = null
        fullScreen?.release(false)
        fullScreen = null
        eglCore?.release()
        eglCore = null
        senderHandler = null
        senderThread?.quit()
        senderThread = null
    }
}
