package com.depthware.lwp.diffuse.activity

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.depthware.lwp.diffuse.manager.ExternalNavigator
import com.depthware.lwp.diffuse.util.WindowInsetsHelper
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutFeedbackActivityBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior

class FeedbackActivity : AppCompatActivity() {

    private lateinit var binding: LayoutFeedbackActivityBinding
    private lateinit var sheetBehavior: BottomSheetBehavior<*>

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.animation_fade_in, R.anim.animation_fade_out)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(R.anim.animation_fade_in, R.anim.animation_fade_out)
        super.onCreate(savedInstanceState)
        binding = LayoutFeedbackActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.feedbackActivityRoot,
            left = true,
            top = true,
            right = true,
            bottom = false
        )
        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.feedbackSheetCard,
            left = false,
            top = false,
            right = false,
            bottom = true
        )

        binding.feedbackActivityRoot.setOnClickListener { finish() }
        binding.btnVisitHomepage.setOnClickListener { ExternalNavigator.openAuthorHomepage() }
        binding.btnWriteEmail.setOnClickListener { ExternalNavigator.sendSupportEmail() }
        binding.btnLeaveRating.setOnClickListener { ExternalNavigator.openPlayStoreListing() }

        binding.root.requestApplyInsets()
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or 1792

        sheetBehavior = BottomSheetBehavior.from(binding.feedbackBottomSheetLayout).apply {
            isDraggable = true
            isHideable = true
            setPeekHeight(BottomSheetBehavior.PEEK_HEIGHT_AUTO, true)
            state = BottomSheetBehavior.STATE_HIDDEN
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                        finish()
                    }
                }
            })
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }
}
