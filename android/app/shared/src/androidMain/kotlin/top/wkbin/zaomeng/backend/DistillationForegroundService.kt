package top.wkbin.zaomeng.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import top.wkbin.zaomeng.app.shared.R
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.RunManifestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 蒸馏前台服务：内嵌后端 + 仓库轮询运行中任务，展示进度通知并支持“停止全部”。 */
class DistillationForegroundService : Service(), KoinComponent {
    private val backend: BackendController by inject()
    private val repository: ZaomengRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    @Volatile private var stopRequested = false
    private val observedRunIds = linkedSetOf<String>()
    private var observedRunning = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            if (!startAsForeground(buildStoppingNotification())) {
                stopSelf()
                return START_NOT_STICKY
            }
            stopRequested = true
            if (monitorJob?.isActive != true) {
                monitorJob = serviceScope.launch { monitorRunningDistillations() }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP_MONITORING) {
            finishMonitoring()
            return START_NOT_STICKY
        }

        if (!startAsForeground(buildStartingNotification())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch { monitorRunningDistillations() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        finishMonitoring()
    }

    override fun onDestroy() {
        monitorJob = null
        serviceScope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun monitorRunningDistillations() {
        while (serviceScope.isActive) {
            try {
                when (backend.state.value) {
                    is BackendState.Failed -> backend.retry()
                    else -> backend.start()
                }
                val allRuns = repository.listRuns()
                val running = allRuns
                    .filter { it.status == RUNNING_STATUS }
                if (running.isNotEmpty()) {
                    observedRunning = true
                    observedRunIds += running.map(RunManifestDto::runId)
                    refreshWakeLock()
                }
                if (stopRequested && running.isNotEmpty()) {
                    updateNotification(buildStoppingNotification())
                    running.forEach { run -> repository.stopRun(run.runId) }
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                if (running.isEmpty()) {
                    if (observedRunning) {
                        publishResultNotification(allRuns, wasStopped = stopRequested)
                    }
                    finishMonitoring()
                    return
                }
                updateNotification(buildProgressNotification(running))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateNotification(buildReconnectNotification())
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun buildStartingNotification(): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.distillation_notification_starting_title))
            .setContentText(getString(R.string.distillation_notification_starting_text))
            .setProgress(0, 0, true)
            .build()

    private fun buildReconnectNotification(): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.distillation_notification_running_title))
            .setContentText(getString(R.string.distillation_notification_reconnecting))
            .setProgress(0, 0, true)
            .build()

    private fun buildStoppingNotification(): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.distillation_notification_stopping_title))
            .setContentText(getString(R.string.distillation_notification_stopping_text))
            .setProgress(0, 0, true)
            .build()

    private fun buildProgressNotification(runs: List<RunManifestDto>): Notification {
        val primary = runs.first()
        val total = runs.sumOf { maxOf(it.progress.totalCharacters, it.lockedCharacters.size) }
        val completed = runs.sumOf { maxOf(it.progress.completedCount, it.availableCharacters.size) }
            .coerceAtMost(total.coerceAtLeast(0))
        val title = if (runs.size == 1) {
            getString(R.string.distillation_notification_single_title, primary.title)
        } else {
            getString(R.string.distillation_notification_multiple_title, runs.size)
        }
        val text = primary.progress.message.ifBlank {
            primary.progress.currentCharacter
                .takeIf(String::isNotBlank)
                ?.let { getString(R.string.distillation_notification_character, it) }
                ?: getString(R.string.distillation_notification_running_text)
        }
        return notificationBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(total, completed, total <= 0)
            .build()
    }

    private fun publishResultNotification(allRuns: List<RunManifestDto>, wasStopped: Boolean) {
        val observedRuns = allRuns.filter { it.runId in observedRunIds }
        val failedCount = observedRuns.count { it.status == "failed" }
        val stopped = wasStopped || observedRuns.any { it.status == "stopped" }
        val title = when {
            failedCount > 0 -> getString(R.string.distillation_notification_failed_title)
            stopped -> getString(R.string.distillation_notification_stopped_title)
            else -> getString(R.string.distillation_notification_complete_title)
        }
        val text = when {
            failedCount > 0 -> getString(
                R.string.distillation_notification_failed_text,
                failedCount,
                observedRuns.size,
            )
            stopped -> getString(R.string.distillation_notification_stopped_result_text)
            observedRuns.size == 1 -> getString(
                R.string.distillation_notification_complete_single_text,
                observedRuns.firstOrNull()?.title.orEmpty(),
            )
            else -> getString(
                R.string.distillation_notification_complete_multiple_text,
                observedRuns.size,
            )
        }
        try {
            NotificationManagerCompat.from(this).notify(
                RESULT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, RESULT_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_distillation_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openAppPendingIntent())
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
        } catch (_: SecurityException) {
            // Android 13+ may deny notification permission while the task itself still finishes locally.
        }
    }

    private fun notificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_distillation_notification)
            .setContentIntent(openAppPendingIntent())
            .addAction(
                R.drawable.ic_distillation_notification,
                getString(R.string.distillation_notification_stop_action),
                stopAllPendingIntent(),
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent().setClassName(packageName, MAIN_ACTIVITY_CLASS_NAME).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopAllPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, DistillationForegroundService::class.java).setAction(ACTION_STOP_ALL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.distillation_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.distillation_notification_channel_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_NOTIFICATION_CHANNEL_ID,
                getString(R.string.distillation_notification_result_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.distillation_notification_result_channel_description)
                setShowBadge(true)
            },
        )
    }

    private fun startAsForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun updateNotification(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android still exposes foreground-service state when notification permission is denied.
        }
    }

    private fun finishMonitoring() {
        stopRequested = false
        monitorJob?.cancel()
        monitorJob = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshWakeLock() {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:distillation")
            .also { created ->
                created.setReferenceCounted(false)
                wakeLock = created
            }
        if (lock.isHeld) lock.release()
        lock.acquire(WAKE_LOCK_LEASE_MS)
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        wakeLock = null
    }

    companion object {
        internal const val ACTION_START_MONITORING =
            "top.wkbin.zaomeng.action.START_DISTILLATION_MONITORING"
        internal const val ACTION_STOP_MONITORING =
            "top.wkbin.zaomeng.action.STOP_DISTILLATION_MONITORING"
        internal const val ACTION_STOP_ALL =
            "top.wkbin.zaomeng.action.STOP_ALL_DISTILLATIONS"
        private const val MAIN_ACTIVITY_CLASS_NAME = "top.wkbin.zaomeng.app.MainActivity"
        private const val NOTIFICATION_CHANNEL_ID = "distillation_progress"
        private const val RESULT_NOTIFICATION_CHANNEL_ID = "distillation_result"
        private const val NOTIFICATION_ID = 4101
        private const val RESULT_NOTIFICATION_ID = 4102
        private const val RUNNING_STATUS = "running"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val WAKE_LOCK_LEASE_MS = 90_000L
    }
}

object DistillationForegroundController {
    const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    fun start(context: Context): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, DistillationForegroundService::class.java)
            .setAction(DistillationForegroundService.ACTION_START_MONITORING)
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, DistillationForegroundService::class.java))
    }

    fun stopAll(context: Context): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, DistillationForegroundService::class.java)
            .setAction(DistillationForegroundService.ACTION_STOP_ALL)
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
}
