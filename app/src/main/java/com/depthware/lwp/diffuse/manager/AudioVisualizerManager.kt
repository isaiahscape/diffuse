package com.depthware.lwp.diffuse.manager

import android.media.audiofx.Visualizer
import android.os.SystemClock
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.util.BpmDetector
import com.depthware.lwp.diffuse.util.DiffuseUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AudioVisualizerManager {

    private data class Band(val lowHz: Float, val highHz: Float)

    private var captureSize = 1024
    private var samplingRateMilliHz = 44_100_000
    private var lastBands = DoubleArray(4)
    private lateinit var bands: Array<Band>
    private var visualizer: Visualizer? = null
    private val bpmDetector = BpmDetector()
    private var lastReportedBpm = 0

    fun init() {
        lastBands = DoubleArray(4)
        bands = arrayOf(
            Band(60.0f, 250.0f),
            Band(250.0f, 500.0f),
            Band(500.0f, 2000.0f),
            Band(2000.0f, 16000.0f)
        )
        ViewModelManager.viewModel.enableLiveBeats.observeForever { enabled ->
            if (enabled) {
                DiffuseUtils.logDebug("enableLiveBeats -> true")
                start(enableListener = true)
            } else {
                DiffuseUtils.logDebug("enableLiveBeats -> false")
                stop()
                bpmDetector.reset()
                lastReportedBpm = 0
                ViewModelManager.viewModel.detectedBpm.postValue(0)
            }
            GdxPlayerManager.setAudio(lastBands, 0.0f, 0.0f)
        }
    }

    fun getOrCreateVisualizer(): Visualizer? {
        val hasPerm = DiffuseUtils.hasAudioPermissions(DiffuseApplication.appContext())
        val exists = visualizer != null
        if (!exists && hasPerm) {
            try {
                visualizer = Visualizer(0).apply {
                    enabled = false
                    scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                    measurementMode = Visualizer.MEASUREMENT_MODE_NONE
                    samplingRateMilliHz = Visualizer.getMaxCaptureRate()
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setCaptureSize(captureSize)
                }
            } catch (e: Exception) {
                DiffuseUtils.logError(e)
            }
        }
        return visualizer
    }

    fun start(enableListener: Boolean) {
        DiffuseUtils.logDebug("begin, enableListener = $enableListener")
        val vis = getOrCreateVisualizer() ?: return
        if (vis.enabled) return
        if (enableListener) {
            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) {
                        processFft(fft, samplingRate)
                    }

                    override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                },
                Visualizer.getMaxCaptureRate(),
                false,
                true
            )
        } else {
            vis.setDataCaptureListener(null, 1, false, false)
        }
        vis.enabled = true
        DiffuseUtils.logDebug("setEnabled true")
    }

    fun stop() {
        val vis = getOrCreateVisualizer() ?: return
        if (!vis.enabled) return
        vis.enabled = false
        vis.setDataCaptureListener(null, 1, false, false)
        DiffuseUtils.logDebug("stop, setEnabled false")
    }

    private fun processFft(fft: ByteArray, samplingRate: Int) {
        if (samplingRateMilliHz != samplingRate) {
            samplingRateMilliHz = samplingRate
        }
        val magnitudes = computeMagnitudes(fft)
        val result = DoubleArray(bands.size)
        for (i in bands.indices) {
            val band = bands[i]
            val lowBin = frequencyToBin(band.lowHz)
            val highBin = frequencyToBin(band.highHz)
            val clampedLow = max(1, min(lowBin, magnitudes.size - 1))
            val clampedHigh = max(clampedLow, min(highBin, magnitudes.size - 1))
            result[i] = bandEnergy(magnitudes, clampedLow, clampedHigh).toDouble()
        }
        val bpmResult = bpmDetector.update(result, SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0)
        GdxPlayerManager.setAudio(result, bpmResult.bpm, bpmResult.confidence)
        val roundedBpm = if (bpmResult.bpm > 20.0f && bpmResult.confidence >= 0.25f) {
            bpmResult.bpm.roundToInt()
        } else {
            0
        }
        if (roundedBpm != lastReportedBpm) {
            lastReportedBpm = roundedBpm
            ViewModelManager.viewModel.detectedBpm.postValue(roundedBpm)
        }
    }

    private fun computeMagnitudes(fft: ByteArray): FloatArray {
        val magnitudes = FloatArray(fft.size / 2)
        magnitudes[0] = abs(fft[0].toInt()).toFloat()
        magnitudes[1] = abs(fft[1].toInt()).toFloat()
        var i = 2
        var bin = 1
        while (i < fft.size) {
            magnitudes[bin] = hypot(
                ((fft[i].toInt() and 0xFF) - 128).toDouble(),
                ((fft[i + 1].toInt() and 0xFF) - 128).toDouble()
            ).toFloat()
            i += 2
            bin++
        }
        return magnitudes
    }

    private fun bandEnergy(magnitudes: FloatArray, startBin: Int, endBin: Int): Float {
        var sum = 0.0f
        var count = 0
        for (i in startBin..endBin) {
            sum += magnitudes[i]
            count++
        }
        val norm = if (count > 0) (sum / count) / 128.0f else 0.0f
        return norm * norm
    }

    private fun frequencyToBin(frequencyHz: Float): Int {
        // Visualizer reports samplingRate in milliHertz (~48,000,000), so convert to Hz first.
        val samplingRateHz = samplingRateMilliHz / 1000.0f
        if (samplingRateHz <= 0.0f) return 1
        val capSize = visualizer?.captureSize ?: captureSize
        return ((frequencyHz * capSize) / samplingRateHz).toInt()
    }
}
