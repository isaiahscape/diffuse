package com.depthware.lwp.diffuse.util

import android.Manifest
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.depthware.lwp.diffuse.application.DiffuseApplication
import com.depthware.lwp.diffuse.service.NotificationPeekService
import com.orhanobut.logger.Logger
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder
import pub.devrel.easypermissions.EasyPermissions
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

object DiffuseUtils {

    fun convertRectRelativeTo(rect: Rect, sourceView: View, targetView: View): Rect {
        val srcLoc = IntArray(2)
        val dstLoc = IntArray(2)
        sourceView.getLocationOnScreen(srcLoc)
        targetView.getLocationOnScreen(dstLoc)
        val dx = srcLoc[0] - dstLoc[0]
        val dy = srcLoc[1] - dstLoc[1]
        return Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } else {
            Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
        }
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun getApplicationInfo(packageName: String, packageManager: PackageManager): ApplicationInfo? =
        try {
            packageManager.getPackageInfo(packageName, 0).applicationInfo
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    fun getFilesDir(): File = DiffuseApplication.appContext().filesDir

    fun readLogcat(): String? = try {
        val reader = BufferedReader(
            InputStreamReader(Runtime.getRuntime().exec("logcat -t 1000 -v time").inputStream)
        )
        buildString {
            var line = reader.readLine()
            while (line != null) {
                append(line).append('\n')
                line = reader.readLine()
            }
        }
    } catch (_: IOException) {
        null
    }

    fun <T> getValueOrDefault(liveData: LiveData<T>, defaultValue: T): T =
        liveData.value ?: defaultValue

    fun hasAudioPermissions(): Boolean = hasAudioPermissions(DiffuseApplication.appContext())

    fun hasAudioPermissions(context: Context): Boolean = EasyPermissions.hasPermissions(
        context,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.RECORD_AUDIO
    )

    fun isNotificationAccessGranted(context: Context): Boolean {
        val app = DiffuseApplication.appContext()
        if (Build.VERSION.SDK_INT >= 27) {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                return nm.isNotificationListenerAccessGranted(
                    ComponentName(app, NotificationPeekService::class.java)
                )
            }
        }
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun resourceExists(context: Context?, resId: Int): Boolean {
        if (context == null) return false
        return try {
            context.resources.getResourceName(resId) != null
        } catch (e: Resources.NotFoundException) {
            logError(e, report = false)
            false
        }
    }

    @Suppress("DEPRECATION")
    fun hasMediaSessionToken(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification?.extras ?: return false
        return extras.containsKey("android.mediaSession") &&
            extras.getParcelable<MediaSession.Token>("android.mediaSession") != null
    }

    /**
     * Original APK predicate (`!sbn.isClearable && sbn.isOngoing`). Modern Android media controls
     * (Android 13+) are often dismissible, so callers should rely on [hasMediaSessionToken].
     */
    fun isOngoingNonClearable(sbn: StatusBarNotification): Boolean =
        !sbn.isClearable && sbn.isOngoing

    fun isAtLeastApi(api: Int): Boolean = Build.VERSION.SDK_INT >= api

    fun isDiffuseWallpaperActive(context: Context): Boolean {
        val info = WallpaperManager.getInstance(context).wallpaperInfo
        return info != null && info.packageName == context.packageName
    }

    fun loadBitmap(dir: File, fileName: String): Bitmap? = try {
        val file = File(dir, fileName)
        if (file.exists()) {
            FileInputStream(file).use { BitmapFactory.decodeStream(it) }
        } else {
            null
        }
    } catch (e: Exception) {
        logError(e)
        null
    }

    fun saveBitmapJpeg(dir: File, fileName: String, bitmap: Bitmap): Boolean {
        val file = File(dir, fileName)
        return try {
            FileOutputStream(file, false).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    fun <T> setLiveDataValue(liveData: MutableLiveData<T>, value: T, post: Boolean = false) {
        if (liveData.value !== value) {
            if (post) {
                liveData.postValue(value)
            } else {
                liveData.value = value
            }
        }
    }

    fun restartNotificationListener() {
        val app = DiffuseApplication.appContext()
        val component = ComponentName(app, NotificationPeekService::class.java)
        val pm = app.packageManager
        try {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            NotificationListenerService.requestRebind(component)
        } catch (e: Exception) {
            logError(e)
            logRemoteError(e.toString())
        } finally {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun logDebug(message: String) {
        Logger.d(message)
    }

    fun logRemoteError(message: String) {
        Logger.e(message)
    }

    fun logError(throwable: Throwable?, report: Boolean = true) {
        if (throwable == null) return
        Logger.e(throwable, throwable.message ?: "Error")
    }

    fun logWarning(message: String) {
        Logger.w(message)
    }

    fun formatDurationMmSs(millis: Long): String {
        val formatter = PeriodFormatterBuilder()
            .printZeroAlways()
            .minimumPrintedDigits(2)
            .appendMinutes()
            .appendSeparator(":")
            .appendSeconds()
            .toFormatter()
        return formatter.print(Duration(millis).toPeriod())
    }

    fun dpToPx(context: Context, dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
