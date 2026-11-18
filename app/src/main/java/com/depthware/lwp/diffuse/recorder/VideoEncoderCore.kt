package com.depthware.lwp.diffuse.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask

class VideoEncoderCore(
    width: Int,
    height: Int,
    bitRate: Int,
    outputFile: File
) {
    companion object {
        private const val TAG = "VideoEncoderCore"
        private const val TIMEOUT_USEC = 10_000L
    }

    private val videoPath: String = outputFile.toString()
    private val inputSurface: Surface
    private var muxer: MediaMuxer?
    private var videoEncoder: MediaCodec?
    private var audioEncoder: MediaCodec?

    private val videoBufferInfo = MediaCodec.BufferInfo()
    private val audioBufferInfo = MediaCodec.BufferInfo()

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var endOfStream = false
    private var startTimeMs = 0L
    private var noAudioSamplesWritten = true
    private var callback: RecorderCallback? = null
    private var coverPath: String? = null
    private var progressTimer: Timer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressRunnable = Runnable {
        callback?.onRecordProgress(System.currentTimeMillis() - startTimeMs)
    }

    private val timerTask = object : TimerTask() {
        override fun run() {
            mainHandler.post(progressRunnable)
        }
    }

    init {
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        }
        Log.d(TAG, "videoFormat: $videoFormat")
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        muxer = MediaMuxer(videoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun getInputSurface(): Surface = inputSurface

    fun setCallback(cb: RecorderCallback?) {
        callback = cb
    }

    fun drainEncoder(endOfStream: Boolean) {
        Log.d(TAG, "drainEncoder($endOfStream)")
        if (endOfStream) {
            Log.d(TAG, "sending EOS to encoder")
            videoEncoder?.signalEndOfInputStream()
            this.endOfStream = true
        }
        drainVideo(endOfStream)
        drainAudio(endOfStream)
        if (muxerStarted && callback != null) {
            mainHandler.post(progressRunnable)
        }
    }

    private fun startMuxerIfReady() {
        if (videoTrackIndex == -1 || audioTrackIndex == -1 || muxerStarted) return
        muxer?.start()
        muxerStarted = true
        startTimeMs = System.currentTimeMillis()
        progressTimer = Timer().also { it.schedule(timerTask, 0L, 16L) }
    }

    private fun drainVideo(eos: Boolean) {
        val encoder = videoEncoder ?: return
        val m = muxer ?: return
        while (true) {
            val status = encoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_USEC)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || endOfStream) return
                Log.d(TAG, "no video output available, spinning to await EOS")
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw RuntimeException("format changed twice")
                val newFormat = encoder.outputFormat
                Log.d(TAG, "video encoder output format changed: $newFormat")
                videoTrackIndex = m.addTrack(newFormat)
                startMuxerIfReady()
            } else if (status < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: $status")
            } else if (!muxerStarted) {
                Log.w(TAG, "Muxer is not started, just return")
                encoder.releaseOutputBuffer(status, false)
            } else {
                val encodedData = encoder.getOutputBuffer(status)
                    ?: throw RuntimeException("encoderOutputBuffer $status was null")
                if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    videoBufferInfo.size = 0
                }
                if (videoBufferInfo.size != 0) {
                    encodedData.position(videoBufferInfo.offset)
                    encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)
                    m.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo)
                }
                encoder.releaseOutputBuffer(status, false)
                if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (eos) Log.d(TAG, "end of video stream reached")
                    else Log.w(TAG, "reached end of stream unexpectedly")
                    return
                }
            }
        }
    }

    fun drainAudio(eos: Boolean) {
        val encoder = audioEncoder ?: return
        val m = muxer ?: return
        while (true) {
            val status = encoder.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_USEC)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || endOfStream) return
                Log.d(TAG, "no audio output available, spinning to await EOS")
            }
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (audioTrackIndex != -1) throw RuntimeException("format changed twice")
                audioTrackIndex = m.addTrack(encoder.outputFormat)
                startMuxerIfReady()
            } else if (status < 0) {
                continue
            } else if (!muxerStarted) {
                Log.w(TAG, "Muxer is not started, just return")
                encoder.releaseOutputBuffer(status, false)
            } else {
                if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    audioBufferInfo.size = 0
                }
                if (audioBufferInfo.size != 0) {
                    val encodedData = encoder.getOutputBuffer(status)
                    if (encodedData != null) {
                        encodedData.position(audioBufferInfo.offset)
                        encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size)
                        m.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo)
                        noAudioSamplesWritten = false
                    }
                }
                encoder.releaseOutputBuffer(status, false)
                if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (eos) Log.d(TAG, "end of audio stream reached")
                    else Log.w(TAG, "reached end of stream unexpectedly")
                    endOfStream = true
                    return
                }
            }
        }
    }

    fun queueAudioSample(buffer: ByteBuffer?, length: Int, endOfStream: Boolean) {
        queueAudioSample(buffer, length, System.nanoTime() / 1000L, endOfStream)
    }

    fun queueAudioSample(buffer: ByteBuffer?, length: Int, presentationTimeUs: Long, endOfStream: Boolean) {
        val encoder = audioEncoder ?: return
        var len = length
        var queued = false
        while (!queued) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_USEC)
            if (index >= 0) {
                val inputBuf = encoder.getInputBuffer(index) ?: continue
                inputBuf.clear()
                if (len < 0) len = 0
                val src = if (buffer == null) {
                    len = 0
                    ByteBuffer.allocate(0)
                } else {
                    buffer
                }
                inputBuf.position(0)
                inputBuf.limit(len)
                src.position(0)
                src.limit(len)
                inputBuf.put(src)
                encoder.queueInputBuffer(
                    index,
                    0,
                    len,
                    presentationTimeUs,
                    if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                )
                queued = true
            }
        }
    }

    fun release() {
        Log.d(TAG, "releasing encoder objects")
        videoEncoder?.apply {
            stop()
            release()
        }
        videoEncoder = null

        audioEncoder?.apply {
            stop()
            release()
        }
        audioEncoder = null

        progressTimer?.cancel()
        progressTimer = null

        muxer?.let { m ->
            try {
                if (noAudioSamplesWritten && audioTrackIndex != -1) {
                    val dummy = ByteBuffer.wrap(ByteArray(2))
                    audioBufferInfo.set(0, 2, System.nanoTime() / 1000L, 0)
                    dummy.position(audioBufferInfo.offset)
                    dummy.limit(audioBufferInfo.offset + audioBufferInfo.size)
                    m.writeSampleData(audioTrackIndex, dummy, audioBufferInfo)
                }
                m.stop()
                mainHandler.post {
                    callback?.onRecordSuccess(videoPath, coverPath, System.currentTimeMillis() - startTimeMs)
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Record failed with error:", e)
                mainHandler.post {
                    callback?.onRecordError(e, System.currentTimeMillis() - startTimeMs)
                }
            }
            try {
                m.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Record failed with error:", e)
            }
            muxer = null
        }
    }
}
