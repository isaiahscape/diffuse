package com.depthware.lwp.diffuse.recorder

import java.nio.FloatBuffer

open class Drawable2d(val prefab: Prefab) {

    enum class Prefab {
        TRIANGLE,
        RECTANGLE,
        FULL_RECTANGLE
    }

    companion object {
        private val TRIANGLE_COORDS = floatArrayOf(
            0.0f, 0.57735026f,
            -0.5f, -0.28867513f,
            0.5f, -0.28867513f
        )
        private val TRIANGLE_TEX_COORDS = floatArrayOf(
            0.5f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
        private val TRIANGLE_BUF = GlUtil.createFloatBuffer(TRIANGLE_COORDS)
        private val TRIANGLE_TEX_BUF = GlUtil.createFloatBuffer(TRIANGLE_TEX_COORDS)

        private val RECTANGLE_COORDS = floatArrayOf(
            -0.5f, -0.5f,
            0.5f, -0.5f,
            -0.5f, 0.5f,
            0.5f, 0.5f
        )
        private val RECTANGLE_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )
        private val RECTANGLE_BUF = GlUtil.createFloatBuffer(RECTANGLE_COORDS)
        private val RECTANGLE_TEX_BUF = GlUtil.createFloatBuffer(RECTANGLE_TEX_COORDS)

        private val FULL_RECTANGLE_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
        )
        private val FULL_RECTANGLE_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
        private val FULL_RECTANGLE_BUF = GlUtil.createFloatBuffer(FULL_RECTANGLE_COORDS)
        private val FULL_RECTANGLE_TEX_BUF = GlUtil.createFloatBuffer(FULL_RECTANGLE_TEX_COORDS)
    }

    val vertexArray: FloatBuffer
    private val baseTexCoordArray: FloatBuffer
    val vertexCount: Int
    val coordsPerVertex: Int = 2
    val vertexStride: Int = coordsPerVertex * 4
    val texCoordStride: Int = 8

    init {
        when (prefab) {
            Prefab.TRIANGLE -> {
                vertexArray = TRIANGLE_BUF
                baseTexCoordArray = TRIANGLE_TEX_BUF
                vertexCount = TRIANGLE_COORDS.size / 2
            }
            Prefab.RECTANGLE -> {
                vertexArray = RECTANGLE_BUF
                baseTexCoordArray = RECTANGLE_TEX_BUF
                vertexCount = RECTANGLE_COORDS.size / 2
            }
            Prefab.FULL_RECTANGLE -> {
                vertexArray = FULL_RECTANGLE_BUF
                baseTexCoordArray = FULL_RECTANGLE_TEX_BUF
                vertexCount = FULL_RECTANGLE_COORDS.size / 2
            }
        }
    }

    open fun getTexCoordArray(): FloatBuffer = baseTexCoordArray

    override fun toString(): String = "[Drawable2d: $prefab]"
}
