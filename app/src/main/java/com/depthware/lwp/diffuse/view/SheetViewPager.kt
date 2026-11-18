package com.depthware.lwp.diffuse.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.viewpager.widget.ViewPager
import com.depthware.lwp.diffuse.util.DiffuseUtils
import java.lang.reflect.Field

class SheetViewPager(context: Context, attrs: AttributeSet?) : ViewPager(context, attrs) {

    private var positionField: Field? = null

    init {
        initHelper()
    }

    private fun initHelper() {
        try {
            positionField = LayoutParams::class.java.getDeclaredField("position").apply {
                isAccessible = true
            }
        } catch (e: NoSuchFieldException) {
            e.printStackTrace()
            DiffuseUtils.logError(e)
        }
        addOnPageChangeListener(object : OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) = Unit
            override fun onPageSelected(position: Int) = Unit
            override fun onPageScrollStateChanged(state: Int) {
                requestLayout()
            }
        })
    }

    override fun getChildAt(index: Int): View? {
        val stackTrace = Throwable().stackTrace
        if (stackTrace.size <= 1 ||
            stackTrace[1].className != "com.google.android.material.bottomsheet.BottomSheetBehavior" ||
            stackTrace[1].methodName != "findScrollingChild"
        ) {
            return super.getChildAt(index)
        }
        val currentView = getCurrentView() ?: super.getChildAt(index)
        if (index == 0) return currentView
        val child = super.getChildAt(index)
        return if (child == currentView) super.getChildAt(0) else child
    }

    fun getCurrentView(): View? {
        val field = positionField ?: return null
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            try {
                val pos = field.getInt(lp)
                if (!lp.isDecor && currentItem == pos) {
                    return child
                }
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }
        }
        return null
    }
}
