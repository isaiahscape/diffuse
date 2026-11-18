package com.depthware.lwp.diffuse.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.util.DiffuseUtils
import java.io.File
import java.util.Objects

class MusicPaletteFile(
    id: String,
    title: String,
    val file: File?
) : MusicPalette(id, title) {

    override fun isValid(): Boolean = try {
        file != null && file.exists()
    } catch (e: Exception) {
        DiffuseUtils.logError(e)
        false
    }

    override fun loadBitmap(): Bitmap? = try {
        if (file != null && file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options())
        } else {
            null
        }
    } catch (e: Exception) {
        DiffuseUtils.logError(e)
        null
    }

    override fun loadDrawable(): Drawable? {
        val bitmap = loadBitmap() ?: return null
        return BitmapDrawable(DiffuseApplication.appContext().resources, bitmap)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        if (!super.equals(other)) return false
        other as MusicPaletteFile
        return file == other.file
    }

    override fun hashCode(): Int = Objects.hash(super.hashCode(), file)
}
