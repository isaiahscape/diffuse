package com.depthware.lwp.diffuse.work

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.manager.ViewModelManager
import com.depthware.lwp.diffuse.service.NotificationPeekService
import com.depthware.lwp.diffuse.util.DiffuseUtils

class NotificationRegulatorWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    @Suppress("DEPRECATION")
    override fun doWork(): Result {
        val app = DiffuseApplication.appContext()
        val collectorComponent = ComponentName(app, NotificationPeekService::class.java)
        DiffuseUtils.logDebug("NotificationRegulatorWorker collectorComponent: $collectorComponent")

        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager == null) {
            DiffuseUtils.logRemoteError("ActivityManager is NULL")
            return Result.failure()
        }

        val runningServices = activityManager.getRunningServices(Int.MAX_VALUE)
        if (runningServices == null) {
            DiffuseUtils.logRemoteError("NotificationRegulatorWorker runningServices is NULL")
            return Result.failure()
        }

        var collectorRunning = false
        for (info in runningServices) {
            if (info.service == collectorComponent) {
                val labelStr = if (info.clientLabel == 0) {
                    "0"
                } else {
                    "(${app.resources.getString(info.clientLabel)})"
                }
                DiffuseUtils.logDebug(
                    "NotificationRegulatorWorker service - pid: ${info.pid}, " +
                        "currentPID: ${Process.myPid()}, clientPackage: ${info.clientPackage}, " +
                        "clientCount: ${info.clientCount}, clientLabel: $labelStr"
                )
                if (info.pid == Process.myPid()) {
                    collectorRunning = true
                }
            }
        }

        if (collectorRunning) {
            DiffuseUtils.logDebug("ensureCollectorRunning: peek service is running, return")
            return Result.success()
        }

        if (!DiffuseUtils.getValueOrDefault(ViewModelManager.viewModel.isNotificationGranted, false)) {
            return Result.success()
        }

        DiffuseUtils.logRemoteError("ensureCollectorRunning: peek service not running, reviving...")
        DiffuseUtils.restartNotificationListener()
        return Result.retry()
    }
}
