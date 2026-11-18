package com.depthware.lwp.diffuse.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.util.DiffuseUtils
import java.util.Objects

class MusicPaletteEmbed(
    id: String,
    title: String,
    @DrawableRes val resId: Int
) : MusicPalette(id, title) {

    override fun isValid(): Boolean =
        DiffuseUtils.resourceExists(DiffuseApplication.appContext(), resId)

    override fun loadBitmap(): Bitmap? =
        BitmapFactory.decodeResource(DiffuseApplication.appContext().resources, resId)

    override fun loadDrawable(): Drawable? =
        ContextCompat.getDrawable(DiffuseApplication.appContext(), resId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        if (!super.equals(other)) return false
        other as MusicPaletteEmbed
        return resId == other.resId
    }

    override fun hashCode(): Int = Objects.hash(super.hashCode(), resId)
}
