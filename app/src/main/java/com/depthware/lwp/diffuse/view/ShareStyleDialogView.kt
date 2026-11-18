package com.depthware.lwp.diffuse.view

import android.content.Context
import android.view.LayoutInflater
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.depthware.lwp.diffuse.v2.databinding.LayoutShareStyleDialogContentViewBinding

class ShareStyleDialogView(
    context: Context,
    private val listener: Listener
) : CoordinatorLayout(context) {

    interface Listener {
        fun onStyleSelected(includeExtraInfo: Boolean)
    }

    private val binding = LayoutShareStyleDialogContentViewBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    init {
        binding.btnShareBackgroundOnly.setOnClickListener {
            listener.onStyleSelected(false)
        }
        binding.btnShareExtraInfo.setOnClickListener {
            listener.onStyleSelected(true)
        }
    }
}
