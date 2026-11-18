package com.depthware.lwp.diffuse.service

import android.app.WallpaperColors
import android.os.Build
import android.service.wallpaper.WallpaperService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.toPublisher
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService
import com.badlogic.gdx.graphics.Color
import com.depthware.lwp.diffuse.engine.GdxConfig
import com.depthware.lwp.diffuse.manager.GdxPlayerManager
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.viewModel.ServiceLifecycleDispatcher
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.TimeUnit

class LiveWallpaperService : AndroidLiveWallpaperService(), LifecycleOwner {

    private val lifecycleDispatcher = ServiceLifecycleDispatcher(this)
    private var colorsDisposable: Disposable? = null

    inner class DiffuseWallpaperEngine : AndroidWallpaperEngine() {
        override fun notifyColorsChanged() {
            super.notifyColorsChanged()
            DiffuseUtils.logDebug("notifyColorsChanged")
        }

        override fun onComputeColors(): WallpaperColors? {
            if (Build.VERSION.SDK_INT >= 27) {
                val vm = ViewModelManager.viewModel
                val colorApi = DiffuseUtils.getValueOrDefault(vm.enableWallpaperColorAPI, false)
                val online = DiffuseUtils.getValueOrDefault(vm.engineOnline, false)
                if (colorApi && online) {
                    val bitmap = DiffuseUtils.getValueOrDefault(vm.playerTexture, null)
                    val colors = bitmap?.takeIf { !it.isRecycled }?.let { WallpaperColors.fromBitmap(it) }
                    if (colors != null) {
                        DiffuseUtils.logDebug("onComputeColors $colors")
                        return colors
                    }
                }
            }
            return super.onComputeColors()
        }

        override fun onZoomChanged(zoom: Float) {
            super.onZoomChanged(zoom)
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleDispatcher.lifecycle

    override fun onCreateApplication() {
        super.onCreateApplication()
        initialize(GdxPlayerManager.getPlayer(), GdxConfig.create())
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleDispatcher.onServicePreSuperOnCreate()
        colorsDisposable = Flowable.fromPublisher(
            ViewModelManager.viewModel.playerTexture.toPublisher(this)
        )
            .throttleLast(1L, TimeUnit.SECONDS)
            .subscribe(
                { bitmap ->
                    if (Build.VERSION.SDK_INT >= 27) {
                        val vm = ViewModelManager.viewModel
                        val online = DiffuseUtils.getValueOrDefault(vm.engineOnline, false)
                        val colorApi = DiffuseUtils.getValueOrDefault(vm.enableWallpaperColorAPI, false)
                        val liveApp = app
                        if (online && colorApi && liveApp != null) {
                            DiffuseUtils.logDebug("notifyColorsChanged $bitmap from engine $this")
                            liveApp.notifyColorsChanged(Color.BLACK, Color.BLACK, Color.BLACK)
                            return@subscribe
                        }
                    }
                    DiffuseUtils.logWarning(
                        "notifyColorsChanged requirements not meet, do not call app.notifyColorsChanged"
                    )
                },
                { err -> DiffuseUtils.logError(err) }
            )
    }

    override fun onCreateEngine(): WallpaperService.Engine {
        DiffuseUtils.logDebug("onCreateEngine")
        return DiffuseWallpaperEngine()
    }

    override fun onDestroy() {
        lifecycleDispatcher.onServicePreSuperOnDestroy()
        colorsDisposable?.let {
            if (!it.isDisposed) it.dispose()
        }
        super.onDestroy()
    }
}
