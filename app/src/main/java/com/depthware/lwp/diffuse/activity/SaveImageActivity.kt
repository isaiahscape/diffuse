package com.depthware.lwp.diffuse.activity

import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import com.depthware.lwp.diffuse.manager.ToastManager
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

class SaveImageActivity : AppCompatActivity() {

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        DiffuseUtils.logDebug("handleIntent $intent")
        if (intent == null) {
            finish()
            return
        }
        val path = intent.getStringExtra("path")
        DiffuseUtils.logDebug("Save $path")
        if (path != null) {
            val file = File(path)
            DiffuseUtils.logDebug("File $file ${file.exists()}")
            if (file.exists()) {
                try {
                    MediaStore.Images.Media.insertImage(
                        contentResolver,
                        path,
                        String.format(Locale.US, "diffuse-%s", System.currentTimeMillis()),
                        getString(R.string.save_image_desc)
                    )
                    ToastManager.show(getString(R.string.label_saved), longDuration = true)
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                    DiffuseUtils.logError(e)
                }
            } else {
                DiffuseUtils.logError(Exception("SaveImageActivity path not exist$path"))
            }
        } else {
            DiffuseUtils.logError(Exception("SaveImageActivity path == null"))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)
        handleIntent(intent)
    }
}
