package com.depthware.lwp.diffuse.provider

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.graphics.drawable.IconCompat
import androidx.slice.Slice
import androidx.slice.SliceProvider
import androidx.slice.builders.GridRowBuilder
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.SliceAction
import com.depthware.lwp.diffuse.activity.LiveWallpaperActivity
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R

@Suppress("DEPRECATION")
class SettingsSliceProvider : SliceProvider() {

    private fun createOpenAppAction(context: Context): SliceAction {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, LiveWallpaperActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return SliceAction.create(
            pendingIntent,
            IconCompat.createWithResource(context, R.drawable.ic_tonality_24px),
            ListBuilder.ICON_IMAGE,
            "Open App"
        )
    }

    override fun onCreateSliceProvider(): Boolean = true

    override fun onBindSlice(sliceUri: Uri): Slice? {
        val ctx = context ?: return null
        val openAction = createOpenAppAction(ctx)
        val listBuilder = ListBuilder(ctx, sliceUri, ListBuilder.INFINITY)

        if (sliceUri.path != "/settings") {
            listBuilder.addRow(
                ListBuilder.RowBuilder()
                    .setTitle("URI not found.")
                    .setPrimaryAction(openAction)
            )
            return listBuilder.build()
        }

        val vm = ViewModelManager.viewModel
        if (DiffuseUtils.getValueOrDefault(vm.isPlaying, false)) {
            listBuilder.addRow(
                ListBuilder.RowBuilder()
                    .setTitle(ctx.getString(R.string.label_last_playing))
                    .setPrimaryAction(openAction)
            )
            val cellBuilder = GridRowBuilder.CellBuilder()
            val albumBitmap = DiffuseUtils.getValueOrDefault(vm.playingAlbumArt, null)
            if (albumBitmap != null) {
                cellBuilder.addImage(
                    IconCompat.createWithBitmap(albumBitmap),
                    ListBuilder.LARGE_IMAGE
                )
            }
            val pkg = vm.playingPackageName.value
            if (pkg != null) {
                val launchIntent = DiffuseApplication.appContext()
                    .packageManager
                    .getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    val pendingIntent = PendingIntent.getActivity(
                        ctx,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    cellBuilder.setContentIntent(pendingIntent)
                }
            }
            listBuilder.addGridRow(
                GridRowBuilder()
                    .addCell(cellBuilder)
                    .setPrimaryAction(openAction)
            )
            listBuilder.addRow(
                ListBuilder.RowBuilder()
                    .setTitle(DiffuseUtils.getValueOrDefault(vm.playingTitle, "Music Title"))
                    .setSubtitle(DiffuseUtils.getValueOrDefault(vm.playingText, "Music Subtitle"))
            )
        } else {
            listBuilder.addRow(
                ListBuilder.RowBuilder()
                    .setTitle(ctx.getString(R.string.label_no_current_playing))
                    .setSubtitle(ctx.getString(R.string.label_no_current_playing_desc))
                    .setPrimaryAction(openAction)
            )
        }
        return listBuilder.build()
    }

    override fun onMapIntentToUri(intent: Intent): Uri {
        var builder = Uri.Builder().scheme("content")
        val data = intent.data
        if (data?.path != null) {
            builder = builder.path(data.path!!.replace("/", ""))
        }
        context?.let {
            builder = builder.authority(it.packageName)
        }
        return builder.build()
    }
}
