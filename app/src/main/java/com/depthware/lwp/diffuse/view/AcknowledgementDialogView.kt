package com.depthware.lwp.diffuse.view

import android.content.Context
import android.view.LayoutInflater
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.depthware.lwp.diffuse.manager.ToastManager
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutAcknowledgementDialogContentViewBinding

class AcknowledgementDialogView(
    context: Context,
    private val listener: Listener
) : CoordinatorLayout(context) {

    interface Listener {
        fun onHelpTranslateClick()
    }

    private val binding = LayoutAcknowledgementDialogContentViewBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    init {
        binding.txtTranslatorsList.setOnClickListener {
            ToastManager.show(
                getContext().getString(R.string.toast_translators_you_are_awesome),
                longDuration = true
            )
        }
        binding.btnHelpTranslate.setOnClickListener {
            listener.onHelpTranslateClick()
        }
    }
}
