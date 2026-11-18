package com.depthware.lwp.diffuse.viewModel

import android.os.Build
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.util.DiffuseUtils

class MainFragmentViewModel : ViewModel() {

    val enableLiveBeatsCheckBox = MediatorLiveData<Boolean>()
    val showSetWallpaperButton = MediatorLiveData<Boolean>()
    val showSetNotificationButton = MediatorLiveData<Boolean>()
    val showAudioPermissionButton = MediatorLiveData<Boolean>()
    val showSetupPanel = MediatorLiveData<Boolean>()
    val showPlayingTitle = MediatorLiveData<Boolean>()
    val showPlayingText = MediatorLiveData<Boolean>()
    val showPlayingSubText = MediatorLiveData<Boolean>()
    val showPlayingAlbumArt = MediatorLiveData<Boolean>()
    val playingProgressReadable = MediatorLiveData<String>()
    val showConfigTooltipHint = MutableLiveData(Build.VERSION.SDK_INT >= 26)

    init {
        val vm = ViewModelManager.viewModel
        enableLiveBeatsCheckBox.addSource(vm.isAudioPermGranted) { enableLiveBeatsCheckBox.value = it }
        showAudioPermissionButton.addSource(vm.isAudioPermGranted) { showAudioPermissionButton.value = !it }
        showSetNotificationButton.addSource(vm.isNotificationGranted) { showSetNotificationButton.value = !it }
        showSetWallpaperButton.addSource(vm.isWallpaperActive) { showSetWallpaperButton.value = !it }

        val updateSetupPanel = {
            showSetupPanel.value =
                DiffuseUtils.getValueOrDefault(showSetWallpaperButton, false) ||
                    DiffuseUtils.getValueOrDefault(showSetNotificationButton, false) ||
                    DiffuseUtils.getValueOrDefault(showAudioPermissionButton, false)
        }
        showSetupPanel.addSource(showSetWallpaperButton) { updateSetupPanel() }
        showSetupPanel.addSource(showSetNotificationButton) { updateSetupPanel() }
        showSetupPanel.addSource(showAudioPermissionButton) { updateSetupPanel() }

        showPlayingTitle.addSource(vm.playingTitle) {
            showPlayingTitle.value = !it.isNullOrEmpty()
        }
        showPlayingText.addSource(vm.playingText) {
            showPlayingText.value = !it.isNullOrEmpty()
        }
        showPlayingSubText.addSource(vm.playingSubText) {
            showPlayingSubText.value = !it.isNullOrEmpty()
        }
        showPlayingAlbumArt.addSource(vm.playingAlbumArtDrawable) {
            showPlayingAlbumArt.value = it != null
        }
        playingProgressReadable.addSource(vm.playingProgress) {
            val ms = DiffuseUtils.getValueOrDefault(vm.playingProgress, 0L)
            playingProgressReadable.value = DiffuseUtils.formatDurationMmSs(ms)
        }
    }
}
