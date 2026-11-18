package com.depthware.lwp.diffuse.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R

class TransparentHoleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var scratchBitmap: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private var cutView: View? = null
    private var cutRect: Rect = DiffuseUtils.convertRectRelativeTo(
        Rect(0, 0, width, height),
        this,
        this
    )

    fun getCutViewRect(): Rect {
        val target = cutView
        if (target != null) {
            cutRect = DiffuseUtils.convertRectRelativeTo(
                Rect(0, 0, target.width, target.height),
                target,
                this
            )
            DiffuseUtils.logDebug("convertRect $cutRect")
        }
        return cutRect
    }

    override fun onDraw(canvas: Canvas) {
        val c = scratchCanvas ?: return
        val bmp = scratchBitmap ?: return
        c.drawColor(context.getColor(R.color.semiTransparent))
        c.drawRect(cutRect, clearPaint)
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scratchCanvas?.setBitmap(null)
        scratchCanvas = null
        scratchBitmap?.recycle()
        scratchBitmap = null

        if (w > 0 && h > 0) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            scratchBitmap = bmp
            scratchCanvas = Canvas(bmp)
            cutView?.let { target ->
                cutRect = DiffuseUtils.convertRectRelativeTo(
                    Rect(0, 0, target.width, target.height),
                    target,
                    this
                )
            }
        }
    }

    fun setCutView(view: View) {
        cutView = view
        requestLayout()
        invalidate()
    }
}
