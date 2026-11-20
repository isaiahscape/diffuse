package com.depthware.lwp.diffuse.manager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.engine.DiffusePlayer
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R

object GdxPlayerManager {

    interface ScreenshotCallback {
        fun onScreenshotSaved(path: String)
    }

    private var currentPlayer: DiffusePlayer? = null

    fun getPlayer(): DiffusePlayer {
        if (currentPlayer == null) {
            currentPlayer = DiffusePlayer(object : DiffusePlayer.PlayerCallback {
                override fun onPlayerReady() {
                    val vm = ViewModelManager.viewModel
                    vm.engineOnline.postValue(true)
                    setPlayerTexture(DiffuseUtils.getValueOrDefault(vm.playerTexture, null))
                    setSaturation(DiffuseUtils.getValueOrDefault(vm.saturation, 1.0f))
                    setHdrConfig(
                        DiffuseUtils.getValueOrDefault(vm.hdrEnabledToApply, false),
                        DiffuseUtils.getValueOrDefault(vm.hdrIntensity, 1.6f)
                    )
                }

                override fun onFrameRendered() = Unit
            })
        }
        return currentPlayer!!
    }

    fun getScreenshot(callback: ScreenshotCallback) {
        val player = currentPlayer
        val app = Gdx.app
        if (player != null && app != null) {
            app.postRunnable {
                val path = player.getScreenshot(
                    DiffuseApplication.appContext().getString(R.string.local_album_share_file_name)
                )
                if (path == null) {
                    DiffuseUtils.logRemoteError("Screenshot path null")
                } else {
                    DiffuseUtils.logDebug("Screenshot path -> $path")
                    callback.onScreenshotSaved(path)
                }
            }
            return
        }
        DiffuseUtils.logRemoteError("currentPlayer $player | Gdx.app $app | when called getScreenshot")
    }

    fun initObservers() {
        val vm = ViewModelManager.viewModel
        vm.enableDebugMode.observeForever { setDebugMode(it) }
        vm.playerTexture.observeForever { setPlayerTexture(it) }
        vm.liveBeatsStrength.observeForever { setAudioStrength(it) }
        vm.noiseScale.observeForever { setNoiseScale(it) }
        vm.postProcessingEffectMode.observeForever { setPostProcessingMode(it) }
        vm.enablePerformanceMode.observeForever { setPerformanceMode(it) }

        val updateSpeed = {
            setSpeedRange(
                DiffuseUtils.getValueOrDefault(vm.noiseSpeedMin, 0.08f),
                DiffuseUtils.getValueOrDefault(vm.noiseSpeedMax, 0.5f)
            )
        }
        vm.noiseSpeedMin.observeForever { updateSpeed() }
        vm.noiseSpeedMax.observeForever { updateSpeed() }
        vm.brightnessOffsetToApply.observeForever { setBrightnessFilter(it) }
        vm.saturation.observeForever { setSaturation(it) }
        val updateHdr = {
            setHdrConfig(
                DiffuseUtils.getValueOrDefault(vm.hdrEnabledToApply, false),
                DiffuseUtils.getValueOrDefault(vm.hdrIntensity, 1.6f)
            )
        }
        vm.hdrEnabledToApply.observeForever { updateHdr() }
        vm.hdrIntensity.observeForever { updateHdr() }
    }

    fun setAudio(bands: DoubleArray, bpm: Float = 0.0f, confidence: Float = 0.0f) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setAudio(bands, bpm, confidence) }
    }

    fun setAudioStrength(strength: Float) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setAudioStrength(strength) }
    }

    fun setBrightnessFilter(brightness: Float) {
        DiffuseUtils.logDebug("setBrightnessFilter, currentPlayer = $currentPlayer Gdx.app = ${Gdx.app}")
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setBrightnessFilter(brightness) }
    }

    fun setSaturation(saturation: Float) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setSaturation(saturation) }
    }

    fun setHdrConfig(enabled: Boolean, intensity: Float) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setHdrConfig(enabled, intensity) }
    }

    fun setDebugMode(enabled: Boolean) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setDebugMode(enabled) }
    }

    fun setNoiseScale(scale: Float) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setNoiseScale(scale) }
    }

    fun setPerformanceMode(enabled: Boolean) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setPerformanceMode(enabled) }
    }

    fun setPlaybackState(positionMs: Long, playing: Boolean) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setPlaybackState(positionMs, playing) }
    }

    fun setSpeedRange(min: Float, max: Float) {
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setSpeedRange(min, max) }
    }

    fun setPlayerTexture(bitmap: Bitmap?) {
        val validBitmap = bitmap?.takeIf { !it.isRecycled }
        DiffuseUtils.logDebug("setPlayerTexture album not null = ${validBitmap != null}")
        val scaledBitmap = if (validBitmap == null) {
            DiffuseUtils.logDebug("set default album")
            BitmapFactory.decodeResource(
                DiffuseApplication.appContext().resources,
                R.mipmap.album_monochrome
            )
        } else {
            DiffuseUtils.logDebug("set scaled album")
            val size = DiffusePlayer.ALBUM_FBO_SIZE
            Bitmap.createScaledBitmap(validBitmap, size, size, false)
        }
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable {
            val pixmap = Pixmap(scaledBitmap.width, scaledBitmap.height, Pixmap.Format.RGB888)
            val texture = Texture(pixmap, true)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.textureObjectHandle)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scaledBitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            player.setAlbumToBlur(texture)
            texture.dispose()
            pixmap.dispose()
        }
    }

    fun setPostProcessingMode(mode: String) {
        DiffuseUtils.logDebug("setPostProcessingMode, currentPlayer = $currentPlayer Gdx.app = ${Gdx.app}")
        val player = currentPlayer ?: return
        val app = Gdx.app ?: return
        app.postRunnable { player.setPostProcessingMode(mode) }
    }
}
