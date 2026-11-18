package com.depthware.lwp.diffuse.recorder

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

class Texture2dProgram(val programType: ProgramType) {

    enum class ProgramType {
        TEXTURE_2D,
        TEXTURE_EXT,
        TEXTURE_EXT_BW,
        TEXTURE_EXT_FILT
    }

    companion object {
        private const val TAG = "Grafika"
        private const val KERNEL_SIZE = 9

        private const val VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "    gl_Position = uMVPMatrix * aPosition;\n" +
                "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
                "}\n"

        private const val FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform sampler2D sTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n"

        private const val FRAGMENT_SHADER_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n"

        private const val FRAGMENT_SHADER_EXT_BW =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "    vec4 tc = texture2D(sTexture, vTextureCoord);\n" +
                "    float color = tc.r * 0.3 + tc.g * 0.59 + tc.b * 0.11;\n" +
                "    gl_FragColor = vec4(color, color, color, 1.0);\n" +
                "}\n"

        private const val FRAGMENT_SHADER_EXT_FILT =
            "#extension GL_OES_EGL_image_external : require\n" +
                "#define KERNEL_SIZE $KERNEL_SIZE\n" +
                "precision highp float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "uniform float uKernel[KERNEL_SIZE];\n" +
                "uniform vec2 uTexOffset[KERNEL_SIZE];\n" +
                "uniform float uColorAdjust;\n" +
                "void main() {\n" +
                "    int i = 0;\n" +
                "    vec4 sum = vec4(0.0);\n" +
                "    if (vTextureCoord.x < vTextureCoord.y - 0.005) {\n" +
                "        for (i = 0; i < KERNEL_SIZE; i++) {\n" +
                "            vec4 texc = texture2D(sTexture, vTextureCoord + uTexOffset[i]);\n" +
                "            sum += texc * uKernel[i];\n" +
                "        }\n" +
                "        sum += uColorAdjust;\n" +
                "    } else if (vTextureCoord.x > vTextureCoord.y + 0.005) {\n" +
                "        sum = texture2D(sTexture, vTextureCoord);\n" +
                "    } else {\n" +
                "        sum.r = 1.0;\n" +
                "    }\n" +
                "    gl_FragColor = sum;\n" +
                "}\n"
    }

    private var programHandle: Int = 0
    private val uMVPMatrixLoc: Int
    private val uTexMatrixLoc: Int
    private var uKernelLoc: Int = -1
    private var uTexOffsetLoc: Int = -1
    private var uColorAdjustLoc: Int = -1
    private val aPositionLoc: Int
    private val aTextureCoordLoc: Int
    private val textureTarget: Int

    private val kernel = FloatArray(KERNEL_SIZE)
    private var texOffset: FloatArray? = null
    private var colorAdjust = 0.0f

    init {
        when (programType) {
            ProgramType.TEXTURE_2D -> {
                textureTarget = GLES20.GL_TEXTURE_2D
                programHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D)
            }
            ProgramType.TEXTURE_EXT -> {
                textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                programHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT)
            }
            ProgramType.TEXTURE_EXT_BW -> {
                textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                programHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_BW)
            }
            ProgramType.TEXTURE_EXT_FILT -> {
                textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                programHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_FILT)
            }
        }
        if (programHandle == 0) {
            throw RuntimeException("Unable to create program")
        }
        Log.d(TAG, "Created program $programHandle ($programType)")

        aPositionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition").also {
            GlUtil.checkLocation(it, "aPosition")
        }
        aTextureCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord").also {
            GlUtil.checkLocation(it, "aTextureCoord")
        }
        uMVPMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uMVPMatrix").also {
            GlUtil.checkLocation(it, "uMVPMatrix")
        }
        uTexMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTexMatrix").also {
            GlUtil.checkLocation(it, "uTexMatrix")
        }

        uKernelLoc = GLES20.glGetUniformLocation(programHandle, "uKernel")
        if (uKernelLoc >= 0) {
            uTexOffsetLoc = GLES20.glGetUniformLocation(programHandle, "uTexOffset").also {
                GlUtil.checkLocation(it, "uTexOffset")
            }
            uColorAdjustLoc = GLES20.glGetUniformLocation(programHandle, "uColorAdjust").also {
                GlUtil.checkLocation(it, "uColorAdjust")
            }
            setKernel(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f), 0.0f)
            setTexSize(256, 256)
        }
    }

    fun createTextureObject(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GlUtil.checkGlError("glGenTextures")
        val texId = textures[0]
        GLES20.glBindTexture(textureTarget, texId)
        GlUtil.checkGlError("glBindTexture $texId")
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GlUtil.checkGlError("glTexParameter")
        return texId
    }

    fun draw(
        mvpMatrix: FloatArray,
        vertexBuffer: FloatBuffer,
        firstVertex: Int,
        vertexCount: Int,
        coordsPerVertex: Int,
        vertexStride: Int,
        texMatrix: FloatArray,
        texBuffer: FloatBuffer,
        textureId: Int,
        texStride: Int
    ) {
        GlUtil.checkGlError("draw start")
        GLES20.glUseProgram(programHandle)
        GlUtil.checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GlUtil.checkGlError("glUniformMatrix4fv")
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GlUtil.checkGlError("glUniformMatrix4fv")

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GlUtil.checkGlError("glEnableVertexAttribArray")
        GLES20.glVertexAttribPointer(
            aPositionLoc, coordsPerVertex, GLES20.GL_FLOAT, false, vertexStride, vertexBuffer
        )
        GlUtil.checkGlError("glVertexAttribPointer")

        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GlUtil.checkGlError("glEnableVertexAttribArray")
        GLES20.glVertexAttribPointer(
            aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, texStride, texBuffer
        )
        GlUtil.checkGlError("glVertexAttribPointer")

        if (uKernelLoc >= 0) {
            GLES20.glUniform1fv(uKernelLoc, KERNEL_SIZE, kernel, 0)
            GLES20.glUniform2fv(uTexOffsetLoc, KERNEL_SIZE, texOffset, 0)
            GLES20.glUniform1f(uColorAdjustLoc, colorAdjust)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount)
        GlUtil.checkGlError("glDrawArrays")

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        GLES20.glBindTexture(textureTarget, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        Log.d(TAG, "deleting program $programHandle")
        GLES20.glDeleteProgram(programHandle)
        programHandle = -1
    }

    fun setKernel(values: FloatArray, colorAdj: Float) {
        require(values.size == KERNEL_SIZE) { "Kernel size is ${values.size} vs. $KERNEL_SIZE" }
        System.arraycopy(values, 0, kernel, 0, KERNEL_SIZE)
        colorAdjust = colorAdj
    }

    fun setTexSize(width: Int, height: Int) {
        val rw = 1.0f / width
        val rh = 1.0f / height
        texOffset = floatArrayOf(
            -rw, -rh, 0f, -rh, rw, -rh,
            -rw, 0f, 0f, 0f, rw, 0f,
            -rw, rh, 0f, rh, rw, rh
        )
    }
}
