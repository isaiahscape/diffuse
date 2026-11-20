package com.depthware.lwp.diffuse.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
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
            .map { sbn -> ArtSource(sbn, sbn.notification?.getLargeIcon(), readMediaMetadata(sbn)) }
            .distinctUntilChanged { a, b ->
                a.largeIcon == b.largeIcon && a.metadataKey == b.metadataKey
            }
            .map { src -> Optional.ofNullable(extractAlbumArtBitmap(src.largeIcon, src.metadata)) }
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

    private data class ArtSource(
        val sbn: StatusBarNotification,
        val largeIcon: Icon?,
        val metadata: MediaMetadata?
    ) {
        val metadataKey: String = buildString {
            append(sbn.packageName)
            append('|')
            append(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            append('|')
            append(metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            append('|')
            append(
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                    ?: ""
            )
            append('|')
            append(sbn.postTime)
        }
    }

    @Suppress("DEPRECATION")
    private fun readMediaMetadata(sbn: StatusBarNotification): MediaMetadata? {
        val token = sbn.notification?.extras
            ?.getParcelable<MediaSession.Token>("android.mediaSession") ?: return null
        return try {
            MediaController(this, token).metadata
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAlbumArtBitmap(
        largeIcon: Icon?,
        metadata: MediaMetadata?
    ): Bitmap? {
        val iconBitmap = largeIcon?.loadDrawable(this)?.let { drawable: Drawable ->
            DiffuseUtils.drawableToBitmap(drawable)
        }
        if (iconBitmap != null) return iconBitmap

        val meta = metadata ?: return null
        return meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: loadArtFromUri(meta)
    }

    private fun loadArtFromUri(metadata: MediaMetadata): Bitmap? {
        val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        val uri = Uri.parse(uriStr)
        if (uri.scheme != "content" && uri.scheme != "file") return null
        return try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun publishNotificationMetadata(sbn: StatusBarNotification) {
        val vm = ViewModelManager.viewModel
        vm.playingPackageName.postValue(sbn.packageName)
        val extras = sbn.notification.extras
        val meta = readMediaMetadata(sbn)
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty() }
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty() }
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()
            .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() }
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
