package com.depthware.lwp.diffuse.ui.lyrics

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class ScrollState {
    Idle, Dragging, Settling
}

interface FastScrollerTargetView {
    val viewScrollState: ScrollState
    fun setScrollState(state: ScrollState)
    fun nestedScrollBy(x: Int, y: Int)
    fun computeVerticalScrollRange(): Int
    fun computeVerticalScrollOffset(): Int
    fun computeVerticalScrollExtent(): Int
}

class FastDrawable {
    val paint = Paint()
    val bounds = RectF()
    private var cornerRadius = 0f
    private var alpha = 255
    private var colorFilter: ColorFilter? = null

    fun draw(canvas: Canvas) {
        if (cornerRadius == 0f) {
            canvas.drawRect(bounds, paint)
        } else {
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
        }
    }

    fun setAlpha(alpha: Int) {
        this.alpha = alpha
        paint.alpha = alpha
    }

    fun setColorFilter(colorFilter: ColorFilter?) {
        this.colorFilter = colorFilter
        paint.colorFilter = colorFilter
    }

    fun setBounds(left: Float, top: Float, right: Float, bottom: Float) {
        bounds.set(left, top, right, bottom)
    }

    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        bounds.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    fun setBounds(bounds: RectF) {
        this.bounds.set(bounds)
    }

    fun setBounds(bounds: Rect) {
        this.bounds.set(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat()
        )
    }

    fun setCornerRadius(cornerRadius: Float) {
        this.cornerRadius = cornerRadius
    }
}

class FastScroller<T>(private val view: T) where T : View, T : FastScrollerTargetView {
    private val horizontalPadding = 2.5.accordDp.px
    private val idleScrollerWidth = 3.accordDp.px
    private val activeScrollerWidth = 5.accordDp.px
    private val idleScrollerAlpha = 0.4f
    private val activeScrollerAlpha = 0.6f
    private val drawable = FastDrawable().apply {
        paint.color = Color.RED
        paint.alpha = (idleScrollerAlpha * 0xFF).roundToInt()
        paint.strokeWidth = idleScrollerWidth
        setCornerRadius(idleScrollerWidth)
    }

    private var shouldIntercept = false
    private var startY = 0f
    private var dy = 0f
    private var lastY = Float.NaN
    private var isDragging = false
    private var toolbarHasSnapBehavior = false
    private var dragDeltaMultiplier = 1f

    private val handler = Handler(Looper.getMainLooper())

    private val widthAnimator = accordSpringAnimator(
        idleScrollerWidth,
        stiffness = 150f,
        minValue = idleScrollerWidth,
        maxValue = activeScrollerWidth
    ) {
        val value = it.currentValue
        drawable.paint.strokeWidth = value
        drawable.setCornerRadius(value / 2)
        drawable.bounds.left = drawable.bounds.right - value
        val scrollState = view.viewScrollState
        if (scrollState == ScrollState.Idle) {
            view.postInvalidateOnAnimation()
        }
    }

    private val alphaAnimator = accordSpringAnimator(
        idleScrollerAlpha,
        stiffness = 150f,
        valueThreshold = 0.01f,
        minValue = 0f,
        maxValue = activeScrollerAlpha
    ) {
        drawable.paint.alpha = (it.currentValue * 0xFF).roundToInt()
        val scrollState = view.viewScrollState
        if (scrollState == ScrollState.Idle) {
            view.postInvalidateOnAnimation()
        }
    }

    fun show() {
        val targetValue = if (isDragging) activeScrollerAlpha else idleScrollerAlpha
        if (alphaAnimator.targetValue == targetValue) return
        handler.removeCallbacksAndMessages(null)
        alphaAnimator.animateTo(targetValue)
    }

    private fun showImmediately() {
        handler.removeCallbacksAndMessages(null)
        alphaAnimator.snapTo(activeScrollerAlpha)
    }

    fun hide() {
        if (alphaAnimator.targetValue == 0f) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!isDragging && view.viewScrollState == ScrollState.Idle) {
                alphaAnimator.animateTo(0f)
            }
        }, 1000)
    }

    fun hideScrollerImmediately() {
        if (alphaAnimator.currentValue == 0f) return
        handler.removeCallbacksAndMessages(null)
        alphaAnimator.snapTo(0f)
    }

    fun draw(c: Canvas) {
        drawable.draw(c)
    }

    fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        shouldIntercept = alphaAnimator.currentValue > 0f &&
            e.x >= view.width - horizontalPadding * 2 - activeScrollerWidth &&
            (e.y >= drawable.bounds.top && e.y <= drawable.bounds.bottom)
        if (shouldIntercept) {
            startY = e.rawY
        }
        return shouldIntercept
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        if (!shouldIntercept) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {}

            MotionEvent.ACTION_MOVE -> {
                if (lastY.isNaN()) {
                    lastY = e.rawY
                    isDragging = true

                    val paddingTop = view.paddingTop
                    val height: Float =
                        (view.height.toFloat() - paddingTop - view.paddingBottom).coerceAtLeast(0f)
                    val range: Float = view.computeVerticalScrollRange().toFloat().coerceAtLeast(height)
                    val extent: Float = view.computeVerticalScrollExtent().toFloat().coerceAtLeast(height)
                    dragDeltaMultiplier = range / extent - 0.5f

                    showImmediately()
                    widthAnimator.animateTo(activeScrollerWidth)
                    view.setScrollState(ScrollState.Dragging)

                    val toolbarLayout =
                        ((view.parent as? CoordinatorLayout)
                            ?.getChildAt(0) as? AppBarLayout)
                            ?.getChildAt(0) as? CollapsingToolbarLayout
                    val params = toolbarLayout?.layoutParams as? AppBarLayout.LayoutParams
                    if (params != null) {
                        toolbarHasSnapBehavior =
                            params.scrollFlags and AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP != 0
                        if (toolbarHasSnapBehavior) {
                            params.setScrollFlags(
                                params.scrollFlags xor AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
                            )
                        }
                    }
                }

                dy = e.rawY - lastY
                lastY = e.rawY

                view.nestedScrollBy(0, (dy * dragDeltaMultiplier).roundToInt())
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                lastY = Float.NaN

                alphaAnimator.animateTo(idleScrollerAlpha)
                widthAnimator.animateTo(idleScrollerWidth)
                hide()
                view.setScrollState(ScrollState.Idle)

                if (toolbarHasSnapBehavior) {
                    val appBarLayout =
                        (view.parent as? CoordinatorLayout)?.getChildAt(0) as? AppBarLayout
                    if (appBarLayout != null) {
                        val toolbarLayout = appBarLayout.getChildAt(0) as? CollapsingToolbarLayout
                        val params = toolbarLayout?.layoutParams as? AppBarLayout.LayoutParams
                        params?.setScrollFlags(
                            params.scrollFlags or AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
                        )
                        if (!view.canScrollVertically(-1)) {
                            val expanded = when {
                                dy < 0f -> true
                                dy > 0f -> false
                                else ->
                                    appBarLayout.height - appBarLayout.totalScrollRange >
                                        appBarLayout.height / 2
                            }
                            appBarLayout.setExpanded(expanded, true)
                        }
                    }
                }
            }
        }
        return isDragging
    }

    fun updateBounds() {
        val x = view.width - horizontalPadding
        val paddingTop = view.paddingTop
        val height: Float =
            (view.height.toFloat() - paddingTop - view.paddingBottom).coerceAtLeast(0f)
        val scrollY: Float = view.computeVerticalScrollOffset().toFloat()
        val scrollRange: Float = view.computeVerticalScrollRange().toFloat()

        val range: Float = scrollRange.coerceAtLeast(height).coerceAtLeast(1f)
        val offset: Float = scrollY.coerceIn(0f, scrollRange.coerceAtLeast(0f))
        val extent: Float = view.computeVerticalScrollExtent().toFloat().coerceAtLeast(height)

        val top = offset.coerceAtLeast(0f) / range * height + paddingTop
        val bottom = (offset.coerceAtMost(scrollRange) + extent) / range * height + paddingTop
        drawable.setBounds(x - drawable.paint.strokeWidth, top, x, bottom)
    }
}

open class ListenableNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {
    private val _userActionFlow = MutableStateFlow(UserAction.None)
    val userActionFlow: StateFlow<UserAction> = _userActionFlow

    private var lastTouchY = 0f

    internal fun setUserAction(userAction: UserAction) {
        _userActionFlow.tryEmit(userAction)
    }

    suspend fun collectUserAction(
        onActionStart: () -> Unit,
        onActionEnd: () -> Unit
    ) {
        var isStarted = false
        userActionFlow.collectLatest { userAction ->
            when (userAction) {
                UserAction.Drag -> {
                    if (!isStarted) {
                        onActionStart()
                        isStarted = true
                    }
                }

                UserAction.Fling -> {
                    if (!isStarted) {
                        onActionStart()
                        isStarted = true
                    }
                    var lastY = scrollY
                    var isScrollEnd = false
                    while (!isScrollEnd) {
                        delay(100)
                        val newY = scrollY
                        isScrollEnd = lastY == newY
                        lastY = newY
                    }
                    _userActionFlow.emit(UserAction.None)
                }

                UserAction.None -> {
                    onActionEnd()
                    isStarted = false
                }
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = ev.y
                _userActionFlow.tryEmit(UserAction.None)
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = ev.y - lastTouchY
                if (deltaY != 0f) {
                    _userActionFlow.tryEmit(UserAction.Drag)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                _userActionFlow.tryEmit(UserAction.None)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun fling(velocityY: Int) {
        super.fling(velocityY)
        _userActionFlow.tryEmit(UserAction.Fling)
    }

    enum class UserAction {
        None,
        Drag,
        Fling
    }
}

class FastScrollerNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ListenableNestedScrollView(context, attrs), FastScrollerTargetView {
    private val fastScroller = FastScroller(this)

    override val viewScrollState: ScrollState
        get() = when (userActionFlow.value) {
            UserAction.Drag -> ScrollState.Dragging
            UserAction.Fling -> ScrollState.Settling
            UserAction.None -> ScrollState.Idle
        }

    init {
        val lifecycle = findLifecycleOwner(context)?.lifecycle
        lifecycle?.coroutineScope?.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                collectUserAction(
                    onActionStart = {
                        if (canScrollVertically(1)) {
                            fastScroller.show()
                        }
                    },
                    onActionEnd = {
                        fastScroller.hide()
                    }
                )
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        fastScroller.updateBounds()
        if (!canScrollVertically(1)) {
            fastScroller.hideScrollerImmediately()
        } else {
            fastScroller.hide()
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.translate(0f, scrollY.toFloat())
        fastScroller.draw(canvas)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return if (fastScroller.onInterceptTouchEvent(ev)) {
            true
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        return if (fastScroller.onTouchEvent(e)) {
            true
        } else {
            super.onTouchEvent(e)
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        fastScroller.updateBounds()
    }

    override fun nestedScrollBy(x: Int, y: Int) {
        scrollBy(x, y)
    }

    override fun setScrollState(state: ScrollState) {
        val userAction = when (state) {
            ScrollState.Idle -> UserAction.None
            ScrollState.Dragging -> UserAction.Drag
            ScrollState.Settling -> UserAction.Fling
        }
        setUserAction(userAction)
    }

    private fun findLifecycleOwner(ctx: Context): LifecycleOwner? {
        var cur: Context? = ctx
        while (cur is ContextWrapper) {
            if (cur is LifecycleOwner) return cur
            cur = cur.baseContext
        }
        return cur as? LifecycleOwner
    }
}
