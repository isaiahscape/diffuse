package com.depthware.lwp.diffuse.manager

import android.content.Intent
import android.net.Uri
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R

object ExternalNavigator {

    fun openAuthorHomepage() {
        val ctx = DiffuseApplication.appContext()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ctx.getString(R.string.url_author_homepage))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun openCuratedPlaylist() {
        val ctx = DiffuseApplication.appContext()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ctx.getString(R.string.url_playlist))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun openPlayStoreListing() {
        val ctx = DiffuseApplication.appContext()
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun sendSupportEmail() {
        val ctx = DiffuseApplication.appContext()
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("mailto", ctx.getString(R.string.support_email), null)
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.diffuse_troubleshooting))
        }
        try {
            val chooser = Intent.createChooser(intent, ctx.getString(R.string.send_support_email)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            ToastManager.show(
                ctx.getString(R.string.label_error_occurred_when_processing_email),
                longDuration = true
            )
            DiffuseUtils.logError(e)
        }
    }
}
