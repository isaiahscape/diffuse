package com.depthware.lwp.diffuse.view

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.canhub.cropper.CropImageView
import com.depthware.lwp.diffuse.v2.databinding.LayoutCropImageDialogContentViewBinding

class CropImageDialogView(
    context: Context,
    imageUri: Uri
) : CoordinatorLayout(context) {

    private val binding = LayoutCropImageDialogContentViewBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    init {
        binding.cropImageView.apply {
            setImageUriAsync(imageUri)
            setFixedAspectRatio(true)
            setAspectRatio(1, 1)
            isAutoZoomEnabled = true
            isShowProgressBar = true
            setMultiTouchEnabled(true)
            scaleType = CropImageView.ScaleType.CENTER_CROP
        }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    fun getCroppedBitmap(): Bitmap? = binding.cropImageView.getCroppedImage()
}
