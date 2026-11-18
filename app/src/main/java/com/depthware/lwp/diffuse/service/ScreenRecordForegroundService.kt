package com.depthware.lwp.diffuse.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.EGL14
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.manager.NotificationChannelManager
import com.depthware.lwp.diffuse.recorder.RecorderCallback
import com.depthware.lwp.diffuse.recorder.TextureMovieEncoder
import com.depthware.lwp.diffuse.util.DiffuseUtils
import java.io.File
import java.nio.ByteBuffer

class ScreenRecordForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ScreenRecordForegroundService = this@ScreenRecordForegroundService
    }

    private inner class AudioRecordRunnable : Runnable {
        private var buffer: ByteArray? = null

        private fun readAndEnqueue(endOfStream: Boolean) {
            val buf = buffer ?: ByteArray(2048).also { buffer = it }
            val record = audioRecord ?: return
            val readBytes = record.read(buf, 0, buf.size)
            when (readBytes) {
                AudioRecord.ERROR_INVALID_OPERATION ->
                    DiffuseUtils.logRemoteError("mAudioRecord.read = AudioRecord.ERROR_INVALID_OPERATION")
                AudioRecord.ERROR_BAD_VALUE ->
                    DiffuseUtils.logRemoteError("mAudioRecord.read = AudioRecord.ERROR_BAD_VALUE")
                else ->
                    enqueueAudio(ByteBuffer.wrap(buf), readBytes, endOfStream)
            }
        }

        override fun run() {
            while (!stopAudioLoop) {
                readAndEnqueue(false)
            }
            readAndEnqueue(true)
        }
    }

    private var resultCode: Int = -1
    private var resultData: Intent? = null
    private var width: Int = 1280
    private var height: Int = 1280
    private var density: Int = 1
    private var useMic: Boolean = false
    private var cutRect: IntArray? = null
    private var recordSeconds: Int = 8

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val encoder = TextureMovieEncoder()

    private var projectionStarted = false
    private var recorderAttached = false
    private var stopAudioLoop = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    private val binder: IBinder = LocalBinder()

    private fun outputVideoFile(): File =
        File(DiffuseApplication.appContext().cacheDir, "export.mp4")

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE) {
            DiffuseUtils.logRemoteError("minBufferSize = AudioRecord.ERROR_BAD_VALUE")
            return false
        }

        val record = if (useMic) {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                48000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } else {
            return false
        }

        audioRecord = record
        if (record.state == AudioRecord.STATE_UNINITIALIZED) {
            return false
        }
        record.startRecording()
        return true
    }

    @Synchronized
    fun attachRecorder(): Boolean {
        val rect = cutRect ?: intArrayOf(0, 0, width, height)
        val left = rect[0].toFloat() / width
        val right = (width - rect[2]).toFloat() / width
        val top = rect[1].toFloat() / height
        val bottom = (height - rect[3]).toFloat() / height

        DiffuseUtils.logDebug(
            "attachRecorder, width $width height $height top $top bottom $bottom left $left right $right"
        )
        val eglContext = EGL14.eglGetCurrentContext()
        encoder.startRecording(
            TextureMovieEncoder.EncoderConfig(
                outputFile = outputVideoFile(),
                width = width,
                height = height,
                topCrop = top,
                bottomCrop = bottom,
                leftCrop = left,
                rightCrop = right,
                bitRate = width * height * 5,
                eglContext = eglContext
            )
        )
        encoder.setSurfaceListener(object : TextureMovieEncoder.SurfaceListener {
            override fun onSurfaceReady(surface: Surface) {
                virtualDisplay?.surface = surface
            }
        })
        startAudioRecord()
        stopAudioLoop = false
        audioThread = Thread(AudioRecordRunnable()).apply { start() }
        recorderAttached = true
        return true
    }

    @Synchronized
    fun detachRecorder(): Boolean {
        DiffuseUtils.logDebug("detachRecorder")
        if (projectionStarted && recorderAttached) {
            recorderAttached = false
            stopAudioLoop = true
            audioThread?.interrupt()
            audioThread = null
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null
            encoder.stopRecording()
            virtualDisplay?.surface = null
            return true
        }
        return false
    }

    fun startTimedRecording() {
        createVirtualDisplay()
        attachRecorder()
        Handler(Looper.myLooper() ?: Looper.getMainLooper()).postDelayed({
            detachRecorder()
            stopProjection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, recordSeconds * 1000L)
    }

    fun enqueueAudio(buffer: ByteBuffer, length: Int, endOfStream: Boolean) {
        if (encoder.isRecording()) {
            encoder.enqueueAudio(buffer, length, endOfStream)
        }
    }

    fun setRecorderCallback(callback: RecorderCallback) {
        encoder.setCallback(callback)
    }

    fun createVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LiveScreen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            null
        )
        projectionStarted = true
    }

    fun stopProjection(): Boolean {
        if (!projectionStarted) return false
        if (recorderAttached) detachRecorder()
        projectionStarted = false
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        return true
    }

    @Suppress("DEPRECATION")
    override fun onBind(intent: Intent?): IBinder? {
        if (intent == null) {
            DiffuseUtils.logRemoteError("Intent == null")
            stopSelf()
            return null
        }
        width = intent.getIntExtra("width", 1280)
        height = intent.getIntExtra("height", 1280)
        density = intent.getIntExtra("density", 1)
        cutRect = intent.getIntArrayExtra("cutRect")
        recordSeconds = intent.getIntExtra("recordSeconds", 8)
        useMic = intent.getBooleanExtra("useMic", false)

        DiffuseUtils.logDebug(
            "onBind, width $width height $height density $density cut ${cutRect?.contentToString()} useMic $useMic"
        )
        resultCode = intent.getIntExtra("resultCode", -1)
        resultData = intent.getParcelableExtra("data")

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NotificationChannelManager.CHANNEL_MISC)
                .setContentTitle("Recording")
                .build()
        } else {
            Notification.Builder(this).setContentTitle("Recording").build()
        }
        startForeground(1, notification)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
        return binder
    }
}
