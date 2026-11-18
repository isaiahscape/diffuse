package com.depthware.lwp.diffuse.share

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.depthware.lwp.diffuse.activity.SaveImageActivity
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class AlbumShareTask(activity: Activity) {

    data class Params(
        val screenshotPath: String,
        val albumArt: Bitmap,
        val title: String,
        val subtitle: String,
        val includeInfoCard: Boolean
    )

    private val activityRef = WeakReference(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    fun execute(params: Params) {
        executor.execute {
            val outputPath = renderShareImage(params)
            mainHandler.post {
                onComplete(outputPath)
            }
        }
    }

    private fun renderShareImage(params: Params): String? {
        val ctx = DiffuseApplication.appContext()
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(params.screenshotPath, options) ?: run {
            DiffuseUtils.logRemoteError("Failed to decode screenshot at ${params.screenshotPath}")
            return null
        }
        val config = decoded.config ?: run {
            DiffuseUtils.logRemoteError("bitmapConfig null, now change to Bitmap.Config.ARGB_8888")
            Bitmap.Config.ARGB_8888
        }
        val canvasBitmap = decoded.copy(config, true)
        if (canvasBitmap == null) {
            DiffuseUtils.logRemoteError("bitmap null")
            return null
        }

        val canvas = Canvas(canvasBitmap)
        if (params.includeInfoCard) {
            val width = canvasBitmap.width
            val height = canvasBitmap.height
            val centerX = width / 2
            val centerY = height / 2
            val unit = Math.min(width, height) / 60
            val cardInnerSize = (Math.min(width, height) * 0.6f).toInt()
            val padding = ((unit * 1.2).toInt()) * 4
            val cardWidth = cardInnerSize + padding
            val spacing = unit * 2

            val boldTypeface = ResourcesCompat.getFont(ctx, R.font.product_sans_bold)
            val regularTypeface = ResourcesCompat.getFont(ctx, R.font.product_sans_regular)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val titlePaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.BLACK
                typeface = boldTypeface
                textSize = (unit * 3).toFloat()
            }
            val subtitlePaint = TextPaint().apply {
                isAntiAlias = true
                color = Color.rgb(50, 50, 50)
                typeface = regularTypeface
                textSize = spacing.toFloat()
            }

            val titleLayout = StaticLayout.Builder.obtain(
                params.title, 0, params.title.length, titlePaint, cardInnerSize
            ).setMaxLines(3).setIncludePad(false).build()

            val subtitleLayout = StaticLayout.Builder.obtain(
                params.subtitle, 0, params.subtitle.length, subtitlePaint, cardInnerSize
            ).setMaxLines(2).setIncludePad(false).build()

            val art = params.albumArt
            val roundedArt = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
            val artCanvas = Canvas(roundedArt)
            paint.color = Color.BLACK
            artCanvas.drawRoundRect(
                0f, 0f,
                roundedArt.width.toFloat(), roundedArt.height.toFloat(),
                roundedArt.width * 0.06f, roundedArt.height * 0.06f,
                paint
            )
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            artCanvas.drawBitmap(art, 0f, 0f, paint)
            paint.xfermode = null

            val logo = BitmapFactory.decodeResource(ctx.resources, R.mipmap.share_diffuse_logo)
            val logoHeight = spacing.toFloat()
            val logoWidth = (logoHeight / logo.height) * logo.width

            val contentHeight = cardInnerSize + spacing + titleLayout.height + subtitleLayout.height + unit + logoHeight.toInt()

            paint.color = Color.WHITE
            val unitF = unit.toFloat()
            paint.setShadowLayer(unitF, 0f, 0f, Color.argb(40, 0, 0, 0))
            val halfCardW = cardWidth / 2
            val halfCardH = (contentHeight + padding) / 2
            val cardRadius = cardWidth * 0.06f
            canvas.drawRoundRect(
                RectF(
                    (centerX - halfCardW).toFloat(),
                    (centerY - halfCardH).toFloat(),
                    (centerX + halfCardW).toFloat(),
                    (centerY + halfCardH).toFloat()
                ),
                cardRadius, cardRadius, paint
            )

            paint.setShadowLayer(unitF * 2.0f, 0f, 0f, Color.argb(80, 0, 0, 0))
            val halfInner = cardInnerSize / 2
            val artLeft = (centerX - halfInner).toFloat()
            val artTopInt = centerY - (contentHeight / 2)
            val artTop = artTopInt.toFloat()
            val artRight = (centerX + halfInner).toFloat()
            val artBottomInt = artTopInt + cardInnerSize
            val artBottom = artBottomInt.toFloat()
            val artRadius = cardInnerSize * 0.06f
            canvas.drawRoundRect(RectF(artLeft, artTop, artRight, artBottom), artRadius, artRadius, paint)

            paint.setShadowLayer(0f, 0f, 0f, Color.argb(0, 0, 0, 0))
            canvas.drawBitmap(
                roundedArt,
                Rect(0, 0, roundedArt.width, roundedArt.height),
                RectF(artLeft, artTop, artRight, artBottom),
                paint
            )

            val textTop = artBottomInt + spacing
            canvas.save()
            canvas.translate(artLeft, textTop.toFloat())
            titleLayout.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.translate(artLeft, (textTop + titleLayout.height).toFloat())
            subtitleLayout.draw(canvas)
            canvas.restore()

            val logoTop = (textTop + titleLayout.height + subtitleLayout.height + unit).toFloat()
            canvas.drawBitmap(
                logo,
                Rect(0, 0, logo.width, logo.height),
                RectF(artLeft, logoTop, artLeft + logoWidth, logoTop + logoHeight),
                paint
            )
        }

        val outFile = File(ctx.cacheDir, ctx.getString(R.string.local_album_share_file_name))
        try {
            FileOutputStream(outFile, false).use { out ->
                canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                out.flush()
            }
        } catch (e: Exception) {
            DiffuseUtils.logError(e)
        }
        return if (outFile.exists()) outFile.absolutePath else null
    }

    private fun onComplete(path: String?) {
        if (path == null) {
            DiffuseUtils.logRemoteError("onPostExecute path null")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            DiffuseUtils.logRemoteError("file not exist at $path")
            return
        }
        val activity = activityRef.get() ?: run {
            DiffuseUtils.logRemoteError("activityWeakReference null")
            return
        }
        val ctx = DiffuseApplication.appContext()
        val uri = FileProvider.getUriForFile(ctx, ctx.getString(R.string.file_provider_authority), file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.label_share_diffuse_text))
            putExtra(Intent.EXTRA_STREAM, uri)
            setDataAndType(uri, "image/jpg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, activity.getString(R.string.label_share_from_diffuse))
        val saveIntent = Intent(ctx, SaveImageActivity::class.java).apply {
            putExtra("path", path)
        }
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(saveIntent))
        activity.startActivity(chooser)
    }
}
