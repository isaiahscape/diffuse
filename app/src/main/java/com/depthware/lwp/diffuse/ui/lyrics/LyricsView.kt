package com.depthware.lwp.diffuse.ui.lyrics

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.forEach
import androidx.core.view.isNotEmpty
import kotlin.math.roundToInt

class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    val contentPaddingTop = 160.accordDp.px.roundToInt()

    fun update(lyrics: Lyrics, onLineClick: ((LyricsLine) -> Unit)? = null) {
        forEach { child: View ->
            (child as? LyricsLineView)?.release()
        }
        removeAllViews()
        val deviceHeight = resources.displayMetrics.heightPixels.toFloat()
        lyrics.lyrics.forEachIndexed { index, line ->
            val view = LyricsLineView(context, line, onLineClick)
            view.setAnimations(index, 0f, deviceHeight)
            addView(view)
        }
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        forEach { child: View ->
            (child as? LyricsLineView)?.release()
        }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        var layoutHeight = contentPaddingTop
        forEach { child: View ->
            child.measure(widthMeasureSpec, heightMeasureSpec)
            val lineView = child as LyricsLineView
            lineView.animations.setGlobalOffset(layoutHeight.toFloat())
            layoutHeight += lineView.measuredHeight
        }
        if (isNotEmpty()) {
            val lastChild = getChildAt(childCount - 1)
            val containerHeight = (parent as? View)?.height?.takeIf { it > 0 } ?: rootView.height
            layoutHeight += (containerHeight - contentPaddingTop - lastChild.measuredHeight).coerceAtLeast(0)
        }

        setMeasuredDimension(width, layoutHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var top = contentPaddingTop
        forEach { child: View ->
            val height = child.measuredHeight
            child.layout(0, top, child.measuredWidth, top + height)
            top += height
        }
    }
}
