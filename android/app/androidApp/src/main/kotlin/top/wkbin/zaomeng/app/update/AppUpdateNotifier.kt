package top.wkbin.zaomeng.app.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import top.wkbin.zaomeng.app.MainActivity
import top.wkbin.zaomeng.app.shared.R
import top.wkbin.zaomeng.data.update.AppUpdateInfo

/** 更新下载进度/完成通知（Android）。 */
class AppUpdateNotifier(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun showProgress(update: AppUpdateInfo, downloadedBytes: Long, totalBytes: Long) {
        if (!canPostNotifications()) return
        val percentage = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
        val detail = if (totalBytes > 0L) {
            "$percentage%  ${formatAppUpdateBytes(downloadedBytes)} / ${formatAppUpdateBytes(totalBytes)}"
        } else {
            "已下载 ${formatAppUpdateBytes(downloadedBytes)}"
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_distillation_notification)
                .setContentTitle("正在下载 ${update.version}")
                .setContentText(detail)
                .setProgress(100, percentage, totalBytes <= 0L)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(mainPendingIntent())
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun showCompleted(update: AppUpdateInfo) {
        if (!canPostNotifications()) return
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_distillation_notification)
                .setContentTitle("${update.version} 下载完成")
                .setContentText("返回应用安装更新")
                .setAutoCancel(true)
                .setContentIntent(mainPendingIntent())
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun clear() = notificationManager.cancel(NOTIFICATION_ID)

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun mainPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val CHANNEL_ID = "app_update"
        const val NOTIFICATION_ID = 41018
    }
}

internal fun formatAppUpdateBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "%.1f %s".format(Locale.US, value, units[unitIndex])
}
