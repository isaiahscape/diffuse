package com.depthware.lwp.diffuse.activity

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.depthware.lwp.diffuse.fragment.PaletteAlbumFragment
import com.depthware.lwp.diffuse.fragment.PaletteGradientFragment
import com.depthware.lwp.diffuse.fragment.PaletteSavedFragment
import com.depthware.lwp.diffuse.util.WindowInsetsHelper
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutPaletteSelectDefaultActivityBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior

class SelectDefaultPaletteActivity : AppCompatActivity() {

    private lateinit var binding: LayoutPaletteSelectDefaultActivityBinding
    private lateinit var sheetBehavior: BottomSheetBehavior<*>

    @Suppress("DEPRECATION")
    private inner class PalettePagerAdapter(fm: FragmentManager) :
        FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getCount(): Int = 3

        override fun getPageTitle(position: Int): CharSequence = when (position) {
            0 -> getString(R.string.label_palette_gradient)
            1 -> getString(R.string.label_palette_texture_presents)
            2 -> getString(R.string.label_palette_saved)
            else -> ""
        }

        override fun getItem(position: Int): Fragment = when (position) {
            0 -> PaletteGradientFragment()
            1 -> PaletteAlbumFragment()
            2 -> PaletteSavedFragment()
            else -> Fragment()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.animation_fade_in, R.anim.animation_fade_out)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(R.anim.animation_fade_in, R.anim.animation_fade_out)
        super.onCreate(savedInstanceState)
        binding = LayoutPaletteSelectDefaultActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.selectPaletteActivityRoot,
            left = true,
            top = true,
            right = true,
            bottom = false
        )
        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.selectPaletteSheetCard,
            left = false,
            top = false,
            right = false,
            bottom = true
        )
        binding.selectPaletteActivityRoot.setOnClickListener { finish() }

        binding.root.requestApplyInsets()
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or 1792

        sheetBehavior = BottomSheetBehavior.from(binding.selectColorPaletteBottomSheetLayout).apply {
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

        binding.paletteCategoryTabViewPager.adapter = PalettePagerAdapter(supportFragmentManager)
        binding.paletteCategoryTabLayout.setupWithViewPager(binding.paletteCategoryTabViewPager)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }
}
