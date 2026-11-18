package com.depthware.lwp.diffuse.viewModel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ScreenRecordViewModel : ViewModel() {
    val currentTimeMs = MutableLiveData(0L)
    val totalTimeMs = MutableLiveData(0L)
}
