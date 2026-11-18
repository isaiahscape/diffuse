package com.depthware.lwp.diffuse.activity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.recorder.RecorderCallback
import com.depthware.lwp.diffuse.service.ScreenRecordForegroundService
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R
import com.depthware.lwp.diffuse.v2.databinding.LayoutScreenRecordActivityBinding
import com.depthware.lwp.diffuse.viewModel.ScreenRecordViewModel
import java.io.File

class ScreenRecordActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 0
        private const val RECORD_SECONDS = 8
    }

    private lateinit var binding: LayoutScreenRecordActivityBinding
    private val screenRecordViewModel: ScreenRecordViewModel by viewModels()

    private var recordService: ScreenRecordForegroundService? = null
    private var serviceBound = false
    private var recordFinished = false

    private val serviceConnection = object : ServiceConnection {
        override fun onBindingDied(name: ComponentName?) {
            DiffuseUtils.logDebug("onBindingDied")
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            DiffuseUtils.logDebug("onServiceConnected")
            val binder = service as ScreenRecordForegroundService.LocalBinder
            val svc = binder.getService()
            recordService = svc
            serviceBound = true
            DiffuseUtils.setLiveDataValue(screenRecordViewModel.totalTimeMs, 8000L)
            svc.setRecorderCallback(object : RecorderCallback {
                override fun onRecordSuccess(videoPath: String, coverPath: String?, durationMs: Long) {
                    recordFinished = true
                    DiffuseUtils.logDebug("onRecordSuccess $videoPath cover $coverPath duration $durationMs")
                    unbindService(this@ScreenRecordActivity.serviceConnection)
                    serviceBound = false
                    val file = File(videoPath)
                    if (!file.exists()) {
                        DiffuseUtils.logRemoteError("file not exist at $videoPath")
                        finish()
                        return
                    }
                    val app = DiffuseApplication.appContext()
                    val uri = FileProvider.getUriForFile(
                        app,
                        app.getString(R.string.file_provider_authority),
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        setDataAndType(uri, "video/mp4")
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.label_share_from_diffuse)))
                }

                override fun onRecordError(error: Throwable, durationMs: Long) {
                    recordFinished = true
                    DiffuseUtils.logError(error)
                    error.printStackTrace()
                }

                override fun onRecordProgress(elapsedMs: Long) {
                    DiffuseUtils.setLiveDataValue(screenRecordViewModel.currentTimeMs, elapsedMs)
                }
            })
            svc.startTimedRecording()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DiffuseUtils.logDebug("onServiceDisconnected")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK) {
                finish()
                return
            }
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val rect = binding.transparentHoleView.getCutViewRect()
            val svcIntent = Intent(this, ScreenRecordForegroundService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("width", metrics.widthPixels)
                putExtra("height", metrics.heightPixels)
                putExtra("useMic", intent.getBooleanExtra("useMic", false))
                putExtra("recordSeconds", RECORD_SECONDS)
                putExtra(
                    "cutRect",
                    intArrayOf(rect.left + 8, rect.top + 8, rect.right - 8, rect.bottom - 8)
                )
            }
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or 1792

        binding = LayoutScreenRecordActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.transparentHoleView.setCutView(binding.transparentHole)

        val vm = ViewModelManager.viewModel
        vm.playingAlbumArtDrawable.observe(this) { binding.imgRecordAlbumArt.setImageDrawable(it) }
        vm.playingTitle.observe(this) { binding.txtRecordTitle.text = it }
        vm.playingText.observe(this) { binding.txtRecordSubtitle.text = it }

        val updateProgress = {
            binding.txtRecordProgress.text =
                "${screenRecordViewModel.currentTimeMs.value} / ${screenRecordViewModel.totalTimeMs.value}"
        }
        screenRecordViewModel.currentTimeMs.observe(this) { updateProgress() }
        screenRecordViewModel.totalTimeMs.observe(this) { updateProgress() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.transparentHoleView.requestLayout()
        binding.transparentHoleView.invalidate()
        if (recordFinished) {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            serviceBound = false
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                DiffuseUtils.logError(e)
            }
        }
    }
}
