package com.depthware.lwp.diffuse.ui.lyrics

import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LyricsViewModel(private val lifecycleOwner: LifecycleOwner) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val lyrics = MutableStateFlow(Lyrics.Empty)

    private var scrollView: ListenableNestedScrollView? = null
    private var lyricsView: LyricsView? = null
    private var isUserScrolling = false
    private var isLayoutFinished = false
    private var currentActiveIndex = -1
    private var latestPositionMs = 0L
    private var onLineClickListener: ((LyricsLine) -> Unit)? = null

    fun onViewCreated(
        view: View,
        onLineClick: ((LyricsLine) -> Unit)? = null
    ) {
        onLineClickListener = onLineClick
        val fadingEdgeLayout = view as FadingVerticalEdgeLayout
        val nestedScrollView = fadingEdgeLayout.getChildAt(0) as ListenableNestedScrollView
        val targetLyricsView = nestedScrollView.getChildAt(0) as LyricsView
        scrollView = nestedScrollView
        lyricsView = targetLyricsView

        scope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                nestedScrollView.collectUserAction(
                    onActionStart = {
                        nestedScrollView.isVerticalScrollBarEnabled = true
                        targetLyricsView.forEach { child ->
                            val lineView = child as LyricsLineView
                            lineView.animations.cancelBlur()
                            lineView.visibility = View.VISIBLE
                        }
                        isUserScrolling = true
                    },
                    onActionEnd = {
                        isUserScrolling = false
                    }
                )
            }
        }
    }

    fun setLyrics(newLyrics: Lyrics, currentPositionMs: Long = latestPositionMs) {
        lyrics.value = newLyrics
        latestPositionMs = currentPositionMs
        currentActiveIndex = -1
        isLayoutFinished = false
        val targetLyricsView = lyricsView ?: return
        targetLyricsView.update(newLyrics, onLineClickListener)
        updateOnLayout(currentPositionMs)
    }

    fun syncPosition(positionMs: Long) {
        latestPositionMs = positionMs
        val currentLyrics = lyrics.value
        val targetLyricsView = lyricsView ?: return
        if (currentLyrics == Lyrics.Empty || !isLayoutFinished) return
        val nextIndex = getCurrentLyricsLineIndex(positionMs)
        if (nextIndex != currentActiveIndex) {
            updateCurrentIndex(nextIndex)
        }
        for (i in 0 until targetLyricsView.childCount) {
            val child = targetLyricsView.getChildAt(i) as? LyricsLineView ?: continue
            child.updateWordSync(positionMs, i == currentActiveIndex)
        }
    }

    fun forceIndex(index: Int) {
        val currentLyrics = lyrics.value
        if (currentLyrics == Lyrics.Empty || !isLayoutFinished) return
        if (index in 0 until currentLyrics.lyrics.size) {
            updateCurrentIndex(index)
        }
    }

    private fun updateOnLayout(initialPositionMs: Long) {
        val nestedScrollView = scrollView ?: return
        val targetLyricsView = lyricsView ?: return
        targetLyricsView.doOnLayout {
            isLayoutFinished = false
            val currentLyrics = lyrics.value
            if (currentLyrics == Lyrics.Empty || targetLyricsView.childCount == 0) {
                isLayoutFinished = true
                return@doOnLayout
            }

            val index = getCurrentLyricsLineIndex(initialPositionMs)
            val currentLineChild = targetLyricsView.getChildAt(index) as? LyricsLineView
                ?: return@doOnLayout
            val maxScrollOffset =
                (targetLyricsView.measuredHeight + targetLyricsView.contentPaddingTop -
                    nestedScrollView.measuredHeight).toFloat()
                    .coerceAtLeast(targetLyricsView.contentPaddingTop.toFloat())
            val targetOffset =
                (currentLineChild.animations.getGlobalOffset().coerceAtMost(maxScrollOffset) -
                    targetLyricsView.contentPaddingTop).coerceAtLeast(0f)

            nestedScrollView.isVerticalScrollBarEnabled = false
            nestedScrollView.scrollTo(0, targetOffset.roundToInt())

            for (i in 0 until targetLyricsView.childCount) {
                val lineView = targetLyricsView.getChildAt(i) as LyricsLineView
                lineView.textOffset = 0f
                lineView.animations.updateImmediately(index)
                lineView.updateWordSync(initialPositionMs, i == index)
                lineView.visibility = View.VISIBLE
            }

            currentActiveIndex = index
            isLayoutFinished = true
        }
    }

    private fun updateCurrentIndex(index: Int) {
        val nestedScrollView = scrollView ?: return
        val targetLyricsView = lyricsView ?: return
        val currentLineChild = targetLyricsView.getChildAt(index) as? LyricsLineView ?: return
        currentActiveIndex = index

        if (!isUserScrolling) {
            val currentOffset = nestedScrollView.scrollY.toFloat()
            val maxScrollOffset =
                (targetLyricsView.measuredHeight + targetLyricsView.contentPaddingTop -
                    nestedScrollView.measuredHeight).toFloat()
                    .coerceAtLeast(targetLyricsView.contentPaddingTop.toFloat())
            val targetOffset =
                (currentLineChild.animations.getGlobalOffset().coerceAtMost(maxScrollOffset) -
                    targetLyricsView.contentPaddingTop).coerceAtLeast(0f)
            val deltaOffset = targetOffset - currentOffset

            nestedScrollView.isVerticalScrollBarEnabled = false
            nestedScrollView.scrollTo(0, targetOffset.roundToInt())

            val scrollOffset = nestedScrollView.scrollY.toFloat()
            targetLyricsView.forEach { child: View ->
                val lineView = child as LyricsLineView
                val targetTransitionY = lineView.textOffset + deltaOffset
                if (lineView.animations.checkIsInScreen(scrollOffset, targetTransitionY)) {
                    lineView.textOffset = targetTransitionY
                    lineView.animations.update(index)
                    if (lineView.visibility != View.VISIBLE) {
                        lineView.visibility = View.VISIBLE
                    }
                } else {
                    if (lineView.visibility != View.GONE) {
                        lineView.visibility = View.GONE
                    }
                    lineView.animations.updateImmediately(index)
                }
            }
        } else {
            targetLyricsView.forEach { child: View ->
                val lineView = child as LyricsLineView
                lineView.textOffset = 0f
                lineView.animations.update(index, true)
            }
        }
    }

    fun release() {
        scope.cancel()
        scrollView = null
        lyricsView = null
    }

    private fun getCurrentLyricsLineIndex(position: Long): Int {
        val currentLyrics = lyrics.value
        val line = currentLyrics.lyrics.indexOfLast { it.timestamp <= position }
        return if (line != -1) line else 0
    }
}
