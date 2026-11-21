package com.depthware.lwp.diffuse.ui.lyrics

import android.animation.TimeInterpolator
import android.content.Context
import android.content.res.Resources
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.util.TypedValue
import android.view.Choreographer
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object AccordUiUtils {

    var density: Float = 0f
        get() = if (field > 0f) field else Resources.getSystem().displayMetrics.density
        private set

    var scaledDensity: Float = 0f
        get() = if (field > 0f) field else TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            1f,
            Resources.getSystem().displayMetrics
        )
        private set

    fun init(context: Context) {
        val resources = context.resources
        density = resources.displayMetrics.density
        scaledDensity = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            1f,
            resources.displayMetrics
        )
    }
}

@JvmInline
value class AccordDp(val value: Float) {
    inline val px: Float
        get() = value * AccordUiUtils.density
}

inline val Int.accordDp: AccordDp
    get() = AccordDp(this.toFloat())

inline val Float.accordDp: AccordDp
    get() = AccordDp(this)

inline val Double.accordDp: AccordDp
    get() = AccordDp(this.toFloat())

@JvmInline
value class AccordSp(val value: Float) {
    inline val px: Float
        get() = value * AccordUiUtils.scaledDensity
}

inline val Int.accordSp: AccordSp
    get() = AccordSp(this.toFloat())

inline val Float.accordSp: AccordSp
    get() = AccordSp(this)

inline val Double.accordSp: AccordSp
    get() = AccordSp(this.toFloat())

@Suppress("NOTHING_TO_INLINE")
inline fun accordLerp(
    start: Float,
    stop: Float,
    amount: Float,
    interpolator: (Float) -> Float = { it }
): Float {
    val t = interpolator(amount)
    return start + (stop - start) * t
}

private val FloatLerp = { from: Float, to: Float, fraction: Float ->
    accordLerp(from, to, fraction)
}

fun accordFloatAnimator(
    duration: Long,
    initialValue: Float = 0f,
    targetValue: Float = 1f,
    startDelay: Long = 0L,
    interpolator: TimeInterpolator? = null,
    listener: AccordAnimationUtils.Animator.ValueUpdateListener<Float>
) = AccordAnimationUtils.LinearAnimator(
    initialValue = initialValue,
    targetValue = targetValue,
    startDelay = startDelay,
    duration = duration,
    interpolator = interpolator,
    listener = listener,
    lerp = FloatLerp
)

fun accordSpringAnimator(
    initialValue: Float,
    stiffness: Float = 300f,
    dampingRatio: Float = 1f,
    valueThreshold: Float = 0.5f,
    minValue: Float = -Float.MAX_VALUE,
    maxValue: Float = Float.MAX_VALUE,
    listener: AccordAnimationUtils.Animator.ValueUpdateListener<Float>
) = AccordAnimationUtils.SpringAnimator(
    initialValue = initialValue,
    stiffness = stiffness,
    dampingRatio = dampingRatio,
    valueThreshold = valueThreshold,
    minValue = minValue,
    maxValue = maxValue,
    listener = listener
)

object AccordAnimationUtils {

    val easingStandardInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val decelerateInterpolator: Interpolator = DecelerateInterpolator(1.7f)
    val addXfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)

    open class LinearAnimator<T>(
        initialValue: T,
        targetValue: T,
        override var startDelay: Long = 0L,
        var duration: Long = 300L,
        var interpolator: TimeInterpolator? = null,
        private val listener: Animator.ValueUpdateListener<T>,
        private val lerp: (from: T, to: T, fraction: Float) -> T
    ) : Animator<T> {
        final override var initialValue: T = initialValue
            private set
        final override var targetValue: T = targetValue
            private set
        final override var currentValue: T = initialValue
            private set
        final override val currentVelocity: Float
            get() = throw UnsupportedOperationException("LinearAnimator does not support velocity")

        final override var isRunning = false
            private set

        private var frameCallback: Choreographer.FrameCallback? = null
        private val choreographer = Choreographer.getInstance()
        private var currentCallbackIncrement = 0

        override fun start() {
            currentCallbackIncrement++
            val currentCallback = currentCallbackIncrement

            val durationScale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.animation.ValueAnimator.getDurationScale()
            } else {
                1f
            }

            cancel()
            isRunning = true
            var startTime = 0L

            val initialValue = initialValue
            val targetValue = targetValue
            val interpolator = interpolator
            val delay = (startDelay * 1_000_000L * durationScale).toLong()
            val duration = (duration * 1_000_000L * durationScale).toLong()

            frameCallback = Choreographer.FrameCallback { time ->
                if (currentCallback != currentCallbackIncrement) {
                    return@FrameCallback
                }

                if (startTime == 0L) {
                    startTime = time
                }

                val playTime = time - startTime
                if (playTime < delay) {
                    choreographer.postFrameCallback(frameCallback)
                    return@FrameCallback
                }

                if (duration <= 0L) {
                    end()
                    return@FrameCallback
                }

                val fraction = (playTime - delay).toFloat() / duration
                if (fraction >= 1f) {
                    end()
                } else {
                    val interpolatedFraction = interpolator?.getInterpolation(fraction) ?: fraction
                    currentValue = lerp(initialValue, targetValue, interpolatedFraction)
                    listener.onValueUpdate(this)
                    choreographer.postFrameCallback(frameCallback)
                }
            }

            choreographer.postFrameCallback(frameCallback)
        }

        override fun animateTo(targetValue: T) {
            initialValue = currentValue
            this.targetValue = targetValue
            start()
        }

        override fun snapTo(targetValue: T) {
            currentCallbackIncrement++
            cancel()
            currentValue = targetValue
            this.targetValue = targetValue
            listener.onValueUpdate(this)
        }

        override fun cancel() {
            isRunning = false
            if (frameCallback != null) {
                choreographer.removeFrameCallback(frameCallback)
            }
        }

        override fun end() {
            isRunning = false
            if (frameCallback != null) {
                choreographer.removeFrameCallback(frameCallback)
            }
            currentValue = targetValue
            listener.onValueUpdate(this)
        }
    }

    open class SpringAnimator(
        initialValue: Float,
        stiffness: Float = 300f,
        dampingRatio: Float = 1f,
        valueThreshold: Float = 0.5f,
        private val minValue: Float = -Float.MAX_VALUE,
        private val maxValue: Float = Float.MAX_VALUE,
        private val listener: Animator.ValueUpdateListener<Float>
    ) : Animator<Float> {
        private val spring = Spring(k = stiffness, m = 1f, zeta = dampingRatio)

        final override var initialValue: Float = initialValue
            private set
        final override var targetValue: Float = initialValue
            private set
        final override var currentValue: Float = initialValue
            private set
        final override var currentVelocity: Float = 0f
            private set

        override var startDelay: Long = 0L

        final override var isRunning = false
            private set

        var valueThreshold: Float = valueThreshold

        private var frameCallback: Choreographer.FrameCallback? = null
        private val choreographer = Choreographer.getInstance()
        private var currentCallbackIncrement = 0

        override fun start() {
            currentCallbackIncrement++
            val currentCallback = currentCallbackIncrement

            val durationScale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.animation.ValueAnimator.getDurationScale()
            } else {
                1f
            }

            cancel()
            isRunning = true
            var startTime = 0L

            val initialValue = initialValue
            val targetValue = targetValue
            val initialVelocity = currentVelocity
            val valueThreshold = valueThreshold
            val delay = (startDelay * 1_000_000L * durationScale).toLong()

            frameCallback = Choreographer.FrameCallback { time ->
                if (currentCallback != currentCallbackIncrement) {
                    return@FrameCallback
                }

                if (startTime == 0L) {
                    startTime = time
                }

                val playTime = time - startTime
                if (playTime < delay) {
                    choreographer.postFrameCallback(frameCallback)
                    return@FrameCallback
                }

                if (durationScale <= 0f) {
                    end()
                    return@FrameCallback
                }

                val springState = spring.stateAt(
                    t = (playTime - delay) / 1_000_000_000f / durationScale,
                    x0 = initialValue,
                    x1 = targetValue,
                    v0 = initialVelocity,
                    tolerance = valueThreshold
                )
                if (springState.isAtEquilibrium) {
                    end()
                } else {
                    currentValue = springState.x.coerceIn(minValue, maxValue)
                    currentVelocity = springState.v
                    listener.onValueUpdate(this)
                    choreographer.postFrameCallback(frameCallback)
                }
            }

            choreographer.postFrameCallback(frameCallback)
        }

        override fun animateTo(targetValue: Float) {
            initialValue = currentValue
            this.targetValue = targetValue.coerceIn(minValue, maxValue)
            start()
        }

        override fun snapTo(targetValue: Float) {
            currentCallbackIncrement++
            cancel()
            val clamped = targetValue.coerceIn(minValue, maxValue)
            currentValue = clamped
            this.targetValue = clamped
            currentVelocity = 0f
            listener.onValueUpdate(this)
        }

        override fun cancel() {
            isRunning = false
            if (frameCallback != null) {
                choreographer.removeFrameCallback(frameCallback)
            }
        }

        override fun end() {
            isRunning = false
            if (frameCallback != null) {
                choreographer.removeFrameCallback(frameCallback)
            }
            currentValue = targetValue
            currentVelocity = 0f
            listener.onValueUpdate(this)
        }
    }

    internal class Spring(
        private var k: Float,
        private var m: Float,
        private var zeta: Float
    ) {
        private var omega0 = sqrt(k / m)

        private var omegaD = when {
            zeta < 1f -> omega0 * sqrt(1 - zeta * zeta)
            zeta > 1f -> omega0 * sqrt(zeta * zeta - 1)
            else -> 0f
        }

        init {
            require(zeta > 0) { "Damping ratio must be greater than 0" }
        }

        fun stateAt(t: Float, x0: Float, x1: Float, v0: Float, tolerance: Float): SpringState {
            val zeta = zeta
            val omega0 = omega0
            val omegaD = omegaD
            val deltaX = x0 - x1

            return when {
                zeta == 1f -> {
                    val expTerm = exp(-omega0 * t)
                    val c1 = (v0 + omega0 * deltaX) * t
                    val x = x1 + expTerm * (deltaX + c1)
                    val v = expTerm * (v0 - omega0 * c1)
                    createState(x, x1, v, tolerance)
                }

                zeta < 1f -> {
                    val c1 = zeta * omega0
                    val expTerm = exp(-c1 * t)
                    val c2 = omegaD * t
                    val cosTerm = cos(c2)
                    val sinTerm = sin(c2)
                    val c3 = (v0 + c1 * deltaX) / omegaD
                    val x = x1 + expTerm * (deltaX * cosTerm + c3 * sinTerm)
                    val v = expTerm * (v0 * cosTerm - (c1 * c3 + deltaX * omegaD) * sinTerm)
                    createState(x, x1, v, tolerance)
                }

                else -> {
                    val c1 = zeta * omega0
                    val ca = c1 - omegaD
                    val cb = c1 + omegaD
                    val expTerm1 = exp(-ca * t)
                    val expTerm2 = exp(-cb * t)
                    val doubleOmegaD = 2 * omegaD
                    val a = (v0 + cb * deltaX) / doubleOmegaD
                    val b = (v0 - ca * deltaX) / doubleOmegaD
                    val x = x1 + a * expTerm1 + b * expTerm2
                    val v = a * -ca * expTerm1 + b * -cb * expTerm2
                    createState(x, x1, v, tolerance)
                }
            }
        }

        private fun createState(x: Float, x1: Float, v: Float, tolerance: Float): SpringState {
            val isAtEquilibrium =
                abs(x - x1) < tolerance && abs(v) < tolerance * VELOCITY_THRESHOLD_MULTIPLIER
            return SpringState(x, v, isAtEquilibrium)
        }

        class SpringState(
            val x: Float,
            val v: Float,
            val isAtEquilibrium: Boolean
        )

        private companion object {
            const val VELOCITY_THRESHOLD_MULTIPLIER = 1000.0 / 16.0
        }
    }

    interface Animator<T> {
        val initialValue: T
        val targetValue: T
        val currentValue: T
        val currentVelocity: Float
        var startDelay: Long
        val isRunning: Boolean

        fun start()
        fun animateTo(targetValue: T)
        fun snapTo(targetValue: T)
        fun cancel()
        fun end()

        fun interface ValueUpdateListener<T> {
            fun onValueUpdate(animator: Animator<T>)
        }
    }
}
