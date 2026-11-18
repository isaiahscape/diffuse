package com.depthware.lwp.diffuse.fragment

import android.Manifest
import android.animation.LayoutTransition
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.depthware.lwp.diffuse.activity.FeedbackActivity
import com.depthware.lwp.diffuse.activity.LaunchMusicAppsActivity
import com.depthware.lwp.diffuse.activity.ScreenRecordActivity
import com.depthware.lwp.diffuse.activity.SelectDefaultPaletteActivity
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.manager.GdxPlayerManager
import com.depthware.lwp.diffuse.manager.ToastManager
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.service.LiveWallpaperService
import com.depthware.lwp.diffuse.service.NotificationPeekService
import com.depthware.lwp.diffuse.share.AlbumShareTask
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.util.WindowInsetsHelper
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutMainFragmentBinding
import com.depthware.lwp.diffuse.view.AcknowledgementDialogView
import com.depthware.lwp.diffuse.view.ShareStyleDialogView
import com.depthware.lwp.diffuse.viewModel.MainFragmentViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import pub.devrel.easypermissions.PermissionRequest
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.Date

class MainFragment : Fragment() {

    companion object {
        private const val RC_AUDIO_PERMS = 1
    }

    private var _binding: LayoutMainFragmentBinding? = null
    private val binding get() = _binding!!
    private val fragViewModel: MainFragmentViewModel by viewModels()

    private val debugTapSubject: PublishSubject<Date> = PublishSubject.create()
    private var debugTapDisposable: Disposable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutMainFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        WindowInsetsHelper.applySystemWindowInsetsPadding(
            binding.mainFragScrollLinearLayout,
            left = false,
            top = false,
            right = false,
            bottom = true
        )

        setupDebugTapDetector()
        bindRequirementsCard()
        bindPlayingCard()
        bindConfigCard()
        bindDebugCard()
        bindExperimentalCard()
        bindAboutCard()
        bindFaqCard()
    }

    override fun onResume() {
        super.onResume()
        binding.mainFragScrollLinearLayout.layoutTransition?.enableTransitionType(LayoutTransition.CHANGING)
        ViewModelManager.viewModel.refresh()
    }

    override fun onDestroyView() {
        debugTapDisposable?.dispose()
        _binding = null
        super.onDestroyView()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    private fun setupDebugTapDetector() {
        val initialWindow = (1..5).map { Date(it * 2000L) }
        debugTapDisposable = debugTapSubject
            .buffer(6, 1)
            .startWithIterable(listOf(initialWindow))
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { taps ->
                    if (taps.isEmpty()) return@subscribe
                    val latest = taps.last()
                    val recentCount = taps.count { latest.time - it.time < 2000L }.toLong()
                    val vm = ViewModelManager.viewModel
                    val current = DiffuseUtils.getValueOrDefault(vm.enableDebugMode, false)
                    if (recentCount == 6L) {
                        val stateStr = getString(if (!current) R.string.enabled else R.string.disabled)
                        ToastManager.showUpdated(String.format(getString(R.string.debug_mode_now_ed), stateStr))
                        DiffuseUtils.setLiveDataValue(vm.enableDebugMode, !current)
                    } else if (recentCount > 1L) {
                        val actionStr = getString(if (current) R.string.disable else R.string.enable)
                        ToastManager.showUpdated(
                            String.format(getString(R.string.debug_mode_v_in_s_clicks), actionStr, 6L - recentCount)
                        )
                    }
                },
                { DiffuseUtils.logError(it) }
            )
    }

    private fun bindRequirementsCard() {
        val vm = ViewModelManager.viewModel
        val card = binding.cardRequirements

        fragViewModel.showSetupPanel.observe(viewLifecycleOwner) { show ->
            card.root.visibility = if (show == true) View.VISIBLE else View.GONE
        }
        fragViewModel.showSetWallpaperButton.observe(viewLifecycleOwner) { show ->
            card.btnSetWallpaper.visibility = if (show == true) View.VISIBLE else View.GONE
        }
        fragViewModel.showSetNotificationButton.observe(viewLifecycleOwner) { show ->
            card.btnAllowNotification.visibility = if (show == true) View.VISIBLE else View.GONE
        }
        fragViewModel.showAudioPermissionButton.observe(viewLifecycleOwner) { show ->
            card.btnAllowVolume.visibility = if (show == true) View.VISIBLE else View.GONE
        }

        vm.isWallpaperActive.observe(viewLifecycleOwner) { active ->
            val ok = active == true
            card.btnSetWallpaper.isEnabled = !ok
            card.btnSetWallpaper.icon = ContextCompat.getDrawable(
                requireContext(),
                if (ok) R.drawable.ic_check_24px else R.drawable.ic_remove_24px
            )
        }
        vm.isNotificationGranted.observe(viewLifecycleOwner) { granted ->
            val ok = granted == true
            card.btnAllowNotification.isEnabled = !ok
            card.btnAllowNotification.icon = ContextCompat.getDrawable(
                requireContext(),
                if (ok) R.drawable.ic_check_24px else R.drawable.ic_remove_24px
            )
        }
        vm.isAudioPermGranted.observe(viewLifecycleOwner) { granted ->
            val ok = granted == true
            card.btnAllowVolume.isEnabled = !ok
            card.btnAllowVolume.icon = ContextCompat.getDrawable(
                requireContext(),
                if (ok) R.drawable.ic_check_24px else R.drawable.ic_remove_24px
            )
        }

        card.btnSetWallpaper.setOnClickListener { openLiveWallpaperPicker(it) }
        card.btnAllowNotification.setOnClickListener { openNotificationListenerSettings(it) }
        card.btnAllowVolume.setOnClickListener { requestAudioPermissions() }
    }

    private fun bindPlayingCard() {
        val vm = ViewModelManager.viewModel
        val card = binding.cardPlaying

        vm.isPlaying.observe(viewLifecycleOwner) { playing ->
            val isPlaying = playing == true
            card.layoutPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE
            card.layoutNotPlaying.visibility = if (isPlaying) View.GONE else View.VISIBLE
        }

        vm.isNotificationGranted.observe(viewLifecycleOwner) { granted ->
            val ok = granted == true
            card.btnOpenMusicAppEmpty.visibility = if (ok) View.VISIBLE else View.GONE
            card.txtNoPlayingDesc.text = getString(
                if (ok) R.string.label_no_current_playing_desc
                else R.string.label_no_current_playing_desc_no_permission
            )
        }

        vm.engineOnline.observe(viewLifecycleOwner) { online ->
            card.btnShareScreenshot.visibility = if (online == true) View.VISIBLE else View.GONE
        }

        fragViewModel.showPlayingAlbumArt.observe(viewLifecycleOwner) { show ->
            card.imgPlayingAlbumArt.visibility = if (show == true) View.VISIBLE else View.GONE
        }
        vm.playingAlbumArtDrawable.observe(viewLifecycleOwner) { drawable ->
            card.imgPlayingAlbumArt.setImageDrawable(drawable)
        }

        fragViewModel.showPlayingTitle.observe(viewLifecycleOwner) { show ->
            card.txtPlayingTitle.visibility = if (show == true) View.VISIBLE else View.GONE
        }
        vm.playingTitle.observe(viewLifecycleOwner) { card.txtPlayingTitle.text = it }

        fragViewModel.showPlayingText.observe(viewLifecycleOwner) { show ->
            card.txtPlayingText.visibility = if (show == true) View.VISIBLE else View.GONE
        }
        vm.playingText.observe(viewLifecycleOwner) { card.txtPlayingText.text = it }

        fragViewModel.showPlayingSubText.observe(viewLifecycleOwner) { show ->
            card.txtPlayingSubText.visibility = if (show == true) View.VISIBLE else View.GONE
        }
        vm.playingSubText.observe(viewLifecycleOwner) { card.txtPlayingSubText.text = it }

        fragViewModel.playingProgressReadable.observe(viewLifecycleOwner) {
            card.txtPlayingProgress.text = it
        }

        card.btnOpenMusicAppEmpty.setOnClickListener { openMusicApp() }
        card.btnOpenMusicAppPlaying.setOnClickListener { openMusicApp() }
        card.btnShareScreenshot.setOnClickListener { openShareStyleDialog(it) }
    }

    private fun bindConfigCard() {
        val vm = ViewModelManager.viewModel
        val card = binding.cardConfig

        fragViewModel.showConfigTooltipHint.observe(viewLifecycleOwner) { show ->
            card.txtConfigTooltipHint.visibility = if (show == true) View.VISIBLE else View.GONE
        }

        // Fluid settings
        vm.enableFluidOverride.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbFluidSettings.isChecked != v) card.cbFluidSettings.isChecked = v
            card.layoutFluidSettings.visibility = if (v) View.VISIBLE else View.GONE
            card.lineFluid.root.visibility = if (v) View.VISIBLE else View.GONE
        }
        card.cbFluidSettings.setOnCheckedChangeListener { _, isChecked ->
            if (vm.enableFluidOverride.value != isChecked) {
                vm.enableFluidOverride.value = isChecked
            }
        }

        vm.noiseScale.observe(viewLifecycleOwner) { scale ->
            if (scale != null && card.sliderFluidScale.value != scale) {
                card.sliderFluidScale.value = scale.coerceIn(
                    card.sliderFluidScale.valueFrom,
                    card.sliderFluidScale.valueTo
                )
            }
        }
        card.sliderFluidScale.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) vm.noiseScale.value = value
        })

        val syncSpeedSlider = {
            val min = (vm.noiseSpeedMin.value ?: 0.05f).coerceIn(
                card.fluidSpeedSlider.valueFrom,
                card.fluidSpeedSlider.valueTo
            )
            val max = (vm.noiseSpeedMax.value ?: 0.5f).coerceIn(
                card.fluidSpeedSlider.valueFrom,
                card.fluidSpeedSlider.valueTo
            )
            val safeMin = min.coerceAtMost(max)
            val currentVals = card.fluidSpeedSlider.values
            if (currentVals.size != 2 || currentVals[0] != safeMin || currentVals[1] != max) {
                card.fluidSpeedSlider.setValues(safeMin, max)
            }
        }
        vm.noiseSpeedMin.observe(viewLifecycleOwner) { syncSpeedSlider() }
        vm.noiseSpeedMax.observe(viewLifecycleOwner) { syncSpeedSlider() }
        card.fluidSpeedSlider.addOnChangeListener(RangeSlider.OnChangeListener { slider, _, _ ->
            val values = slider.values
            if (values.size >= 2) {
                if (vm.noiseSpeedMin.value != values[0]) vm.noiseSpeedMin.value = values[0]
                if (vm.noiseSpeedMax.value != values[1]) vm.noiseSpeedMax.value = values[1]
            }
        })

        // Graphics settings
        vm.enableGraphicsOverride.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbGraphicsSettings.isChecked != v) card.cbGraphicsSettings.isChecked = v
            card.layoutGraphicsSettings.visibility = if (v) View.VISIBLE else View.GONE
            card.lineGraphics.root.visibility = if (v) View.VISIBLE else View.GONE
        }
        card.cbGraphicsSettings.setOnCheckedChangeListener { _, isChecked ->
            if (vm.enableGraphicsOverride.value != isChecked) {
                vm.enableGraphicsOverride.value = isChecked
            }
        }

        vm.postProcessingEffectMode.observe(viewLifecycleOwner) {
            val desiredId = vm.getPostProcessRadioId()
            if (card.rgPostProcess.checkedRadioButtonId != desiredId) {
                card.rgPostProcess.check(desiredId)
            }
        }
        card.rgPostProcess.setOnCheckedChangeListener { _, checkedId ->
            vm.setPostProcessEffectFromRadioId(checkedId)
        }

        // Live Beats
        fragViewModel.enableLiveBeatsCheckBox.observe(viewLifecycleOwner) { enabled ->
            card.cbLiveBeats.isEnabled = enabled == true
        }
        vm.isAudioPermGranted.observe(viewLifecycleOwner) { granted ->
            card.cbLiveBeats.text = getString(
                if (granted == true) R.string.button_live_beats
                else R.string.button_live_beats_need_permission
            )
        }
        vm.enableLiveBeats.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbLiveBeats.isChecked != v) card.cbLiveBeats.isChecked = v
            card.layoutLiveBeats.visibility = if (v) View.VISIBLE else View.GONE
            card.lineLiveBeats.root.visibility = if (v) View.VISIBLE else View.GONE
        }
        card.cbLiveBeats.setOnCheckedChangeListener { _, isChecked ->
            if (vm.enableLiveBeats.value != isChecked) {
                vm.enableLiveBeats.value = isChecked
            }
        }

        vm.liveBeatsStrength.observe(viewLifecycleOwner) { strength ->
            if (strength != null && card.sliderLiveBeatsStrength.value != strength) {
                card.sliderLiveBeatsStrength.value = strength.coerceIn(
                    card.sliderLiveBeatsStrength.valueFrom,
                    card.sliderLiveBeatsStrength.valueTo
                )
            }
        }
        card.sliderLiveBeatsStrength.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) vm.liveBeatsStrength.value = value
        })

        // Preserve state / Album art settings
        vm.enablePreserveState.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbPreserveState.isChecked != v) card.cbPreserveState.isChecked = v
            card.layoutPreserveState.visibility = if (v) View.VISIBLE else View.GONE
            card.linePreserveState.root.visibility = if (v) View.VISIBLE else View.GONE
        }
        card.cbPreserveState.setOnCheckedChangeListener { _, isChecked ->
            if (vm.enablePreserveState.value != isChecked) {
                vm.enablePreserveState.value = isChecked
            }
        }

        vm.preserveStateAfterRestart.observe(viewLifecycleOwner) { v ->
            val checked = v == true
            if (card.cbPreserveAfterRestart.isChecked != checked) {
                card.cbPreserveAfterRestart.isChecked = checked
            }
        }
        card.cbPreserveAfterRestart.setOnCheckedChangeListener { _, isChecked ->
            if (vm.preserveStateAfterRestart.value != isChecked) {
                vm.preserveStateAfterRestart.value = isChecked
            }
        }

        vm.preserveStateAfterPause.observe(viewLifecycleOwner) { v ->
            val checked = v == true
            if (card.cbPreserveAfterPause.isChecked != checked) {
                card.cbPreserveAfterPause.isChecked = checked
            }
        }
        card.cbPreserveAfterPause.setOnCheckedChangeListener { _, isChecked ->
            if (vm.preserveStateAfterPause.value != isChecked) {
                vm.preserveStateAfterPause.value = isChecked
            }
        }

        card.btnSelectDefaultPalette.setOnClickListener {
            startActivity(Intent(requireActivity(), SelectDefaultPaletteActivity::class.java))
        }

        // Misc settings
        vm.enableMiscOverride.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbMiscSettings.isChecked != v) card.cbMiscSettings.isChecked = v
            card.layoutMiscSettings.visibility = if (v) View.VISIBLE else View.GONE
            card.lineMisc.root.visibility = if (v) View.VISIBLE else View.GONE
        }
        card.cbMiscSettings.setOnCheckedChangeListener { _, isChecked ->
            if (vm.enableMiscOverride.value != isChecked) {
                vm.enableMiscOverride.value = isChecked
            }
        }

        vm.supportsWallpaperColorApi.observe(viewLifecycleOwner) { supported ->
            card.cbWallpaperColorApi.visibility = if (supported == true) View.VISIBLE else View.GONE
        }
        vm.enableWallpaperColorAPI.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbWallpaperColorApi.isChecked != v) card.cbWallpaperColorApi.isChecked = v
            card.layoutBrightness.visibility = if (v) View.GONE else View.VISIBLE
        }
        card.cbWallpaperColorApi.setOnCheckedChangeListener { _, isChecked ->
            if (vm.enableWallpaperColorAPI.value != isChecked) {
                vm.enableWallpaperColorAPI.value = isChecked
            }
        }

        vm.brightnessOffset.observe(viewLifecycleOwner) { brightness ->
            if (brightness != null && card.sliderBrightness.value != brightness) {
                card.sliderBrightness.value = brightness.coerceIn(
                    card.sliderBrightness.valueFrom,
                    card.sliderBrightness.valueTo
                )
            }
        }
        card.sliderBrightness.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            if (vm.brightnessOffset.value != value) {
                vm.brightnessOffset.value = value
            }
        })

        vm.saturation.observe(viewLifecycleOwner) { sat ->
            if (sat != null) {
                val snapped = (Math.round(sat * 20f) / 20f).coerceIn(
                    card.sliderSaturation.valueFrom,
                    card.sliderSaturation.valueTo
                )
                if (card.sliderSaturation.value != snapped) {
                    card.sliderSaturation.value = snapped
                }
            }
        }
        card.sliderSaturation.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser && vm.saturation.value != value) {
                vm.saturation.value = value
            }
        })

        vm.hdrMode.observe(viewLifecycleOwner) { mode ->
            val desiredId = vm.getHdrRadioId()
            if (card.rgHdrMode.checkedRadioButtonId != desiredId) {
                card.rgHdrMode.check(desiredId)
            }
            card.layoutHdrIntensity.visibility = if (mode == "off") View.GONE else View.VISIBLE
        }
        card.rgHdrMode.setOnCheckedChangeListener { _, checkedId ->
            vm.setHdrModeFromRadioId(checkedId)
        }

        vm.hdrIntensity.observe(viewLifecycleOwner) { intensity ->
            if (intensity != null) {
                val snapped = (Math.round(intensity * 20f) / 20f).coerceIn(
                    card.sliderHdrIntensity.valueFrom,
                    card.sliderHdrIntensity.valueTo
                )
                if (card.sliderHdrIntensity.value != snapped) {
                    card.sliderHdrIntensity.value = snapped
                }
            }
        }
        card.sliderHdrIntensity.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser && vm.hdrIntensity.value != value) {
                vm.hdrIntensity.value = value
            }
        })

        // Performance mode & Debug view
        vm.enablePerformanceMode.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbPerformanceMode.isChecked != v) card.cbPerformanceMode.isChecked = v
        }
        card.cbPerformanceMode.setOnClickListener { togglePerformanceMode() }

        vm.enableDebugMode.observe(viewLifecycleOwner) { enabled ->
            val v = enabled == true
            if (card.cbDebugView.isChecked != v) card.cbDebugView.isChecked = v
        }
        card.cbDebugView.setOnCheckedChangeListener { _, isChecked ->
            if (vm.enableDebugMode.value != isChecked) {
                vm.enableDebugMode.value = isChecked
            }
        }
    }

    private fun bindDebugCard() {
        val vm = ViewModelManager.viewModel
        val card = binding.cardDebug
        vm.enableDebugMode.observe(viewLifecycleOwner) { enabled ->
            card.root.visibility = if (enabled == true) View.VISIBLE else View.GONE
        }
        card.btnSendLogs.setOnClickListener { sendLogs() }
    }

    private fun bindExperimentalCard() {
        val vm = ViewModelManager.viewModel
        val card = binding.cardExperimental
        val updateVisibility = {
            val playing = DiffuseUtils.getValueOrDefault(vm.isPlaying, false)
            val online = DiffuseUtils.getValueOrDefault(vm.engineOnline, false)
            val visible = playing && online && DiffuseUtils.hasAudioPermissions() && DiffuseUtils.isAtLeastApi(29)
            card.btnShareVideos.visibility = if (visible) View.VISIBLE else View.GONE
        }
        vm.isPlaying.observe(viewLifecycleOwner) { updateVisibility() }
        vm.engineOnline.observe(viewLifecycleOwner) { updateVisibility() }
        card.btnShareVideos.setOnClickListener { openScreenRecordDialog(it) }
    }

    private fun bindAboutCard() {
        val vm = ViewModelManager.viewModel
        val card = binding.cardAbout
        vm.appVersionString.observe(viewLifecycleOwner) { card.txtAppVersion.text = it }
        card.txtAppVersion.setOnClickListener { debugTapSubject.onNext(Date()) }
        card.btnFeedback.setOnClickListener {
            startActivity(Intent(requireActivity(), FeedbackActivity::class.java))
        }
        card.btnOtherApps.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_author_store))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
        card.btnAcknowledgement.setOnClickListener { view ->
            val dialogView = AcknowledgementDialogView(
                view.context,
                object : AcknowledgementDialogView.Listener {
                    override fun onHelpTranslateClick() {
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(getString(R.string.crowdin_url))
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            DiffuseUtils.logError(e)
                        }
                    }
                }
            )
            MaterialAlertDialogBuilder(view.context).setView(dialogView).create().show()
        }
    }

    private fun bindFaqCard() {
        val card = binding.cardFaq
        card.btnRestartListener.setOnClickListener {
            DiffuseUtils.restartNotificationListener()
            ToastManager.show(
                getString(R.string.label_done_restart_notification_listener),
                longDuration = true
            )
        }
        card.btnTroubleshoot.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_troubleshoot))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun openNotificationListenerSettings(view: View) {
        Toast.makeText(
            view.context,
            R.string.toast_enable_diffuse_access_notification,
            Toast.LENGTH_SHORT
        ).show()
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val componentStr =
            "${DiffuseApplication.appContext().packageName}/${NotificationPeekService::class.java.name}"
        val bundle = Bundle().apply {
            putString(":settings:fragment_args_key", componentStr)
        }
        try {
            intent.putExtra(":settings:fragment_args_key", componentStr)
            intent.putExtra(":settings:show_fragment_args", bundle)
            startActivity(intent)
        } catch (e: Exception) {
            DiffuseUtils.logError(e)
            try {
                startActivity(intent)
            } catch (e2: Exception) {
                DiffuseUtils.logError(e2)
            }
        }
    }

    private fun openLiveWallpaperPicker(view: View) {
        val ctx = view.context
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(ctx, LiveWallpaperService::class.java)
            )
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            DiffuseUtils.logError(e)
            try {
                val fallback = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                ctx.startActivity(fallback)
            } catch (e2: Exception) {
                DiffuseUtils.logError(e2)
            }
        }
    }

    private fun openMusicApp() {
        try {
            val vm = ViewModelManager.viewModel
            val playing = DiffuseUtils.getValueOrDefault(vm.isPlaying, false)
            if (!playing) {
                startActivity(Intent(requireActivity(), LaunchMusicAppsActivity::class.java))
                return
            }
            val pkg = vm.playingPackageName.value ?: return
            val pm = DiffuseApplication.appContext().packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: return
            if (launchIntent.resolveActivity(pm) != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            DiffuseUtils.logError(e)
        }
    }

    private fun openShareStyleDialog(view: View) {
        val vm = ViewModelManager.viewModel
        val bitmap = DiffuseUtils.getValueOrDefault(vm.playingAlbumArt, null) ?: run {
            DiffuseUtils.logRemoteError("albumArt null")
            return
        }
        val title = DiffuseUtils.getValueOrDefault(vm.playingTitle, "")
        val text = DiffuseUtils.getValueOrDefault(vm.playingText, "")

        val dialog = MaterialAlertDialogBuilder(view.context)
            .setTitle(R.string.label_choose_share_style)
            .setMessage(R.string.label_share_style_desc)
            .setPositiveButton(R.string.button_cancel) { _, _ -> }
            .create()

        dialog.setView(
            ShareStyleDialogView(
                view.context,
                object : ShareStyleDialogView.Listener {
                    override fun onStyleSelected(includeExtraInfo: Boolean) {
                        dialog.dismiss()
                        ToastManager.show(getString(R.string.label_generating_album), false)
                        GdxPlayerManager.getScreenshot(object : GdxPlayerManager.ScreenshotCallback {
                            override fun onScreenshotSaved(path: String) {
                                DiffuseUtils.logDebug("GdxPlayerManager.INSTANCE.getScreenshot -> $path")
                                AlbumShareTask(requireActivity()).execute(
                                    AlbumShareTask.Params(path, bitmap, title, text, includeExtraInfo)
                                )
                            }
                        })
                    }
                }
            )
        )
        dialog.show()
    }

    private fun openScreenRecordDialog(view: View) {
        val intent = Intent(view.context, ScreenRecordActivity::class.java)
        MaterialAlertDialogBuilder(view.context)
            .setTitle(R.string.label_choose_audio_source_for_recording)
            .setMessage(R.string.label_choose_audio_source_for_recording_desc)
            .setPositiveButton(R.string.label_mic_audio) { _, _ ->
                intent.putExtra("useMic", true)
                startActivity(intent)
            }
            .setNegativeButton(R.string.label_device_audio) { _, _ ->
                intent.putExtra("useMic", false)
                startActivity(intent)
            }
            .show()
    }

    private fun sendLogs() {
        val logs = DiffuseUtils.readLogcat()
        val app = DiffuseApplication.appContext()
        val file = File(app.cacheDir, app.getString(R.string.local_adb_log_file_name))
        try {
            FileOutputStream(file, false).use { fos ->
                PrintStream(fos).use { ps ->
                    ps.println(logs)
                }
                fos.flush()
            }
            val uri = FileProvider.getUriForFile(
                app,
                app.getString(R.string.file_provider_authority),
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.support_email)))
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.label_diffuse_logs))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.label_send_log_notice))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.send_support_email)))
            ToastManager.show(getString(R.string.select_email_client_send_log), false)
        } catch (e: Exception) {
            DiffuseUtils.logError(e)
            ToastManager.show(getString(R.string.label_error_getting_logs), true)
        }
    }

    private fun togglePerformanceMode() {
        val vm = ViewModelManager.viewModel
        val current = DiffuseUtils.getValueOrDefault(vm.enablePerformanceMode, false)
        ToastManager.show(
            DiffuseApplication.appContext().getString(
                if (!current) R.string.toast_performance_mode_enabled
                else R.string.toast_performance_mode_disabled
            ),
            longDuration = true
        )
        vm.enablePerformanceMode.value = !current
    }

    @AfterPermissionGranted(RC_AUDIO_PERMS)
    fun requestAudioPermissions() {
        if (DiffuseUtils.hasAudioPermissions(context ?: return)) {
            ViewModelManager.viewModel.refresh()
        } else {
            EasyPermissions.requestPermissions(
                PermissionRequest.Builder(
                    this,
                    RC_AUDIO_PERMS,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.RECORD_AUDIO
                )
                    .setRationale(R.string.label_audio_permission_rationale)
                    .setPositiveButtonText(R.string.button_agree)
                    .setNegativeButtonText(R.string.button_cancel)
                    .build()
            )
        }
    }
}
