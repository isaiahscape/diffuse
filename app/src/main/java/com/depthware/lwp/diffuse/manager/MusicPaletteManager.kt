package com.depthware.lwp.diffuse.manager

import android.graphics.Bitmap
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.model.MusicPalette
import com.depthware.lwp.diffuse.model.MusicPaletteEmbed
import com.depthware.lwp.diffuse.model.MusicPaletteFile
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R
import java.io.File
import java.io.FileOutputStream
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

object MusicPaletteManager {

    const val DEFAULT_PALETTE_ID = "monochrome"

    val savedPalettes: MutableList<MusicPalette> = ArrayList()

    val gradientPalettes: List<MusicPalette> by lazy {
        val ctx = DiffuseApplication.appContext()
        listOf(
            MusicPaletteEmbed("monochrome", ctx.getString(R.string.text_monochrome), R.mipmap.album_monochrome),
            MusicPaletteEmbed("rainbow", ctx.getString(R.string.text_rainbow), R.mipmap.album_rainbow),
            MusicPaletteEmbed("fire", ctx.getString(R.string.text_fire), R.mipmap.album_fire),
            MusicPaletteEmbed("beach", ctx.getString(R.string.text_beach), R.mipmap.album_beach),
            MusicPaletteEmbed("lime", ctx.getString(R.string.text_lime), R.mipmap.album_lime),
            MusicPaletteEmbed("twilight", ctx.getString(R.string.text_twilight), R.mipmap.album_twilight),
            MusicPaletteEmbed("blue_marble", ctx.getString(R.string.text_blue_marble), R.mipmap.album_blue_marble),
            MusicPaletteEmbed("nightclub", ctx.getString(R.string.text_night_club), R.mipmap.album_nightclub),
            MusicPaletteEmbed("flamingo_on_water", ctx.getString(R.string.text_flamingo_on_water), R.mipmap.album_flamingo_on_water),
            MusicPaletteEmbed("aurora", ctx.getString(R.string.text_aurora), R.mipmap.album_aurora)
        )
    }

    val albumPalettes: List<MusicPalette> by lazy {
        listOf(
            MusicPaletteEmbed("midnight_pretenders", "Midnight Pretenders", R.mipmap.album_midnight_pretenders),
            MusicPaletteEmbed("abbey_road", "Abbey Road", R.mipmap.album_abbey_road),
            MusicPaletteEmbed("color_of_autumn", "Color Of Autumn", R.mipmap.album_color_of_autumn),
            MusicPaletteEmbed("7_days", "7 Days", R.mipmap.album_7_days),
            MusicPaletteEmbed("sapphire", "Sapphire", R.mipmap.album_sapphire),
            MusicPaletteEmbed("caught_in_the_act", "Caught in the Act", R.mipmap.album_caught_in_the_act),
            MusicPaletteEmbed("modal_soul", "Modal Soul", R.mipmap.album_modal_soul),
            MusicPaletteEmbed("blue_neighbourhood", "Blue Neighbourhood", R.mipmap.album_blue_neighbourhood),
            MusicPaletteEmbed("dirty_bit", "Dirty Bit", R.mipmap.album_dirty_bit),
            MusicPaletteEmbed("my_jinji", "My Jinji", R.mipmap.album_my_jinji),
            MusicPaletteEmbed("summer_breeze", "Summer Breeze", R.mipmap.album_summer_breeze),
            MusicPaletteEmbed("little_bit", "Little Bit", R.mipmap.album_little_bit),
            MusicPaletteEmbed("broken_clocks", "Broken Clocks", R.mipmap.album_broken_clocks)
        )
    }

    init {
        refreshSavedPalettes()
    }

    fun getAllPalettes(refresh: Boolean = false): List<MusicPalette> {
        if (refresh) {
            refreshSavedPalettes()
        }
        return buildList {
            addAll(gradientPalettes)
            addAll(albumPalettes)
            addAll(savedPalettes)
        }
    }

    fun getSavedPalettesDir(): File {
        val dir = File(DiffuseApplication.appContext().filesDir, "saved_palettes")
        if (!dir.exists()) {
            try {
                dir.mkdirs()
            } catch (e: Exception) {
                DiffuseUtils.logError(e)
            }
        }
        return dir
    }

    fun refreshSavedPalettes() {
        val files = getSavedPalettesDir().listFiles() ?: return
        val loaded = files
            .sortedByDescending { it.name }
            .map { file ->
                val name = file.name
                val title = try {
                    DateFormat.getDateTimeInstance().format(Date(name.toLong()))
                } catch (e: Exception) {
                    DiffuseUtils.logError(e)
                    name
                }
                MusicPaletteFile(name, title, file)
            }
        savedPalettes.clear()
        savedPalettes.addAll(loaded)
    }

    fun savePalette(bitmap: Bitmap): String {
        val id = System.currentTimeMillis().toString()
        savePalette(bitmap, id)
        return id
    }

    fun savePalette(bitmap: Bitmap, id: String) {
        val size = MusicPalette.PALETTE_SIZE
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, false)
        val encodedName = try {
            URLEncoder.encode(id, "utf-8")
        } catch (e: UnsupportedEncodingException) {
            e.printStackTrace()
            DiffuseUtils.logError(e)
            id
        }
        val file = File(getSavedPalettesDir(), encodedName)
        try {
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        refreshSavedPalettes()
    }

    fun deletePalette(palette: MusicPaletteFile) {
        val vm = ViewModelManager.viewModel
        val currentId = DiffuseUtils.getValueOrDefault(vm.defaultAlbumArtID, DEFAULT_PALETTE_ID)
        if (currentId == palette.id) {
            DiffuseUtils.setLiveDataValue(vm.defaultAlbumArtID, DEFAULT_PALETTE_ID)
        }
        palette.file?.delete()
        refreshSavedPalettes()
    }
}
