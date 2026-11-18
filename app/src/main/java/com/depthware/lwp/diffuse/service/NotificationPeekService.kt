package com.depthware.lwp.diffuse.service

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.depthware.lwp.diffuse.manager.GdxPlayerManager
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.util.DiffuseUtils
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.Optional
import java.util.concurrent.TimeUnit

class NotificationPeekService : NotificationListenerService() {

    companion object {
        var albumArtSize = 512

        // Set to true only to reproduce the original APK's strict `!isClearable && isOngoing` predicate,
        // which rejects dismissible media notifications on Android 13+.
        private const val REQUIRE_NON_CLEARABLE = false
    }

    private val intervalTicker: Observable<Long> = Observable.interval(1000L, TimeUnit.MILLISECONDS)
    private val notificationSubject: PublishSubject<StatusBarNotification> = PublishSubject.create()

    private val filteredNotificationObservable: Observable<StatusBarNotification>
    private val mediaControllerObservable: Observable<MediaController>
    private val playbackStateObservable: Observable<PlaybackState>
    private val albumArtObservable: Observable<Optional<Bitmap>>

    private var notificationDisposable: Disposable? = null
    private var playbackDisposable: Disposable? = null
    private var albumArtDisposable: Disposable? = null

    init {
        filteredNotificationObservable = Observable.combineLatest(
            intervalTicker,
            notificationSubject.filter { sbn ->
                val passesOngoingCheck = if (REQUIRE_NON_CLEARABLE) {
                    DiffuseUtils.isOngoingNonClearable(sbn)
                } else {
                    true
                }
                passesOngoingCheck && DiffuseUtils.hasMediaSessionToken(sbn)
            }
        ) { _, sbn -> sbn }
            .doOnError { DiffuseUtils.logError(it) }

        @Suppress("DEPRECATION")
        mediaControllerObservable = filteredNotificationObservable
            .map { sbn ->
                sbn.notification.extras.getParcelable<MediaSession.Token>("android.mediaSession")!!
            }
            .distinctUntilChanged()
            .map { token -> MediaController(this, token) }
            .filter { controller ->
                controller.playbackState != null && controller.playbackInfo != null
            }
            .doOnError { DiffuseUtils.logError(it) }

        playbackStateObservable = Observable.combineLatest(
            intervalTicker,
            mediaControllerObservable
        ) { _, controller -> controller }
            .flatMap { controller ->
                val state = controller.playbackState
                if (state != null) Observable.just(state) else Observable.empty()
            }
            .distinctUntilChanged { a, b -> a == b }
            .doOnError { DiffuseUtils.logError(it) }

        albumArtObservable = filteredNotificationObservable
            .map { sbn -> Optional.ofNullable(sbn.notification.getLargeIcon()) }
            .filter { it.isPresent }
            .distinctUntilChanged { a, b -> a == b }
            .map { optIcon -> optIcon.flatMap { icon -> Optional.ofNullable(icon.loadDrawable(this)) } }
            .filter { it.isPresent }
            .distinctUntilChanged()
            .map { optDrawable ->
                optDrawable.map { drawable: Drawable -> DiffuseUtils.drawableToBitmap(drawable) }
            }
            .filter { it.isPresent }
            .distinctUntilChanged { a, b ->
                a.isPresent && b.isPresent && a.get().sameAs(b.get())
            }
            .map { optBitmap ->
                optBitmap.map { bmp ->
                    Bitmap.createScaledBitmap(bmp, albumArtSize, albumArtSize, true)
                }
            }
            .distinctUntilChanged { a, b ->
                a.isPresent && b.isPresent && a.get().sameAs(b.get())
            }
            .filter { it.isPresent }
            .distinctUntilChanged()
            .doOnError { DiffuseUtils.logError(it) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiffuseUtils.logDebug("onListenerConnected")

        albumArtDisposable?.let { if (!it.isDisposed) it.dispose() }
        albumArtDisposable = albumArtObservable.subscribe(
            { optBitmap ->
                DiffuseUtils.logDebug("albumArtObservable present -> ${optBitmap.isPresent}")
                optBitmap.ifPresent { bmp ->
                    ViewModelManager.viewModel.playingAlbumArt.postValue(bmp)
                }
            },
            { DiffuseUtils.logError(it) }
        )

        notificationDisposable?.let { if (!it.isDisposed) it.dispose() }
        notificationDisposable = filteredNotificationObservable.subscribe(
            { sbn -> publishNotificationMetadata(sbn) },
            { DiffuseUtils.logError(it) }
        )

        playbackDisposable?.let { if (!it.isDisposed) it.dispose() }
        playbackDisposable = playbackStateObservable.subscribe(
            { state -> publishPlaybackState(state) },
            { DiffuseUtils.logError(it) }
        )

        try {
            activeNotifications?.forEach { sbn ->
                notificationSubject.onNext(sbn)
            }
        } catch (e: Exception) {
            DiffuseUtils.logError(e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        notificationDisposable?.dispose()
        playbackDisposable?.dispose()
        albumArtDisposable?.dispose()
        DiffuseUtils.logDebug("onListenerDisconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        notificationSubject.onNext(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    private fun publishNotificationMetadata(sbn: StatusBarNotification) {
        val vm = ViewModelManager.viewModel
        vm.playingPackageName.postValue(sbn.packageName)
        val extras = sbn.notification.extras
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        DiffuseUtils.setLiveDataValue(vm.playingSubText, subText, post = true)
        DiffuseUtils.setLiveDataValue(vm.playingText, text, post = true)
        DiffuseUtils.setLiveDataValue(vm.playingTitle, title, post = true)
    }

    private fun publishPlaybackState(state: PlaybackState) {
        val playing = state.state == PlaybackState.STATE_PLAYING
        val position = state.position
        GdxPlayerManager.setPlaybackState(position, playing)
        val vm = ViewModelManager.viewModel
        DiffuseUtils.setLiveDataValue(vm.isPlaying, playing, post = true)
        DiffuseUtils.setLiveDataValue(vm.playingProgress, position, post = true)
    }
}
