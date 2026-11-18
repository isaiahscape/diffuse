package com.depthware.lwp.diffuse.recorder

class FullFrameRect(private var program: Texture2dProgram?) {

    private val rectDrawable = CroppedDrawable2d(Drawable2d.Prefab.FULL_RECTANGLE)

    fun createTextureObject(): Int = program!!.createTextureObject()

    fun drawFrame(textureId: Int, texMatrix: FloatArray) {
        program?.draw(
            GlUtil.IDENTITY_MATRIX,
            rectDrawable.vertexArray,
            0,
            rectDrawable.vertexCount,
            rectDrawable.coordsPerVertex,
            rectDrawable.vertexStride,
            texMatrix,
            rectDrawable.getTexCoordArray(),
            textureId,
            rectDrawable.texCoordStride
        )
    }

    fun release(doEglCleanup: Boolean) {
        program?.let {
            if (doEglCleanup) {
                it.release()
            }
            program = null
        }
    }

    fun setBottomCrop(crop: Float) = rectDrawable.setBottomCrop(crop)
    fun setLeftCrop(crop: Float) = rectDrawable.setLeftCrop(crop)
    fun setRightCrop(crop: Float) = rectDrawable.setRightCrop(crop)
    fun setTopCrop(crop: Float) = rectDrawable.setTopCrop(crop)
}
