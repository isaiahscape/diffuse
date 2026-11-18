package com.depthware.lwp.diffuse.manager

import android.app.Activity
import com.depthware.lwp.diffuse.util.DiffuseUtils
import com.google.android.play.core.review.ReviewManagerFactory

object ReviewManager {
    private const val SEVEN_DAYS_MS = 604_800_000L

    fun maybePromptReview(activity: Activity) {
        val vm = ViewModelManager.viewModel
        val alreadyRequested = DiffuseUtils.getValueOrDefault(vm.appReviewRequested, true)
        val installTimestamp = DiffuseUtils.getValueOrDefault(vm.recordedAppInstallTimeStamp, 0L)
        val elapsed = System.currentTimeMillis() - installTimestamp
        if (!alreadyRequested && elapsed > SEVEN_DAYS_MS && Math.random() < 0.1) {
            requestReview(activity)
        }
    }

    fun requestReview(activity: Activity) {
        DiffuseUtils.setLiveDataValue(ViewModelManager.viewModel.appReviewRequested, true)
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                manager.launchReviewFlow(activity, task.result).addOnCompleteListener {
                    DiffuseUtils.logDebug("launchReviewFlow task isSuccessful ${task.isSuccessful}")
                    ToastManager.showUpdated("Thank You!")
                }
            } else {
                task.exception?.let { DiffuseUtils.logError(it) }
            }
        }
    }
}
