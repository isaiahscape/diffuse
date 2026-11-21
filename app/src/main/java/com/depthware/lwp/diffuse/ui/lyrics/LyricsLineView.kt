package com.depthware.lwp.diffuse.ui.lyrics

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import com.depthware.lwp.diffuse.v2.R
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress("ViewConstructor")
class LyricsLineView internal constructor(
    context: Context,
    private val line: LyricsLine,
    private val onLineClick: ((LyricsLine) -> Unit)? = null
) : View(context) {
    private val horizontalPadding = 32.accordDp.px
    private val verticalPadding = 14.accordDp.px
    private val bgGapPx = 6.accordDp.px

    lateinit var animations: Animations
        private set

    private lateinit var staticLayout: StaticLayout
    private var bgStaticLayout: StaticLayout? = null
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (if (line.agent == "bg") 22f else 32f).accordSp.px
        color = Color.WHITE
        typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
    }
    private val bgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f.accordSp.px
        color = Color.WHITE
        typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
    }
    private val contentPaint = Paint().apply {
        xfermode = AccordAnimationUtils.addXfermode
    }
    private val srcAtopPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    private val blurRenderNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        RenderNode(BLUR_NODE_NAME)
    } else {
        null
    }

    private var currentWordPositionMs: Long = Long.MIN_VALUE
    private var isLineActive: Boolean = false

    var textOffset: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            translationY = value
        }

    var textAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blurRenderNode?.alpha = textAlpha
            } else {
                alpha = value
            }
            invalidate()
        }

    var textScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var blurRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val roundedValue = value.roundToInt()
            val renderEffect =
                if (roundedValue == 0) null
                else blurs?.get(roundedValue)
            blurRenderNode?.setRenderEffect(renderEffect)
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(rippleColorStateList, null, null)
        contentDescription = line.text
    }

    fun setAnimations(index: Int, globalOffset: Float, deviceHeight: Float) {
        animations = Animations(index, globalOffset, deviceHeight)
    }

    fun updateWordSync(positionMs: Long, isActiveLine: Boolean) {
        val wasActive = isLineActive
        isLineActive = isActiveLine
        val hasWords = line.words.isNotEmpty() || line.backgroundWords.isNotEmpty()
        if (!hasWords) {
            if (wasActive != isActiveLine) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    blurRenderNode?.discardDisplayList()
                }
                invalidate()
            }
            return
        }
        val minT = minOf(line.timestamp, line.backgroundTimestamp) - 50L
        val maxT = maxOf(line.endTimestamp, line.backgroundEndTimestamp) + 300L
        val shouldRefresh = wasActive != isActiveLine ||
            (isActiveLine && positionMs in minT..maxT) ||
            (currentWordPositionMs in minT..maxT && positionMs !in minT..maxT)
        if (!shouldRefresh && currentWordPositionMs == positionMs) return
        currentWordPositionMs = positionMs
        if (shouldRefresh) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !(isLineActive && blurRadius < 0.5f)) {
                blurRenderNode?.discardDisplayList()
            }
            invalidate()
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blurRenderNode?.discardDisplayList()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (height < 0 || width < 0) return
        val hP = horizontalPadding.roundToInt()
        val vP = verticalPadding.roundToInt()

        val text = line.text
        val layoutWidth = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = (layoutWidth - hP * 2).coerceAtLeast(0)

        staticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .build()

        var contentHeight = staticLayout.height
        val bgText = line.background
        bgStaticLayout = if (!bgText.isNullOrEmpty()) {
            StaticLayout.Builder
                .obtain(bgText, 0, bgText.length, bgPaint, textWidth)
                .build()
                .also { contentHeight += bgGapPx.roundToInt() + it.height }
        } else {
            null
        }

        val layoutHeight = contentHeight + vP * 2
        setMeasuredDimension(layoutWidth, layoutHeight)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blurRenderNode?.apply {
                setPosition(hP, vP, layoutWidth - hP, layoutHeight - vP)
                pivotX = 0f
                pivotY = layoutHeight / 2f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!::staticLayout.isInitialized) return
        val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), contentPaint)
        val scale = textScale
        val hasActiveWordAnim =
            (line.words.isNotEmpty() || line.backgroundWords.isNotEmpty()) &&
                isLineActive &&
                blurRadius < 0.5f &&
                currentWordPositionMs != Long.MIN_VALUE

        if (!hasActiveWordAnim && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            canvas.isHardwareAccelerated && blurRenderNode != null
        ) {
            with(blurRenderNode) {
                if (!hasDisplayList()) {
                    val recordingCanvas = beginRecording()
                    drawBlock(recordingCanvas, 1f)
                    endRecording()
                }
                scaleX = scale
                scaleY = scale
                canvas.drawRenderNode(this)
            }
        } else {
            canvas.save()
            canvas.translate(horizontalPadding, verticalPadding)
            canvas.scale(scale, scale, 0f, (height - verticalPadding * 2f) / 2f)
            drawBlock(canvas, textAlpha)
            canvas.restore()
        }
        canvas.restoreToCount(count)
    }

    private fun drawBlock(canvas: Canvas, alphaMultiplier: Float) {
        drawSweptLayout(
            canvas = canvas,
            layout = staticLayout,
            text = line.text,
            textPaint = paint,
            words = line.words,
            isActive = isLineActive,
            lineAlphaMultiplier = alphaMultiplier,
            unsungFraction = UNSUNG_WORD_ALPHA,
            sungFraction = 1f
        )

        val bgLayout = bgStaticLayout
        val bgText = line.background
        if (bgLayout != null && !bgText.isNullOrEmpty()) {
            canvas.save()
            canvas.translate(0f, staticLayout.height + bgGapPx)
            drawSweptLayout(
                canvas = canvas,
                layout = bgLayout,
                text = bgText,
                textPaint = bgPaint,
                words = line.backgroundWords,
                isActive = isLineActive,
                lineAlphaMultiplier = alphaMultiplier * BG_LINE_ALPHA_FACTOR,
                unsungFraction = UNSUNG_WORD_ALPHA,
                sungFraction = 1f
            )
            canvas.restore()
        }
    }

    private fun drawSweptLayout(
        canvas: Canvas,
        layout: StaticLayout,
        text: String,
        textPaint: TextPaint,
        words: List<LyricsWord>,
        isActive: Boolean,
        lineAlphaMultiplier: Float,
        unsungFraction: Float,
        sungFraction: Float
    ) {
        if (words.isEmpty() || !isActive || currentWordPositionMs == Long.MIN_VALUE) {
            textPaint.shader = null
            textPaint.color = Color.WHITE
            textPaint.alpha = (lineAlphaMultiplier * sungFraction * 255f).roundToInt().coerceIn(0, 255)
            layout.draw(canvas)
            return
        }

        val layoutW = layout.width.toFloat().coerceAtLeast(1f)
        val layoutH = layout.height.toFloat().coerceAtLeast(1f)
        val dimAlpha = (lineAlphaMultiplier * unsungFraction * 255f).roundToInt().coerceIn(0, 255)
        val fullAlpha = (lineAlphaMultiplier * sungFraction * 255f).roundToInt().coerceIn(0, 255)
        val dimColor = Color.argb(dimAlpha, 255, 255, 255)
        val fullColor = Color.argb(fullAlpha, 255, 255, 255)

        val maskLayer = canvas.saveLayer(0f, 0f, layoutW, layoutH, null)
        textPaint.shader = null
        textPaint.color = Color.WHITE
        textPaint.alpha = 255
        layout.draw(canvas)

        srcAtopPaint.shader = null
        srcAtopPaint.color = dimColor
        canvas.drawRect(0f, 0f, layoutW, layoutH, srcAtopPaint)

        val feather = (textPaint.textSize * 0.65f).coerceAtLeast(18f)

        for (word in words) {
            val startIdx = word.startOffset.coerceIn(0, text.length)
            val endIdx = word.endOffset.coerceIn(startIdx, text.length)
            if (endIdx <= startIdx) continue

            val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L).toFloat()
            val rawProgress = ((currentWordPositionMs - word.startTimeMs).toFloat() / duration)
                .coerceIn(0f, 1f)
            if (rawProgress <= 0f) continue

            val firstLayoutLine = layout.getLineForOffset(startIdx)
            val lastLayoutLine = layout.getLineForOffset((endIdx - 1).coerceAtLeast(startIdx))

            for (layoutLine in firstLayoutLine..lastLayoutLine) {
                val segStart = maxOf(startIdx, layout.getLineStart(layoutLine))
                val segEnd = minOf(endIdx, layout.getLineEnd(layoutLine))
                if (segEnd <= segStart) continue

                val h0 = layout.getPrimaryHorizontal(segStart)
                val h1 = layout.getPrimaryHorizontal(segEnd)
                val segLeft = minOf(h0, h1)
                val segRight = maxOf(h0, h1)
                if (segRight <= segLeft) continue

                val lineTop = layout.getLineTop(layoutLine).toFloat()
                val lineBottom = layout.getLineBottom(layoutLine).toFloat()

                if (rawProgress >= 1f) {
                    srcAtopPaint.shader = null
                    srcAtopPaint.color = fullColor
                } else {
                    val widthSpan = segRight - segLeft
                    val gradEnd = segLeft + (widthSpan + feather) * rawProgress
                    val gradStart = gradEnd - feather
                    srcAtopPaint.color = Color.WHITE
                    srcAtopPaint.shader = LinearGradient(
                        gradStart,
                        0f,
                        gradEnd,
                        0f,
                        fullColor,
                        dimColor,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(segLeft, lineTop, segRight, lineBottom, srcAtopPaint)
            }
        }
        srcAtopPaint.shader = null
        canvas.restoreToCount(maskLayer)
    }

    override fun performClick(): Boolean {
        onLineClick?.invoke(line)
        super.performClick()
        return true
    }

    inner class Animations(
        private val index: Int,
        private var globalOffset: Float,
        private val deviceHeight: Float
    ) {
        private var targetOffset = 0f

        private val offsetFractionAnimator =
            accordFloatAnimator(700L, interpolator = offsetFractionInterpolator) {
                textOffset = targetOffset * (1f - it.currentValue)
            }

        private val alphaAnimator =
            accordFloatAnimator(500L, interpolator = AccordAnimationUtils.decelerateInterpolator) {
                textAlpha = it.currentValue
            }

        private val scaleAnimator =
            accordFloatAnimator(500L, interpolator = AccordAnimationUtils.decelerateInterpolator) {
                textScale = it.currentValue
            }

        private val blurRadiusAnimator = accordFloatAnimator(100L) {
            blurRadius = it.currentValue
        }

        fun getGlobalOffset() = globalOffset

        fun setGlobalOffset(offset: Float) {
            globalOffset = offset
        }

        fun cancelBlur() {
            blurRadiusAnimator.snapTo(0f)
        }

        fun checkIsInScreen(scrollOffset: Float, targetOffset: Float): Boolean {
            val previousScrollOffset = scrollOffset - targetOffset
            val viewHeight = height
            return if (previousScrollOffset < scrollOffset) {
                globalOffset + viewHeight > previousScrollOffset &&
                    globalOffset < scrollOffset + deviceHeight
            } else {
                globalOffset + viewHeight > scrollOffset &&
                    globalOffset < previousScrollOffset + deviceHeight
            }
        }

        fun updateImmediately(targetIndex: Int) {
            val isActivated = index == targetIndex
            val targetAlpha = if (isActivated) ACTIVE_ALPHA else INACTIVE_ALPHA
            val targetScale = if (isActivated) ACTIVE_SCALE else INACTIVE_SCALE
            val targetBlurRadius =
                (abs(index - targetIndex) * blurRadiusStep).coerceAtMost(maxBlurRadius)

            offsetFractionAnimator.snapTo(1f)
            alphaAnimator.snapTo(targetAlpha)
            scaleAnimator.snapTo(targetScale)
            blurRadiusAnimator.snapTo(targetBlurRadius)
        }

        fun update(targetIndex: Int, preventBlurUpdate: Boolean = false) {
            val isActivated = index == targetIndex
            val targetAlpha = if (isActivated) ACTIVE_ALPHA else INACTIVE_ALPHA
            val targetScale = if (isActivated) ACTIVE_SCALE else INACTIVE_SCALE
            val targetBlurRadius =
                (abs(index - targetIndex) * blurRadiusStep).coerceAtMost(maxBlurRadius)

            val delay = if (index < targetIndex) {
                0L
            } else {
                ((index - targetIndex) * 20L + 10L).coerceAtMost(190L)
            }
            val secondaryDelay = if (isActivated) delay else delay + 180L

            targetOffset = textOffset
            offsetFractionAnimator.startDelay = delay
            offsetFractionAnimator.start()

            if (alphaAnimator.targetValue != targetAlpha) {
                alphaAnimator.startDelay = secondaryDelay
                alphaAnimator.animateTo(targetAlpha)
            }

            if (scaleAnimator.targetValue != targetScale) {
                scaleAnimator.startDelay = secondaryDelay
                scaleAnimator.animateTo(targetScale)
            }

            if (!preventBlurUpdate) {
                if (blurRadiusAnimator.targetValue != targetBlurRadius) {
                    blurRadiusAnimator.startDelay = secondaryDelay
                    blurRadiusAnimator.animateTo(targetBlurRadius)
                }
            }
        }
    }

    private companion object {
        const val BLUR_NODE_NAME = "LyricsLineViewBlurNode"

        const val ACTIVE_ALPHA = 0.94f
        const val INACTIVE_ALPHA = 0.24f
        const val UNSUNG_WORD_ALPHA = 0.40f
        const val BG_LINE_ALPHA_FACTOR = 0.78f
        const val ACTIVE_SCALE = 1f
        const val INACTIVE_SCALE = 0.96f
        val maxBlurRadius = 8.accordDp.px
        val blurRadiusStep = 2.accordDp.px

        val offsetFractionInterpolator = PathInterpolator(0.6f, 0f, 0.2f, 1f)

        val blurs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (1..maxBlurRadius.roundToInt()).associateWith {
                val radius = it.toFloat()
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
            }
        } else {
            null
        }

        val rippleColorStateList = ColorStateList.valueOf(0x60FFFFFF)
    }
}
