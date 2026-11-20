package com.depthware.lwp.diffuse.util

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class BpmResult(val bpm: Float, val confidence: Float)

/**
 * Real-time tempo tracker using multi-band spectral flux and resampled autocorrelation
 * with a 120 BPM perceptual prior and parabolic sub-sample lag refinement.
 */
class BpmDetector {

    companion object {
        private const val GRID_RATE_HZ = 40.0
        private const val WINDOW_SECONDS = 6.0
        private const val MIN_SPAN_SECONDS = 2.5
        private const val ESTIMATE_INTERVAL_SEC = 0.25
        private const val SILENCE_TIMEOUT_SEC = 1.5
        private const val MIN_BPM = 60.0
        private const val MAX_BPM = 180.0
        private const val MAX_SAMPLES = 512
        private const val GRID_SIZE = 240
    }

    private val times = DoubleArray(MAX_SAMPLES)
    private val onsets = DoubleArray(MAX_SAMPLES)
    private val grid = DoubleArray(GRID_SIZE)
    private val prevBands = DoubleArray(4)

    private var head = 0
    private var count = 0
    private var avgFlux = 0.0
    private var lastActiveSec = 0.0
    private var lastEstimateSec = 0.0
    private var lastSampleSec = 0.0
    private var smoothedBpm = 0.0f
    private var confidence = 0.0f

    fun reset() {
        head = 0
        count = 0
        avgFlux = 0.0
        lastActiveSec = 0.0
        lastEstimateSec = 0.0
        lastSampleSec = 0.0
        smoothedBpm = 0.0f
        confidence = 0.0f
        prevBands.fill(0.0)
    }

    fun update(bands: DoubleArray, nowSec: Double): BpmResult {
        if (bands.size < 4) return BpmResult(smoothedBpm, confidence)

        val dt = if (lastSampleSec > 0.0) (nowSec - lastSampleSec).coerceIn(0.0, 0.25) else 0.04
        lastSampleSec = nowSec

        val totalEnergy = bands[0] + bands[1] + bands[2] + bands[3]
        val f0 = max(0.0, bands[0] - prevBands[0])
        val f1 = max(0.0, bands[1] - prevBands[1])
        val f2 = max(0.0, bands[2] - prevBands[2])
        val flux = f0 * 1.4 + f1 * 0.9 + f2 * 0.35

        prevBands[0] = bands[0]
        prevBands[1] = bands[1]
        prevBands[2] = bands[2]
        prevBands[3] = bands[3]

        if (totalEnergy > 1e-4) {
            lastActiveSec = nowSec
        } else if (lastActiveSec > 0.0 && (nowSec - lastActiveSec) > SILENCE_TIMEOUT_SEC) {
            confidence = max(0.0f, confidence - (dt * 0.8).toFloat())
            if (confidence < 0.05f) {
                smoothedBpm = 0.0f
                confidence = 0.0f
            }
            return BpmResult(smoothedBpm, confidence)
        }

        avgFlux += 0.08 * (flux - avgFlux)
        val onset = max(0.0, flux - avgFlux * 1.12)

        times[head] = nowSec
        onsets[head] = onset
        head = (head + 1) % MAX_SAMPLES
        if (count < MAX_SAMPLES) count++

        if ((nowSec - lastEstimateSec) >= ESTIMATE_INTERVAL_SEC) {
            lastEstimateSec = nowSec
            estimateTempo(nowSec)
        }

        return BpmResult(smoothedBpm, confidence)
    }

    private fun estimateTempo(nowSec: Double) {
        if (count < 24) return

        val oldestIdx = (head - count + MAX_SAMPLES) % MAX_SAMPLES
        val oldestTime = times[oldestIdx]
        val span = nowSec - oldestTime
        if (span < MIN_SPAN_SECONDS) return

        val window = min(WINDOW_SECONDS, span)
        val n = min(GRID_SIZE, (window * GRID_RATE_HZ).roundToInt())
        if (n < 80) return

        val startTime = nowSec - (n - 1) / GRID_RATE_HZ
        var cursor = oldestIdx
        var visited = 0
        var mean = 0.0

        for (g in 0 until n) {
            val t = startTime + g / GRID_RATE_HZ
            while (visited + 1 < count) {
                val nextIdx = (cursor + 1) % MAX_SAMPLES
                if (times[nextIdx] > t) break
                cursor = nextIdx
                visited++
            }

            val value = if (visited + 1 >= count) {
                onsets[cursor]
            } else {
                val nextIdx = (cursor + 1) % MAX_SAMPLES
                val t0 = times[cursor]
                val t1 = times[nextIdx]
                val alpha = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
                onsets[cursor] + (onsets[nextIdx] - onsets[cursor]) * alpha
            }
            grid[g] = value
            mean += value
        }
        mean /= n

        var variance = 0.0
        for (g in 0 until n) {
            grid[g] -= mean
            variance += grid[g] * grid[g]
        }
        if (variance < 1e-6) {
            confidence = max(0.0f, confidence - 0.1f)
            if (confidence < 0.05f) smoothedBpm = 0.0f
            return
        }

        val lagMin = max(1, floor(GRID_RATE_HZ * 60.0 / MAX_BPM).toInt())
        val lagMax = min(n / 2 - 1, ceil(GRID_RATE_HZ * 60.0 / MIN_BPM).toInt())
        if (lagMax <= lagMin + 2) return

        val corr = DoubleArray(lagMax + 2)
        var bestLag = lagMin
        var bestScore = Double.NEGATIVE_INFINITY
        val ln2 = ln(2.0)

        for (lag in (lagMin - 1)..(lagMax + 1)) {
            if (lag < 1 || lag >= n / 2) continue
            var num = 0.0
            var d1 = 0.0
            var d2 = 0.0
            val limit = n - lag
            for (i in 0 until limit) {
                val a = grid[i]
                val b = grid[i + lag]
                num += a * b
                d1 += a * a
                d2 += b * b
            }
            val denom = sqrt(d1 * d2)
            val r = if (denom > 1e-9) num / denom else 0.0
            corr[lag] = r

            if (lag in lagMin..lagMax) {
                val bpm = 60.0 * GRID_RATE_HZ / lag
                val oct = ln(bpm / 120.0) / ln2
                val prior = exp(-0.5 * (oct / 0.55) * (oct / 0.55))
                val score = r * (0.75 + 0.25 * prior)
                if (score > bestScore) {
                    bestScore = score
                    bestLag = lag
                }
            }
        }

        var refinedLag = bestLag.toDouble()
        if (bestLag in (lagMin + 1) until lagMax) {
            val y0 = corr[bestLag - 1]
            val y1 = corr[bestLag]
            val y2 = corr[bestLag + 1]
            val denom = y0 - 2.0 * y1 + y2
            if (abs(denom) > 1e-6) {
                val delta = (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
                refinedLag += delta
            }
        }

        val candBpm = (60.0 * GRID_RATE_HZ / refinedLag).toFloat()
        val candConf = ((corr[bestLag] - 0.12) / 0.38).coerceIn(0.0, 1.0).toFloat()

        if (smoothedBpm <= 0.0f) {
            if (candConf >= 0.30f) {
                smoothedBpm = candBpm
            }
        } else if (candConf >= 0.22f) {
            val relDiff = abs(candBpm - smoothedBpm) / smoothedBpm
            if (relDiff < 0.16f) {
                smoothedBpm += 0.22f * (candBpm - smoothedBpm)
            } else if (candConf >= 0.52f) {
                smoothedBpm += 0.35f * (candBpm - smoothedBpm)
            }
        }

        confidence += 0.25f * (candConf - confidence)
        if (confidence < 0.08f) {
            smoothedBpm = 0.0f
        }
    }
}
