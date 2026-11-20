package com.depthware.lwp.diffuse.manager

import android.media.audiofx.Visualizer
import android.os.SystemClock
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.util.BpmDetector
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.util.OnlineBpmFetcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
    private val lookupExecutor = Executors.newSingleThreadExecutor()
    private val lookupGeneration = AtomicInteger(0)

    @Volatile private var audioBpm = 0.0f
    @Volatile private var audioBpmConf = 0.0f
    @Volatile private var onlineBpm = 0.0f
    @Volatile private var onlineBpmSource = ""
    @Volatile private var onlineTrackKey = ""
    @Volatile private var lastReportedBpm = 0
    @Volatile private var lastReportedLabel = ""

    fun init() {
        lastBands = DoubleArray(4)
        bands = arrayOf(
            Band(60.0f, 250.0f),
            Band(250.0f, 500.0f),
            Band(500.0f, 2000.0f),
            Band(2000.0f, 16000.0f)
        )
        val vm = ViewModelManager.viewModel

        val syncMode = {
            val enabled = DiffuseUtils.getValueOrDefault(vm.enableLiveBeats, false)
            val mode = DiffuseUtils.getValueOrDefault(vm.bpmSourceMode, "auto")
            val useVisualizer = enabled && mode != "online"
            if (useVisualizer) {
                start(enableListener = true)
            } else {
                stop()
                bpmDetector.reset()
                audioBpm = 0.0f
                audioBpmConf = 0.0f
                lastBands = DoubleArray(4)
            }

            if (!enabled || mode == "audio") {
                clearOnlineBpm()
            } else {
                triggerOnlineLookupIfNeeded()
            }
            resolveAndPublishAudio()
        }

        vm.enableLiveBeats.observeForever {
            DiffuseUtils.logDebug("enableLiveBeats -> $it")
            syncMode()
        }
        vm.bpmSourceMode.observeForever {
            DiffuseUtils.logDebug("bpmSourceMode -> $it")
            syncMode()
        }

        val onTrackChanged = {
            if (DiffuseUtils.getValueOrDefault(vm.enableLiveBeats, false) &&
                DiffuseUtils.getValueOrDefault(vm.bpmSourceMode, "auto") != "audio"
            ) {
                triggerOnlineLookupIfNeeded()
            }
        }
        vm.playingTitle.observeForever { onTrackChanged() }
        vm.playingText.observeForever { onTrackChanged() }
        vm.playingPackageName.observeForever { onTrackChanged() }
        vm.isPlaying.observeForever { onTrackChanged() }
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

    private fun clearOnlineBpm() {
        lookupGeneration.incrementAndGet()
        onlineTrackKey = ""
        onlineBpm = 0.0f
        onlineBpmSource = ""
    }

    private fun triggerOnlineLookupIfNeeded() {
        val vm = ViewModelManager.viewModel
        val playing = DiffuseUtils.getValueOrDefault(vm.isPlaying, false)
        val title = vm.playingTitle.value.orEmpty().trim()
        val artist = vm.playingText.value.orEmpty().trim()
        val pkg = vm.playingPackageName.value.orEmpty()

        if (!playing || title.isEmpty()) {
            clearOnlineBpm()
            resolveAndPublishAudio()
            return
        }

        val key = "$artist|$title|$pkg"
        if (key == onlineTrackKey && onlineBpm > 20.0f) {
            resolveAndPublishAudio()
            return
        }

        onlineTrackKey = key
        onlineBpm = 0.0f
        onlineBpmSource = ""
        val gen = lookupGeneration.incrementAndGet()

        lookupExecutor.execute {
            val res = OnlineBpmFetcher.lookup(title, artist, pkg)
            if (lookupGeneration.get() != gen || onlineTrackKey != key) return@execute
            if (res != null && res.bpm > 20.0f) {
                onlineBpm = res.bpm
                onlineBpmSource = res.source
            } else {
                onlineBpm = 0.0f
                onlineBpmSource = ""
            }
            resolveAndPublishAudio()
        }
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
        lastBands = result
        val bpmResult = bpmDetector.update(result, SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0)
        audioBpm = bpmResult.bpm
        audioBpmConf = bpmResult.confidence
        resolveAndPublishAudio()
    }

    private fun resolveAndPublishAudio() {
        val vm = ViewModelManager.viewModel
        val enabled = DiffuseUtils.getValueOrDefault(vm.enableLiveBeats, false)
        val mode = DiffuseUtils.getValueOrDefault(vm.bpmSourceMode, "auto")

        var bpmToApply = 0.0f
        var confToApply = 0.0f
        var label = ""

        if (enabled) {
            when (mode) {
                "audio" -> {
                    bpmToApply = audioBpm
                    confToApply = audioBpmConf
                    label = "Audio"
                }
                "online" -> {
                    if (onlineBpm > 20.0f) {
                        bpmToApply = onlineBpm
                        confToApply = 0.90f
                        label = onlineBpmSource
                    }
                }
                else -> {
                    if (audioBpmConf >= 0.40f && audioBpm > 20.0f) {
                        bpmToApply = audioBpm
                        confToApply = audioBpmConf
                        label = "Audio"
                    } else if (onlineBpm > 20.0f) {
                        bpmToApply = onlineBpm
                        confToApply = 0.85f
                        label = onlineBpmSource
                    } else {
                        bpmToApply = audioBpm
                        confToApply = audioBpmConf
                        label = "Audio"
                    }
                }
            }
        }

        val bandsToSend = if (enabled && mode != "online") lastBands else DoubleArray(4)
        GdxPlayerManager.setAudio(bandsToSend, bpmToApply, confToApply)

        val roundedBpm = if (bpmToApply > 20.0f && confToApply >= 0.25f) {
            bpmToApply.roundToInt()
        } else {
            0
        }
        val shownLabel = if (roundedBpm > 0) label else ""
        if (roundedBpm != lastReportedBpm || shownLabel != lastReportedLabel) {
            lastReportedBpm = roundedBpm
            lastReportedLabel = shownLabel
            vm.detectedBpm.postValue(roundedBpm)
            vm.detectedBpmLabel.postValue(shownLabel)
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
