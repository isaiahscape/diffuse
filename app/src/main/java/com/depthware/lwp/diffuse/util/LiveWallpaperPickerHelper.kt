package com.depthware.lwp.diffuse.util

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.depthware.lwp.diffuse.manager.ToastManager
import com.depthware.lwp.diffuse.service.LiveWallpaperService
import com.depthware.lwp.diffuse.v2.R

/**
 * Resolves and launches live wallpaper pickers across Google Wallpapers, stock Android / AOSP
 * LivePicker (`com.android.wallpaper.livepicker`), AOSP WallpaperPicker2 (`com.android.wallpaper`),
 * and OEM fallback pickers.
 */
object LiveWallpaperPickerHelper {

    private const val PKG_AOSP_LIVE_PICKER = "com.android.wallpaper.livepicker"
    private const val PKG_AOSP_WALLPAPER = "com.android.wallpaper"
    private const val PKG_GOOGLE_WALLPAPER = "com.google.android.apps.wallpaper"

    /**
     * Returns true if a stock Android / AOSP live wallpaper picker or system chooser is resolvable.
     */
    fun hasStockAospPicker(context: Context): Boolean =
        buildStockAospCandidates(context).any { canResolveLaunchable(context.packageManager, it) }

    /**
     * Launches the best available live wallpaper preview or picker, falling back from direct
     * preview intents to stock Android / AOSP pickers and the generic wallpaper chooser.
     */
    fun openLiveWallpaperPicker(context: Context) {
        val candidates = buildList {
            addAll(buildDirectPreviewCandidates(context))
            addAll(buildStockAospCandidates(context))
            context.packageManager.getLaunchIntentForPackage(PKG_GOOGLE_WALLPAPER)?.let { add(it) }
            addAll(buildSystemChooserCandidates(context))
        }
        if (!tryStartFirst(context, candidates)) {
            ToastManager.show(
                context.getString(R.string.toast_live_wallpaper_picker_unavailable),
                longDuration = true
            )
        }
    }

    /**
     * Launches the stock Android / AOSP live wallpaper picker directly, bypassing broken or
     * OEM-restricted `ACTION_CHANGE_LIVE_WALLPAPER` handlers.
     */
    fun openStockAospPicker(context: Context) {
        val candidates = buildList {
            add(
                componentPreviewIntent(
                    context,
                    PKG_AOSP_LIVE_PICKER,
                    "$PKG_AOSP_LIVE_PICKER.LiveWallpaperChange"
                )
            )
            addAll(buildStockAospCandidates(context))
            addAll(buildSystemChooserCandidates(context))
        }
        if (!tryStartFirst(context, candidates)) {
            ToastManager.show(
                context.getString(R.string.toast_live_wallpaper_picker_unavailable),
                longDuration = true
            )
        }
    }

    private fun buildDirectPreviewCandidates(context: Context): List<Intent> {
        val service = ComponentName(context, LiveWallpaperService::class.java)
        return listOf(
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, service)
            },
            componentPreviewIntent(
                context,
                PKG_AOSP_LIVE_PICKER,
                "$PKG_AOSP_LIVE_PICKER.LiveWallpaperChange"
            ),
            componentPreviewIntent(
                context,
                PKG_AOSP_WALLPAPER,
                "$PKG_AOSP_WALLPAPER.picker.LivePreviewActivity"
            ),
            componentPreviewIntent(
                context,
                PKG_GOOGLE_WALLPAPER,
                "$PKG_AOSP_WALLPAPER.picker.LivePreviewActivity"
            )
        )
    }

    private fun buildStockAospCandidates(context: Context): List<Intent> {
        val pm = context.packageManager
        return buildList {
            add(explicitIntent(PKG_AOSP_LIVE_PICKER, "$PKG_AOSP_LIVE_PICKER.LiveWallpaperActivity"))
            add(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            pm.getLaunchIntentForPackage(PKG_AOSP_LIVE_PICKER)?.let { add(it) }
            add(explicitIntent(PKG_AOSP_WALLPAPER, "$PKG_AOSP_WALLPAPER.picker.LiveWallpaperActivity"))
            add(explicitIntent(PKG_AOSP_WALLPAPER, "$PKG_AOSP_WALLPAPER.picker.CustomizationPickerActivity"))
            add(explicitIntent(PKG_AOSP_WALLPAPER, "$PKG_AOSP_WALLPAPER.picker.CategoryPickerActivity"))
            pm.getLaunchIntentForPackage(PKG_AOSP_WALLPAPER)?.let { add(it) }
        }
    }

    private fun buildSystemChooserCandidates(context: Context): List<Intent> {
        val setWallpaper = Intent(Intent.ACTION_SET_WALLPAPER)
        if (!canResolveLaunchable(context.packageManager, setWallpaper)) {
            return emptyList()
        }
        return listOf(
            Intent.createChooser(setWallpaper, context.getString(R.string.button_set_wallpaper))
        )
    }

    private fun componentPreviewIntent(context: Context, pkg: String, cls: String): Intent =
        explicitIntent(pkg, cls).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, LiveWallpaperService::class.java)
            )
        }

    private fun explicitIntent(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun tryStartFirst(context: Context, candidates: List<Intent>): Boolean {
        val pm = context.packageManager
        for (candidate in candidates) {
            if (!canResolveLaunchable(pm, candidate)) continue
            val launchIntent = Intent(candidate).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(launchIntent)
                return true
            } catch (e: Exception) {
                DiffuseUtils.logError(e, report = false)
            }
        }
        return false
    }

    private fun canResolveLaunchable(pm: PackageManager, intent: Intent): Boolean {
        return try {
            val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
            resolved.activityInfo?.exported == true
        } catch (_: Exception) {
            false
        }
    }
}
