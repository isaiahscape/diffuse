package com.depthware.lwp.diffuse.recorder

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CroppedDrawable2d(prefab: Prefab) : Drawable2d(prefab) {

    private var tweakedTexCoordArray: FloatBuffer? = null
    private var topCrop = 0.0f
    private var bottomCrop = 0.0f
    private var leftCrop = 0.0f
    private var rightCrop = 0.0f
    private var dirty = true

    override fun getTexCoordArray(): FloatBuffer {
        if (dirty) {
            val base = super.getTexCoordArray()
            val cap = base.capacity()
            val out = tweakedTexCoordArray ?: ByteBuffer.allocateDirect(cap * 4).run {
                order(ByteOrder.nativeOrder())
                asFloatBuffer()
            }.also { tweakedTexCoordArray = it }

            for (i in 0 until cap) {
                var value = base.get(i)
                when (i) {
                    0, 4 -> value = leftCrop
                    2, 6 -> value = 1.0f - rightCrop
                    1, 3 -> value = bottomCrop
                    5, 7 -> value = 1.0f - topCrop
                }
                out.put(i, value)
            }
            dirty = false
        }
        return tweakedTexCoordArray!!
    }

    fun setBottomCrop(crop: Float) {
        require(crop in 0.0f..1.0f) { "invalid crop $crop" }
        bottomCrop = crop
        dirty = true
    }

    fun setLeftCrop(crop: Float) {
        require(crop in 0.0f..1.0f) { "invalid crop $crop" }
        leftCrop = crop
        dirty = true
    }

    fun setRightCrop(crop: Float) {
        require(crop in 0.0f..1.0f) { "invalid crop $crop" }
        rightCrop = crop
        dirty = true
    }

    fun setTopCrop(crop: Float) {
        require(crop in 0.0f..1.0f) { "invalid crop $crop" }
        topCrop = crop
        dirty = true
    }
}
