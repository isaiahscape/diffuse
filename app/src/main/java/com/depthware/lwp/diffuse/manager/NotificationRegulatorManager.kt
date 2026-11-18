package com.depthware.lwp.diffuse.manager

import androidx.work.BackoffPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.work.NotificationRegulatorWorker
import java.util.concurrent.TimeUnit

object NotificationRegulatorManager {
    private const val WORK_TAG = "NotificationRegulator"

    fun schedule() {
        val wm = WorkManager.getInstance(DiffuseApplication.appContext())
        wm.cancelAllWorkByTag(WORK_TAG)
        val request = PeriodicWorkRequestBuilder<NotificationRegulatorWorker>(10L, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5L, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        wm.enqueue(request)
    }
}
