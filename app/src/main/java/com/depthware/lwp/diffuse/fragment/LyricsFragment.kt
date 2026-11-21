package com.depthware.lwp.diffuse.fragment

import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.service.NotificationPeekService
import com.depthware.lwp.diffuse.ui.lyrics.Lyrics
import com.depthware.lwp.diffuse.ui.lyrics.LyricsLine
import com.depthware.lwp.diffuse.ui.lyrics.LyricsViewModel
import com.depthware.lwp.diffuse.util.LyricsRepository
import com.depthware.lwp.diffuse.util.WindowInsetsHelper
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutLyricsFragmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LyricsFragment : Fragment() {

    private var _binding: LayoutLyricsFragmentBinding? = null
    private val binding get() = _binding!!

    private var lyricsViewModel: LyricsViewModel? = null
    private var fetchJob: Job? = null
    private var activeTrackKey: String = ""
    private var currentSynced: Boolean = false
    private var currentWordTimed: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutLyricsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.root,
            left = false,
            top = false,
            right = false,
            bottom = true
        )

        val lvm = LyricsViewModel(viewLifecycleOwner)
        lyricsViewModel = lvm
        lvm.onViewCreated(binding.fading) { line: LyricsLine ->
            if (currentSynced) {
                NotificationPeekService.seekTo(line.timestamp)
                lvm.syncPosition(line.timestamp + SYNC_LOOKAHEAD_MS)
            }
        }

        binding.btnLyricsClose.setOnClickListener {
            findNavController().popBackStack()
        }

        val vm = ViewModelManager.viewModel
        vm.playingAlbumArtDrawable.observe(viewLifecycleOwner) { drawable ->
            binding.imgLyricsAlbumArt.setImageDrawable(drawable)
        }

        val updateTrackHeaderAndFetch = {
            val title = vm.playingTitle.value.orEmpty()
            val artist = vm.playingText.value.orEmpty()
            val album = vm.playingSubText.value.orEmpty()
            val durationMs = vm.playingDurationMs.value ?: 0L

            binding.txtLyricsTitle.text = title.ifEmpty { getString(R.string.label_no_current_playing) }
            binding.txtLyricsArtist.text = artist
            binding.txtLyricsArtist.visibility = if (artist.isEmpty()) View.GONE else View.VISIBLE

            val trackKey = "$artist|$title|$album"
            if (trackKey != activeTrackKey) {
                activeTrackKey = trackKey
                loadLyricsForTrack(title, artist, album, durationMs, trackKey)
            }
        }

        vm.playingTitle.observe(viewLifecycleOwner) { updateTrackHeaderAndFetch() }
        vm.playingText.observe(viewLifecycleOwner) { updateTrackHeaderAndFetch() }
        vm.playingSubText.observe(viewLifecycleOwner) { updateTrackHeaderAndFetch() }
        vm.playingDurationMs.observe(viewLifecycleOwner) { updateTrackHeaderAndFetch() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (currentSynced) {
                        val posMs = estimateCurrentPositionMs() + SYNC_LOOKAHEAD_MS
                        lyricsViewModel?.syncPosition(posMs)
                    }
                    if (currentWordTimed && vm.isPlaying.value == true) {
                        awaitNextFrame()
                    } else {
                        delay(100L)
                    }
                }
            }
        }
    }

    private suspend fun awaitNextFrame() {
        suspendCancellableCoroutine<Long> { cont ->
            val choreographer = Choreographer.getInstance()
            val callback = Choreographer.FrameCallback { frameTimeNanos ->
                if (cont.isActive) {
                    cont.resume(frameTimeNanos)
                }
            }
            choreographer.postFrameCallback(callback)
            cont.invokeOnCancellation {
                choreographer.removeFrameCallback(callback)
            }
        }
    }

    private fun loadLyricsForTrack(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        trackKey: String
    ) {
        fetchJob?.cancel()
        currentSynced = false
        currentWordTimed = false

        if (title.isBlank()) {
            lyricsViewModel?.setLyrics(Lyrics.Empty, 0L)
            binding.txtLyricsStatus.setText(R.string.label_lyrics_empty)
            binding.txtLyricsStatus.visibility = View.VISIBLE
            return
        }

        binding.txtLyricsStatus.setText(R.string.label_lyrics_loading)
        binding.txtLyricsStatus.visibility = View.VISIBLE
        lyricsViewModel?.setLyrics(Lyrics.Empty, 0L)

        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val lookup = withContext(Dispatchers.IO) {
                LyricsRepository.fetchLyrics(title, artist, album, durationMs)
            }
            if (!isActive || activeTrackKey != trackKey || _binding == null) return@launch

            currentSynced = lookup.isSynced
            currentWordTimed = lookup.hasWordTiming
            if (lookup.lyrics == Lyrics.Empty || lookup.lyrics.lyrics.isEmpty()) {
                binding.txtLyricsStatus.setText(R.string.label_lyrics_empty)
                binding.txtLyricsStatus.visibility = View.VISIBLE
                lyricsViewModel?.setLyrics(Lyrics.Empty, 0L)
            } else {
                binding.txtLyricsStatus.visibility = View.GONE
                val initialPos = if (lookup.isSynced) estimateCurrentPositionMs() + SYNC_LOOKAHEAD_MS else 0L
                lyricsViewModel?.setLyrics(lookup.lyrics, initialPos)
            }
        }
    }

    private fun estimateCurrentPositionMs(): Long {
        val vm = ViewModelManager.viewModel
        val basePos = vm.playingProgress.value ?: 0L
        val playing = vm.isPlaying.value == true
        if (!playing) return basePos
        val updateTime = vm.playingPositionUpdateTimeMs.value ?: 0L
        if (updateTime <= 0L) return basePos
        val speed = vm.playingPlaybackSpeed.value ?: 1.0f
        val elapsed = (SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L)
        return basePos + (elapsed * speed).toLong()
    }

    override fun onDestroyView() {
        fetchJob?.cancel()
        fetchJob = null
        lyricsViewModel?.release()
        lyricsViewModel = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val SYNC_LOOKAHEAD_MS = 200L
    }
}
