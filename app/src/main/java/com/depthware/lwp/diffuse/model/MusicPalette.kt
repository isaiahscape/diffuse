package com.depthware.lwp.diffuse.model

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.util.Objects

abstract class MusicPalette(
    val id: String,
    val title: String
) {
    companion object {
        const val PALETTE_SIZE = 192
    }

    abstract fun isValid(): Boolean
    abstract fun loadBitmap(): Bitmap?
    abstract fun loadDrawable(): Drawable?

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as MusicPalette
        return id == other.id && title == other.title
    }

    override fun hashCode(): Int = Objects.hash(id, title)
}
