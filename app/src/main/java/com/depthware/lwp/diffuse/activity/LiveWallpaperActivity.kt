package com.depthware.lwp.diffuse.activity

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import com.depthware.lwp.diffuse.manager.ReviewManager
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.util.WindowInsetsHelper
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutWallpaperActivityBinding

class LiveWallpaperActivity : AppCompatActivity() {

    private lateinit var binding: LayoutWallpaperActivityBinding

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutWallpaperActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.wallpaperActivityRoot,
            left = true,
            top = true,
            right = true,
            bottom = false
        )

        var isInLyrics = false
        val updateRootChrome = { active: Boolean?, inLyrics: Boolean ->
            binding.headerContainer.visibility = if (inLyrics) View.GONE else View.VISIBLE
            if (inLyrics) {
                val scrimColor = if (active == true) 0x66000000 else 0xE6101014.toInt()
                binding.wallpaperActivityRoot.setBackgroundColor(scrimColor)
            } else {
                val colorRes = if (active == true) R.color.semiTransparent else R.color.lessSemiTransparent
                binding.wallpaperActivityRoot.setBackgroundColor(ContextCompat.getColor(this, colorRes))
            }
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        navHostFragment?.navController?.addOnDestinationChangedListener { _, destination, _ ->
            isInLyrics = destination.id == R.id.lyricsFragment
            updateRootChrome(ViewModelManager.viewModel.isWallpaperActive.value, isInLyrics)
        }

        ViewModelManager.viewModel.isWallpaperActive.observe(this) { active ->
            updateRootChrome(active, isInLyrics)
        }

        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or 1792
    }

    override fun onResume() {
        super.onResume()
        ReviewManager.maybePromptReview(this)
    }
}
