package com.depthware.lwp.diffuse.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.model.AppInfo
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.util.WindowInsetsHelper
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutLaunchMusicAppItemBinding
import com.depthware.lwp.diffuse.v2.databinding.LayoutLaunchMusicAppsActivityBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior

class LaunchMusicAppsActivity : AppCompatActivity() {

    private lateinit var binding: LayoutLaunchMusicAppsActivityBinding
    private lateinit var sheetBehavior: BottomSheetBehavior<*>

    private val compatiblePackages = listOf(
        "com.tencent.qqmusic",
        "com.google.android.apps.podcasts",
        "qijaz221.github.io.musicplayer.premium",
        "qijaz221.github.io.musicplayer",
        "com.soundcloud.android",
        "com.amazon.mp3",
        "com.aspiro.tidal",
        "com.apple.android.music",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.sirius",
        "com.pandora.android",
        "com.foobar2000.foobar2000",
        "com.spotify.zerotap",
        "deezer.android.app",
        "com.musicplayer.blackplayerfree",
        "com.kodarkooperativet.blackplayerex",
        "in.krosbits.musicolet",
        "com.jio.media.jiobeats",
        "com.gaana",
        "com.cidercollective.cider",
        "com.mossapps.flick"
    )

    private val excludedPackages = setOf("com.sec.android.app.music")

    private inner class AppAdapter : ListAdapter<AppInfo, AppAdapter.Holder>(
        object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem.packageName == newItem.packageName
        }
    ) {
        inner class Holder(val itemBinding: LayoutLaunchMusicAppItemBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = LayoutLaunchMusicAppItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            holder.itemBinding.imgAppIcon.setImageDrawable(item.icon)
            holder.itemBinding.txtAppName.text = item.label
            holder.itemBinding.txtAppPackage.text = item.packageName
            holder.itemBinding.root.setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(item.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    finish()
                    startActivity(launch)
                }
            }
            holder.itemBinding.root.setOnLongClickListener {
                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    data = Uri.parse("package:${item.packageName}")
                }
                finish()
                startActivity(settingsIntent)
                true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryApps(intent: Intent): List<AppInfo> {
        val pm = DiffuseApplication.appContext().packageManager
        val resolved = pm.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        ).map { it.activityInfo.packageName }.toMutableList()
        resolved.addAll(compatiblePackages)
        resolved.removeAll(excludedPackages)

        return resolved.distinct()
            .mapNotNull { pkg -> DiffuseUtils.getApplicationInfo(pkg, pm) }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = appInfo.loadUnbadgedIcon(pm)
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun loadMusicApps(): List<AppInfo> {
        val primary = queryApps(Intent("android.intent.action.MUSIC_PLAYER"))
        if (primary.isNotEmpty()) return primary
        return queryApps(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
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
        binding = LayoutLaunchMusicAppsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.launchMusicAppsActivityRoot,
            left = true,
            top = true,
            right = true,
            bottom = false
        )
        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.launchMusicSheetCard,
            left = false,
            top = false,
            right = false,
            bottom = true
        )
        binding.launchMusicAppsActivityRoot.setOnClickListener { finish() }

        binding.root.requestApplyInsets()
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or 1792

        sheetBehavior = BottomSheetBehavior.from(binding.launchMusicBottomSheetLayout).apply {
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

        val adapter = AppAdapter()
        binding.launchMusicBottomSheetListView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.launchMusicBottomSheetListView.adapter = adapter
        adapter.submitList(loadMusicApps())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && sheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }
}
