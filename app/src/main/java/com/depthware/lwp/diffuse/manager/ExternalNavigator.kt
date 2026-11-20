package com.depthware.lwp.diffuse.manager

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.depthware.lwp.diffuse.v2.R

object ExternalNavigator {

    private fun openUrlRes(@StringRes urlResId: Int) {
        val ctx = DiffuseApplication.appContext()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ctx.getString(urlResId))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            DiffuseUtils.logError(e)
        }
    }

    fun openAuthorHomepage() = openUrlRes(R.string.url_author_homepage)

    fun openCuratedPlaylist() = openUrlRes(R.string.url_playlist)

    fun openGitHubRepo() = openUrlRes(R.string.url_github_repo)

    fun openGitHubIssues() = openUrlRes(R.string.url_github_issues)

    fun openCommunityDiscord() = openUrlRes(R.string.url_community_discord)

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
