package com.depthware.lwp.diffuse.viewModel

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.manager.MusicPaletteManager
import com.depthware.lwp.diffuse.manager.SliceNotifier
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.BuildConfig
import com.depthware.lwp.diffuse.v2.R
import java.util.Date

class UniversalViewModel : ViewModel() {

    companion object {
        private const val PREFS_NAME = "v0"
        private const val ALBUM_CACHE_FILE = "album.jpeg"
    }

    val engineOnline = MutableLiveData(false)
    val recordedAppVersionCode = MutableLiveData<Int>()
    val recordedAppInstallTimeStamp = MutableLiveData<Long>()
    val appReviewRequested = MutableLiveData<Boolean>()

    val isWallpaperActive = MutableLiveData<Boolean>()
    val isNotificationGranted = MutableLiveData<Boolean>()
    val isAudioPermGranted = MutableLiveData<Boolean>()
    val supportsWallpaperColorApi = MutableLiveData<Boolean>()

    val playingProgress = MutableLiveData<Long>()
    val isPlaying = MutableLiveData<Boolean>()
    val playingText = MutableLiveData<String>()
    val playingPackageName = MutableLiveData<String>()
    val playingSubText = MutableLiveData<String>()
    val playingTitle = MutableLiveData<String>()
    val playingAlbumArt = MutableLiveData<Bitmap>()
    val playingAlbumArtDrawable = MediatorLiveData<Drawable>()
    val playerTexture = MediatorLiveData<Bitmap>()

    val appVersionString = MutableLiveData(BuildConfig.VERSION_NAME)
    val noiseSpeedMin = MutableLiveData<Float>()
    val noiseSpeedMax = MutableLiveData<Float>()
    val brightnessOffset = MutableLiveData<Float>()
    val brightnessOffsetToApply = MediatorLiveData<Float>()
    val saturation = MutableLiveData<Float>()
    val hdrMode = MutableLiveData("auto")
    val hdrIntensity = MutableLiveData<Float>()
    val hdrEnabledToApply = MediatorLiveData<Boolean>()
    val supportsHdrDisplay = MutableLiveData(false)

    val enableLiveBeats = MutableLiveData<Boolean>()
    val liveBeatsStrength = MutableLiveData<Float>()
    val enableDebugMode = MutableLiveData<Boolean>()
    val enablePreserveState = MutableLiveData<Boolean>()
    val preserveStateAfterRestart = MutableLiveData<Boolean>()
    val preserveStateAfterPause = MutableLiveData<Boolean>()
    val defaultAlbumArtID = MutableLiveData<String>()
    val enableWallpaperColorAPI = MutableLiveData<Boolean>()
    val enableGraphicsOverride = MutableLiveData<Boolean>()
    val enableMiscOverride = MutableLiveData<Boolean>()
    val postProcessingEffectMode = MutableLiveData("blur")
    val enableFluidOverride = MutableLiveData<Boolean>()
    val enablePerformanceMode = MutableLiveData<Boolean>()
    val noiseScale = MutableLiveData<Float>()

    private val needsSaveAlbumArt = MediatorLiveData<Date>()
    private val needsSave = MediatorLiveData<Date>()

    init {
        setupPlayingAlbumArtDrawable()
        setupPlayerTexture()
        setupBrightnessOffsetToApply()
        setupHdrEnabledToApply()
        setupEngineOnline()
        setupNeedsSaveAlbumArt()
        refresh()
        updateVersionRecord()
        setupNeedsSave()
    }

    private fun prefs(): SharedPreferences =
        DiffuseApplication.appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun setupPlayingAlbumArtDrawable() {
        playingAlbumArtDrawable.addSource(playingAlbumArt) { bitmap ->
            if (bitmap != null) {
                playingAlbumArtDrawable.value =
                    BitmapDrawable(DiffuseApplication.appContext().resources, bitmap)
            }
        }
    }

    private fun setupPlayerTexture() {
        val recompute = {
            var bitmap = DiffuseUtils.getValueOrDefault(playingAlbumArt, null)
            val playing = DiffuseUtils.getValueOrDefault(isPlaying, false)
            val preserveOnPause = DiffuseUtils.getValueOrDefault(preserveStateAfterPause, true)
            if ((!playing && !preserveOnPause) || bitmap == null) {
                val id = DiffuseUtils.getValueOrDefault(
                    defaultAlbumArtID,
                    MusicPaletteManager.DEFAULT_PALETTE_ID
                )
                val palette = MusicPaletteManager.getAllPalettes().firstOrNull { it.id == id }
                bitmap = if (palette != null && palette.isValid()) {
                    palette.loadBitmap()
                } else {
                    BitmapFactory.decodeResource(
                        DiffuseApplication.appContext().resources,
                        R.mipmap.album_monochrome
                    )
                }
            }
            DiffuseUtils.setLiveDataValue(playerTexture, bitmap, post = false)
            DiffuseUtils.logDebug("playingAlbumArt album $bitmap play $playing preserveOnPause $preserveOnPause")
            SliceNotifier.notifyChange()
        }
        playerTexture.addSource(playingAlbumArt) { recompute() }
        playerTexture.addSource(defaultAlbumArtID) { recompute() }
        playerTexture.addSource(isPlaying) { recompute() }
        playerTexture.addSource(preserveStateAfterPause) { recompute() }
    }

    private fun setupBrightnessOffsetToApply() {
        val recompute = {
            val offset = DiffuseUtils.getValueOrDefault(brightnessOffset, 0.0f)
            val colorApiEnabled = DiffuseUtils.getValueOrDefault(enableWallpaperColorAPI, false)
            brightnessOffsetToApply.value = if (colorApiEnabled) 0.0f else offset
        }
        brightnessOffsetToApply.addSource(brightnessOffset) { recompute() }
        brightnessOffsetToApply.addSource(enableWallpaperColorAPI) { recompute() }
    }

    private fun setupHdrEnabledToApply() {
        val recompute = {
            val mode = DiffuseUtils.getValueOrDefault(hdrMode, "auto")
            val displayHdr = DiffuseUtils.getValueOrDefault(supportsHdrDisplay, false)
            hdrEnabledToApply.value = when (mode) {
                "on" -> true
                "off" -> false
                else -> displayHdr
            }
        }
        hdrEnabledToApply.addSource(hdrMode) { recompute() }
        hdrEnabledToApply.addSource(supportsHdrDisplay) { recompute() }
    }

    private fun isDisplayHdrCapable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val dm = DiffuseApplication.appContext().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.isHdr == true
        } catch (_: Exception) {
            false
        }
    }

    private fun setupEngineOnline() {
        engineOnline.observeForever { online ->
            if (online) {
                refresh()
            }
        }
    }

    private fun setupNeedsSaveAlbumArt() {
        needsSaveAlbumArt.addSource(playingAlbumArt) {
            if (DiffuseUtils.getValueOrDefault(preserveStateAfterRestart, false)) {
                needsSaveAlbumArt.value = Date()
            }
        }
        needsSaveAlbumArt.observeForever { saveAlbumArt() }
    }

    private fun setupNeedsSave() {
        val fields: List<LiveData<*>> = listOf(
            enableLiveBeats,
            liveBeatsStrength,
            enableDebugMode,
            enableWallpaperColorAPI,
            enableGraphicsOverride,
            enableFluidOverride,
            enableMiscOverride,
            enablePerformanceMode,
            noiseScale,
            postProcessingEffectMode,
            enablePreserveState,
            preserveStateAfterRestart,
            preserveStateAfterPause,
            defaultAlbumArtID,
            noiseSpeedMin,
            noiseSpeedMax,
            brightnessOffset,
            saturation,
            hdrMode,
            hdrIntensity,
            recordedAppInstallTimeStamp,
            recordedAppVersionCode,
            appReviewRequested
        )
        fields.forEach { field ->
            needsSave.addSource(field) { needsSave.value = Date() }
        }
        needsSave.observeForever { save() }
    }

    fun refresh() {
        DiffuseUtils.logDebug("refresh")
        isWallpaperActive.value = DiffuseUtils.isDiffuseWallpaperActive(DiffuseApplication.appContext())
        isNotificationGranted.value = DiffuseUtils.isNotificationAccessGranted(DiffuseApplication.appContext())
        isAudioPermGranted.value = DiffuseUtils.hasAudioPermissions(DiffuseApplication.appContext())
        supportsWallpaperColorApi.value = Build.VERSION.SDK_INT >= 27
        supportsHdrDisplay.value = isDisplayHdrCapable()
        restore()
        restoreAlbumArt()
    }

    private fun restore() {
        DiffuseUtils.logDebug("restore")
        val p = prefs()
        enableLiveBeats.value = p.getBoolean("enableLiveBeats", false)
        liveBeatsStrength.value = p.getFloat("liveBeatsStrength", 0.5f)
        enableDebugMode.value = p.getBoolean("enableDebugMode", false)
        enableGraphicsOverride.value = p.getBoolean("enableGraphicsOverride", true)
        enableFluidOverride.value = p.getBoolean("enableFluidOverride", true)
        enableMiscOverride.value = p.getBoolean("enableMiscOverride", true)
        enableWallpaperColorAPI.value = p.getBoolean("enableWallpaperColorAPI", false)
        enablePerformanceMode.value = p.getBoolean("enablePerformanceMode", false)
        noiseScale.value = p.getFloat("noiseScale", 0.8f)
        enablePreserveState.value = p.getBoolean("enablePreserveState", true)
        preserveStateAfterRestart.value = p.getBoolean("preserveStateAfterRestart", false)
        preserveStateAfterPause.value = p.getBoolean("preserveStateAfterPause", false)
        defaultAlbumArtID.value = p.getString("defaultAlbumArtID", MusicPaletteManager.DEFAULT_PALETTE_ID)
        postProcessingEffectMode.value = p.getString("postProcessingEffectMode", "blur")
        noiseSpeedMin.value = p.getFloat("noiseSpeedMin", 0.05f)
        noiseSpeedMax.value = p.getFloat("noiseSpeedMax", 0.5f)
        brightnessOffset.value = p.getFloat("brightnessOffset", 0.0f)
        saturation.value = p.getFloat("saturation", 1.0f).coerceIn(0.0f, 2.0f)
        val storedHdrMode = p.getString("hdrMode", "auto") ?: "auto"
        hdrMode.value = if (storedHdrMode == "on" || storedHdrMode == "off" || storedHdrMode == "auto") storedHdrMode else "auto"
        hdrIntensity.value = p.getFloat("hdrIntensity", 1.6f).coerceIn(0.5f, 3.5f)
        recordedAppVersionCode.value = p.getInt("recordedAppVersionCode", 0)
        recordedAppInstallTimeStamp.value = p.getLong("recordedAppInstallTimeStamp", System.currentTimeMillis())
        appReviewRequested.value = p.getBoolean("appReviewRequested", false)
    }

    private fun restoreAlbumArt() {
        val online = DiffuseUtils.getValueOrDefault(engineOnline, false)
        DiffuseUtils.logDebug("restoreAlbumArt engineOnline $online")
        val p = prefs()
        if (online) {
            var bitmap = DiffuseUtils.getValueOrDefault(playingAlbumArt, null)
            if (bitmap == null && p.getBoolean("preserveStateAfterRestart", false)) {
                DiffuseUtils.logDebug("load local save")
                bitmap = DiffuseUtils.loadBitmap(DiffuseUtils.getFilesDir(), ALBUM_CACHE_FILE)
            }
            if (bitmap != null) {
                playingAlbumArt.postValue(bitmap)
            } else {
                DiffuseUtils.logRemoteError("bitmap null")
            }
        }
    }

    private fun save() {
        DiffuseUtils.logDebug("save")
        prefs().edit().apply {
            putBoolean("enableLiveBeats", DiffuseUtils.getValueOrDefault(enableLiveBeats, false))
            putFloat("liveBeatsStrength", DiffuseUtils.getValueOrDefault(liveBeatsStrength, 0.5f))
            putBoolean("enableDebugMode", DiffuseUtils.getValueOrDefault(enableDebugMode, false))
            putBoolean("enableGraphicsOverride", DiffuseUtils.getValueOrDefault(enableGraphicsOverride, true))
            putBoolean("enableFluidOverride", DiffuseUtils.getValueOrDefault(enableFluidOverride, true))
            putBoolean("enableMiscOverride", DiffuseUtils.getValueOrDefault(enableMiscOverride, true))
            putBoolean("enablePerformanceMode", DiffuseUtils.getValueOrDefault(enablePerformanceMode, false))
            putBoolean("enableWallpaperColorAPI", DiffuseUtils.getValueOrDefault(enableWallpaperColorAPI, false))
            putFloat("noiseScale", DiffuseUtils.getValueOrDefault(noiseScale, 0.8f))
            putBoolean("enablePreserveState", DiffuseUtils.getValueOrDefault(enablePreserveState, true))
            putBoolean("preserveStateAfterPause", DiffuseUtils.getValueOrDefault(preserveStateAfterPause, false))
            putBoolean("preserveStateAfterRestart", DiffuseUtils.getValueOrDefault(preserveStateAfterRestart, false))
            putString("defaultAlbumArtID", DiffuseUtils.getValueOrDefault(defaultAlbumArtID, MusicPaletteManager.DEFAULT_PALETTE_ID))
            putString("postProcessingEffectMode", DiffuseUtils.getValueOrDefault(postProcessingEffectMode, "blur"))
            putFloat("noiseSpeedMin", DiffuseUtils.getValueOrDefault(noiseSpeedMin, 0.05f))
            putFloat("noiseSpeedMax", DiffuseUtils.getValueOrDefault(noiseSpeedMax, 0.5f))
            putFloat("brightnessOffset", DiffuseUtils.getValueOrDefault(brightnessOffset, 0.0f))
            putFloat("saturation", DiffuseUtils.getValueOrDefault(saturation, 1.0f))
            putString("hdrMode", DiffuseUtils.getValueOrDefault(hdrMode, "auto"))
            putFloat("hdrIntensity", DiffuseUtils.getValueOrDefault(hdrIntensity, 1.6f))
            putInt("recordedAppVersionCode", DiffuseUtils.getValueOrDefault(recordedAppVersionCode, 0))
            putLong("recordedAppInstallTimeStamp", DiffuseUtils.getValueOrDefault(recordedAppInstallTimeStamp, System.currentTimeMillis()))
            putBoolean("appReviewRequested", DiffuseUtils.getValueOrDefault(appReviewRequested, true))
        }.apply()
    }

    private fun saveAlbumArt() {
        DiffuseUtils.logDebug("saveAlbumArt")
        if (!DiffuseUtils.getValueOrDefault(preserveStateAfterRestart, false)) return
        val bitmap = playingAlbumArt.value ?: return
        DiffuseUtils.logDebug("playingAlbumArt $bitmap saving to $ALBUM_CACHE_FILE")
        DiffuseUtils.saveBitmapJpeg(DiffuseUtils.getFilesDir(), ALBUM_CACHE_FILE, bitmap)
    }

    fun updateVersionRecord() {
        val oldVersion = DiffuseUtils.getValueOrDefault(recordedAppVersionCode, 0)
        DiffuseUtils.logDebug("oldVersion $oldVersion newVersion ${BuildConfig.VERSION_CODE}")
        if (oldVersion != BuildConfig.VERSION_CODE) {
            DiffuseUtils.setLiveDataValue(recordedAppVersionCode, BuildConfig.VERSION_CODE)
            DiffuseUtils.setLiveDataValue(recordedAppInstallTimeStamp, System.currentTimeMillis())
            DiffuseUtils.setLiveDataValue(appReviewRequested, false)
        }
    }

    fun getPostProcessRadioId(): Int =
        if (postProcessingEffectMode.value == "blur") {
            R.id.blur_post_process_effect
        } else {
            R.id.fluted_glass_post_process_effect
        }

    fun setPostProcessEffectFromRadioId(id: Int) {
        postProcessingEffectMode.value =
            if (id == R.id.blur_post_process_effect) "blur" else "flutedGlass"
    }

    fun getHdrRadioId(): Int = when (hdrMode.value) {
        "on" -> R.id.hdr_mode_on
        "off" -> R.id.hdr_mode_off
        else -> R.id.hdr_mode_auto
    }

    fun setHdrModeFromRadioId(id: Int) {
        hdrMode.value = when (id) {
            R.id.hdr_mode_on -> "on"
            R.id.hdr_mode_off -> "off"
            else -> "auto"
        }
    }
}
