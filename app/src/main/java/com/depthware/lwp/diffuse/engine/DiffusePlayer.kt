package com.depthware.lwp.diffuse.engine

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.profiling.GLProfiler
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.PerformanceCounter
import com.badlogic.gdx.utils.viewport.ScreenViewport
import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.exp
import kotlin.math.sqrt

class DiffusePlayer(private val callback: PlayerCallback? = null) : ApplicationAdapter() {

    companion object {
        const val ALBUM_FBO_SIZE = 192
    }

    interface PlayerCallback {
        fun onPlayerReady()
        fun onFrameRendered()
    }

    private lateinit var batch: SpriteBatch
    private var glProfiler: GLProfiler? = null

    private lateinit var blurCounter: PerformanceCounter
    private lateinit var noiseCounter: PerformanceCounter
    private lateinit var textCounter: PerformanceCounter
    private lateinit var fadeCounter: PerformanceCounter

    private lateinit var fboA: FrameBuffer
    private lateinit var fboB: FrameBuffer
    private lateinit var fboC: FrameBuffer
    private var screenFbo: FrameBuffer? = null

    private lateinit var blurShader: ShaderProgram
    private lateinit var noiseShader: ShaderProgram
    private lateinit var fadeShader: ShaderProgram
    private lateinit var colorShader: ShaderProgram
    private lateinit var glassShader: ShaderProgram
    private var fontShader: ShaderProgram? = null

    private var debugFont: BitmapFont? = null
    private lateinit var viewport: ScreenViewport
    private var randTexture: Texture? = null

    private var positionMs: Long = 0L
    private var playing: Boolean = false
    private var maxSpeed: Float = 0.5f
    private var minSpeed: Float = 0.08f
    private var targetSpeed: Float = 0.2f
    private var currentSpeed: Float = 1.0f

    private var enableGraphicsOverride: Boolean = true
    private var enableFluidOverride: Boolean = true
    private var enableMiscOverride: Boolean = true

    private var noiseScale: Float = 1.0f
    private var postProcessingMode: String = "blur"
    private var brightnessOffset: Float = 0.0f
    private var saturation: Float = 1.0f
    private var hdrEnabled: Boolean = false
    private var hdrIntensity: Float = 1.6f
    private var debugMode: Boolean = false
    private var performanceMode: Boolean = false

    private var audioBands: DoubleArray? = null
    private var beatStrength: Float = 1.0f
    private var beatLerpAlpha: Float = 0.08f
    private var targetBeat: Float = 0.0f
    private var currentBeat: Float = 0.0f
    private var detectedBpm: Float = 0.0f
    private var bpmConfidence: Float = 0.0f
    private var beatPhase: Float = 0.0f

    private var totalTime: Double = 0.0
    private var scaledTime: Double = 0.0
    private val timeWrap: Double = 10000.0

    private fun setupScreenFbo(forceRecreate: Boolean) {
        val width = Gdx.graphics.width
        val height = Gdx.graphics.height
        var density = Gdx.graphics.density
        if (density < 1.0f) {
            density = 2.5f
        }
        val scaledWidth = maxOf(1, (width / density).toInt())
        val scaledHeight = maxOf(1, (height / density).toInt())
        val existing = screenFbo
        if (existing == null || existing.height != scaledHeight || existing.width != scaledWidth || forceRecreate) {
            existing?.dispose()
            val fbo = FrameBuffer(Pixmap.Format.RGB888, scaledWidth, scaledHeight, false)
            fbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            screenFbo = fbo
            Gdx.app.debug(
                "Player",
                "Resize\nGdx.graphics.getDensity() = $density\n" +
                    "Gdx.graphics.getPpiX() = ${Gdx.graphics.ppiX}\n" +
                    "Gdx.graphics.getPpiY() = ${Gdx.graphics.ppiY}\n" +
                    "Gdx.graphics.getWidth() = $width\n" +
                    "Gdx.graphics.getHeight() = $height\n" +
                    "scaledWidth = $scaledWidth\nscaledHeight = $scaledHeight"
            )
        }
    }

    override fun create() {
        Gdx.app.debug("Player", "Init Begin")
        glProfiler = GLProfiler(Gdx.graphics)
        Gdx.gl.glDisable(GL20.GL_DITHER)

        blurCounter = PerformanceCounter("Blur")
        noiseCounter = PerformanceCounter("Noise")
        textCounter = PerformanceCounter("Text")
        fadeCounter = PerformanceCounter("Fade")

        ShaderProgram.pedantic = false
        Gdx.graphics.isContinuousRendering = true

        batch = SpriteBatch(8)
        batch.maxSpritesInBatch = 10
        batch.enableBlending()

        viewport = ScreenViewport()
        viewport.apply()

        blurShader = ShaderProgram(
            Gdx.files.internal("kawaseBlur.vert"),
            Gdx.files.internal("kawaseBlur.frag")
        )
        if (!blurShader.isCompiled) {
            Gdx.app.error("Player", "blurShader compilation failed:\n${blurShader.log}")
        }

        noiseShader = ShaderProgram(
            Gdx.files.internal("noise.vert"),
            Gdx.files.internal("noise.frag")
        )
        if (!noiseShader.isCompiled) {
            Gdx.app.error("Player", "noiseShader compilation failed:\n${noiseShader.log}")
        }

        fadeShader = ShaderProgram(
            Gdx.files.internal("fade.vert"),
            Gdx.files.internal("fade.frag")
        )
        if (!fadeShader.isCompiled) {
            Gdx.app.error("Player", "fadeShader compilation failed:\n${fadeShader.log}")
        }

        colorShader = ShaderProgram(
            Gdx.files.internal("color.vert"),
            Gdx.files.internal("color.frag")
        )
        if (!colorShader.isCompiled) {
            Gdx.app.error("Player", "colorShader compilation failed:\n${colorShader.log}")
        }

        glassShader = ShaderProgram(
            Gdx.files.internal("flutedGlass.vert"),
            Gdx.files.internal("flutedGlass.frag")
        )
        if (!glassShader.isCompiled) {
            Gdx.app.error("Player", "glassShader compilation failed:\n${glassShader.log}")
        }

        fboA = FrameBuffer(Pixmap.Format.RGB888, ALBUM_FBO_SIZE, ALBUM_FBO_SIZE, false).apply {
            colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        fboB = FrameBuffer(Pixmap.Format.RGB888, ALBUM_FBO_SIZE, ALBUM_FBO_SIZE, false).apply {
            colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        fboC = FrameBuffer(Pixmap.Format.RGB888, ALBUM_FBO_SIZE, ALBUM_FBO_SIZE, false).apply {
            colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        setupScreenFbo(true)

        val fontTex = Texture(Gdx.files.internal("ibm-plex-mono-regular.png"), true).apply {
            setFilter(Texture.TextureFilter.MipMapLinearNearest, Texture.TextureFilter.Linear)
        }
        debugFont = BitmapFont(Gdx.files.internal("ibm-plex-mono-regular.fnt"), TextureRegion(fontTex), false)

        val fShader = ShaderProgram(
            Gdx.files.internal("font.vert"),
            Gdx.files.internal("font.frag")
        )
        fontShader = fShader
        if (!fShader.isCompiled) {
            Gdx.app.error("fontShader", "compilation failed:\n${fShader.log}")
        }

        val rand = Texture(Gdx.files.internal("rand.png"), true).apply {
            setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        randTexture = rand
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE2)
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, rand.textureObjectHandle)
        rand.bind()
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)

        callback?.onPlayerReady()
        Gdx.app.debug("Player", "Init End")
    }

    override fun dispose() {
        Gdx.app.log("Player", "dispose")
        batch.dispose()
        debugFont?.dispose()
        blurShader.dispose()
        fontShader?.dispose()
        noiseShader.dispose()
        fadeShader.dispose()
        colorShader.dispose()
        glassShader.dispose()
        fboA.dispose()
        fboB.dispose()
        fboC.dispose()
        screenFbo?.dispose()
        screenFbo = null
        randTexture?.dispose()
        randTexture = null
    }

    override fun resume() {
        super.resume()
        Gdx.app.log("Player", "resume")
    }

    override fun pause() {
        super.pause()
        Gdx.app.log("Player", "pause")
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        viewport.update(width, height, true)
        viewport.camera.update()
        batch.projectionMatrix = viewport.camera.combined
        setupScreenFbo(false)
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        totalTime += delta.toDouble()
        scaledTime = (scaledTime + (delta * currentSpeed).toDouble()) % timeWrap
        var targetPulseBeat = 0.0f
        var speedBonus = 0.0f
        if (detectedBpm > 20.0f && bpmConfidence >= 0.25f && beatStrength > 0.001f) {
            beatPhase = (beatPhase + delta * (detectedBpm / 60.0f)) % 1.0f
            val attackFrac = 0.06f
            val envelope = if (beatPhase < attackFrac) {
                beatPhase / attackFrac
            } else {
                exp((-(beatPhase - attackFrac) * 5.2f).toDouble()).toFloat()
            }
            val pulse = sqrt(maxOf(0.0f, envelope).toDouble()).toFloat()
            targetPulseBeat = pulse * beatStrength * 1.65f
            speedBonus = pulse * beatStrength * 0.45f
        }
        val speedAlpha = 1.0f - Math.pow(1.0 - 0.025, (delta * 60.0f).toDouble()).toFloat()
        val beatAlpha = minOf(1.0f, maxOf(0.18f, 1.0f - Math.pow(1.0 - 0.38, (delta * 60.0f).toDouble()).toFloat()))
        currentSpeed = MathUtils.lerp(currentSpeed, targetSpeed + speedBonus, speedAlpha)
        currentBeat = MathUtils.lerp(currentBeat, targetPulseBeat, beatAlpha)

        batch.shader = null
        val width = Gdx.graphics.width
        val height = Gdx.graphics.height
        if (width <= 0 || height <= 0) {
            return
        }
        val density = Gdx.graphics.density
        val activeScreenFbo = screenFbo
        val scaledWidth = activeScreenFbo?.width ?: 0
        val scaledHeight = activeScreenFbo?.height ?: 0
        val widthF = width.toFloat()
        val heightF = height.toFloat()
        val aspectRatio = widthF / heightF

        if (activeScreenFbo == null || !Gdx.gl.glIsFramebuffer(activeScreenFbo.framebufferHandle)) {
            Gdx.app.error("Player", "Screen Buffer Invalidated, setupRatioSensitiveTextures true")
            setupScreenFbo(true)
        }
        val renderFbo = screenFbo!!

        if (enableFluidOverride) {
            if (debugMode) {
                blurCounter.tick()
                noiseCounter.tick()
                textCounter.tick()
                fadeCounter.tick()
                fadeCounter.start()
            }

            // Pass 1: Copy current state (fboA) -> scratch (fboC), then crossfade fboC -> fboA toward fboB (unit 1)
            fboC.begin()
            batch.begin()
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            batch.shader = null
            batch.draw(fboA.colorBufferTexture, 0.0f, 0.0f, widthF, heightF, 0.0f, 0.0f, 1.0f, 1.0f)
            batch.flush()
            batch.end()
            fboC.end()

            fboA.begin()
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            fadeShader.bind()
            fadeShader.setUniformi("u_targetTexture", 1)
            batch.begin()
            batch.shader = fadeShader
            batch.draw(fboC.colorBufferTexture, 0.0f, 0.0f, widthF, heightF, 0.0f, 0.0f, 1.0f, 1.0f)
            batch.flush()
            batch.end()
            fboA.end()

            if (debugMode) {
                fadeCounter.stop()
                noiseCounter.start()
            }

            // Pass 2: Domain-warp fboA -> density-scaled screenFbo
            batch.begin()
            renderFbo.begin()
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            if (enableMiscOverride) {
                noiseShader.bind()
                noiseShader.setUniformf("u_scale", noiseScale)
                noiseShader.setUniformf("u_border", currentBeat)
                noiseShader.setUniformf("u_timeScaled", scaledTime.toFloat())
                noiseShader.setUniform2fv("u_resolution", floatArrayOf(scaledWidth.toFloat(), scaledHeight.toFloat()), 0, 2)
                if (performanceMode) {
                    noiseShader.setUniformi("u_pre_compute", 1)
                    noiseShader.setUniformi("u_rand", 2)
                } else {
                    noiseShader.setUniformi("u_pre_compute", 0)
                }
                batch.shader = noiseShader
            } else {
                batch.shader = null
            }
            batch.draw(
                fboA.colorBufferTexture,
                0.0f, 0.0f, 0.0f, 0.0f,
                widthF, heightF,
                1.0f, 1.0f, 0.0f,
                0, 0, fboA.width, fboA.height,
                false, false
            )
            batch.flush()
            renderFbo.end()
            batch.end()

            if (debugMode) {
                noiseCounter.stop()
            }

            // Pass 3: Upscale screenFbo to backbuffer via blur or flutedGlass
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            if (debugMode) {
                blurCounter.start()
            }
            batch.begin()
            if (postProcessingMode == "blur" && enableGraphicsOverride) {
                blurShader.bind()
                blurShader.setUniformi("u_level", 1)
                applyOutputUniforms(blurShader, isFinalPass = true)
                batch.shader = blurShader
            } else if (postProcessingMode == "flutedGlass") {
                glassShader.bind()
                glassShader.setUniformf("u_numSegments", 16.0f * aspectRatio)
                applyOutputUniforms(glassShader, isFinalPass = true)
                batch.shader = glassShader
            } else {
                colorShader.bind()
                applyOutputUniforms(colorShader, isFinalPass = true)
                batch.shader = colorShader
            }
            batch.draw(renderFbo.colorBufferTexture, 0.0f, 0.0f, widthF, heightF, 0.0f, 0.0f, 1.0f, 1.0f)
            batch.flush()
            batch.end()
            if (debugMode) {
                blurCounter.stop()
                textCounter.start()
            }

            // Pass 4: Optional debug profiler overlay
            batch.begin()
            if (debugMode) {
                batch.enableBlending()
                batch.shader = fontShader
                val bandsText = audioBands?.joinToString(" ") { band ->
                    String.format(Locale.US, "[%6.2f]", band)
                } ?: " "
                val profiler = glProfiler
                val debugText = String.format(
                    Locale.US,
                    "DEBUG INFO\nFPS: %s (Perf Mode: %s)\nPlaying: %s\nTime: %s\nScaledTime: %s\nTimeResetIn: %s\n" +
                        "TimeSpeed: %5.3f (%5.3f-%5.3f-%5.3f)\nCurrent: %s\nDensity: %3.1f\nWidth: %s (%s)\n" +
                        "Height: %s (%s)\nBeat: %8.6f (%5.3f in %5.3f x)\nBeats: %s\nBrightness Offset: %4.3f\n" +
                        "DrawCall: %s\nCall: %s\nTex Binding: %s\nShader Switch: %s\nFade: %s\nBlur: %s\nNoise: %s\nText: %s",
                    Gdx.graphics.framesPerSecond,
                    if (performanceMode) "On" else "Off",
                    playing,
                    totalTime,
                    scaledTime,
                    timeWrap - scaledTime,
                    currentSpeed,
                    minSpeed,
                    targetSpeed,
                    maxSpeed,
                    positionMs,
                    density,
                    width,
                    scaledWidth,
                    height,
                    scaledHeight,
                    currentBeat,
                    targetBeat,
                    beatLerpAlpha,
                    bandsText,
                    brightnessOffset,
                    profiler?.drawCalls ?: 0,
                    profiler?.calls ?: 0,
                    profiler?.textureBindings ?: 0,
                    profiler?.shaderSwitches ?: 0,
                    String.format(Locale.US, "%6.3fms [%4.2f%%]", fadeCounter.time.average * 1000.0f, fadeCounter.load.average * 100.0f),
                    String.format(Locale.US, "%6.3fms [%4.2f%%]", blurCounter.time.average * 1000.0f, blurCounter.load.average * 100.0f),
                    String.format(Locale.US, "%6.3fms [%4.2f%%]", noiseCounter.time.average * 1000.0f, noiseCounter.load.average * 100.0f),
                    String.format(Locale.US, "%6.3fms [%4.2f%%]", textCounter.time.average * 1000.0f, textCounter.load.average * 100.0f)
                )
                val font = debugFont
                if (font != null) {
                    val lineCount = debugText.split("\r\n|\r|\n".toRegex()).size + 1
                    font.draw(batch, debugText, font.lineHeight, font.lineHeight * lineCount)
                }
                batch.flush()
                batch.shader = null
                batch.disableBlending()
            }
            batch.end()
            if (debugMode) {
                textCounter.stop()
            }
        }

        callback?.onFrameRendered()
        if (debugMode) {
            glProfiler?.reset()
        }
    }

    fun setAlbumToBlur(texture: Texture) {
        val tempA = FrameBuffer(Pixmap.Format.RGB888, ALBUM_FBO_SIZE, ALBUM_FBO_SIZE, false).apply {
            colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        val tempB = FrameBuffer(Pixmap.Format.RGB888, ALBUM_FBO_SIZE, ALBUM_FBO_SIZE, false).apply {
            colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }

        Gdx.app.debug("Player", "setAlbumToBlur")
        if (debugMode) {
            blurCounter.start()
        }

        tempA.begin()
        Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        batch.begin()
        colorShader.bind()
        applyOutputUniforms(colorShader, isFinalPass = false)
        batch.shader = colorShader
        batch.draw(texture, 0.0f, 0.0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat(), 0.0f, 0.0f, 1.0f, 1.0f)
        batch.flush()
        batch.end()
        tempA.end()

        blurShader.bind()
        applyOutputUniforms(blurShader, isFinalPass = false)
        blurShader.setUniform2fv("u_resolution", floatArrayOf(ALBUM_FBO_SIZE.toFloat(), ALBUM_FBO_SIZE.toFloat()), 0, 2)
        batch.shader = blurShader

        val levels = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4)
        for (i in levels.indices) {
            val level = levels[i] * 2
            val even = i % 2 == 0
            blurShader.setUniformi("u_level", level)
            batch.begin()
            if (even) tempB.begin() else tempA.begin()
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            val src = if (even) tempA else tempB
            batch.draw(src.colorBufferTexture, 0.0f, 0.0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat(), 0.0f, 0.0f, 1.0f, 1.0f)
            batch.flush()
            if (even) tempB.end() else tempA.end()
            batch.end()
        }

        fboB.begin()
        Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        batch.begin()
        batch.shader = null
        batch.draw(tempA.colorBufferTexture, 0.0f, 0.0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat(), 0.0f, 0.0f, 1.0f, 1.0f)
        batch.flush()
        batch.end()
        fboB.end()

        // Bind the color-buffer texture (not the FBO handle) onto texture unit 1 for fade.frag.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
        fboB.colorBufferTexture.bind(1)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)

        if (debugMode) {
            blurCounter.stop()
        }
        tempA.dispose()
        tempB.dispose()
    }

    fun setAudioStrength(strength: Float) {
        beatStrength = strength
        beatLerpAlpha = 0.06f * strength + 0.04f
    }

    fun setAudio(bands: DoubleArray, bpm: Float = 0.0f, confidence: Float = 0.0f) {
        audioBands = bands
        detectedBpm = bpm
        bpmConfidence = confidence
        if (bpm <= 0.0f) {
            beatPhase = 0.0f
        }
        // Live Beats pulses strictly from detected/fetched BPM rather than raw FFT amplitude.
        targetBeat = 0.0f
    }

    private fun applyOutputUniforms(shader: ShaderProgram, isFinalPass: Boolean) {
        shader.setUniformf("u_brightnessOffset", brightnessOffset)
        shader.setUniformf("u_saturation", saturation)
        shader.setUniformi("u_hdrEnabled", if (hdrEnabled) 1 else 0)
        shader.setUniformf("u_hdrIntensity", hdrIntensity)
        shader.setUniformi("u_isFinalPass", if (isFinalPass) 1 else 0)
    }

    fun setBrightnessFilter(brightness: Float) {
        brightnessOffset = brightness * 0.05f
    }

    fun setSaturation(saturation: Float) {
        this.saturation = saturation.coerceIn(0.0f, 2.0f)
    }

    fun setHdrConfig(enabled: Boolean, intensity: Float) {
        this.hdrEnabled = enabled
        this.hdrIntensity = intensity.coerceIn(0.5f, 3.5f)
    }

    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
        val profiler = glProfiler ?: return
        if (enabled) profiler.enable() else profiler.disable()
    }

    fun setNoiseScale(scale: Float) {
        noiseScale = scale * 1.2f + 0.4f
    }

    fun setPlaybackState(positionMs: Long, playing: Boolean) {
        this.positionMs = positionMs
        this.playing = playing
        targetSpeed = if (playing) maxSpeed else minSpeed
    }

    fun setSpeedRange(min: Float, max: Float) {
        maxSpeed = max
        minSpeed = min
        targetSpeed = if (playing) max else min
    }

    fun setPostProcessingMode(mode: String) {
        postProcessingMode = mode
    }

    fun setPerformanceMode(enabled: Boolean) {
        performanceMode = enabled
    }

    fun getScreenshot(fileName: String): String? {
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.width, Gdx.graphics.height)
        if (Gdx.files.isLocalStorageAvailable) {
            val handle = Gdx.files.local(fileName)
            PixmapIO.writePNG(handle, pixmap, Deflater.NO_COMPRESSION, true)
            pixmap.dispose()
            return handle.file().absolutePath
        }
        Gdx.app.debug("Player", "Gdx.files.isLocalStorageAvailable() returns false")
        pixmap.dispose()
        return null
    }
}
