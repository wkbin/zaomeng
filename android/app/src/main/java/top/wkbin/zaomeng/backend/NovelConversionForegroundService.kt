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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import top.wkbin.zaomeng.MainActivity
import top.wkbin.zaomeng.R
import top.wkbin.zaomeng.data.api.ArchiveDialogueChapterRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NovelConversionForegroundService : Service(), KoinComponent {
    private val backend: EmbeddedBackendController by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private val pendingConversions = ArrayDeque<ConversionRequest>()
    private var processorJob: Job? = null
    private var latestStartId = 0
    @Volatile private var pendingRunId = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getStringExtra(EXTRA_RUN_ID).orEmpty().trim()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty().trim()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().trim()
        if (runId.isBlank() || sessionId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val shouldStartProcessor = synchronized(queueLock) {
            pendingConversions.addLast(ConversionRequest(runId, sessionId, title))
            latestStartId = maxOf(latestStartId, startId)
            processorJob?.isActive != true
        }
        if (shouldStartProcessor) {
            pendingRunId = runId
            if (!startAsForeground(buildStartingNotification())) {
                synchronized(queueLock) {
                    pendingConversions.clear()
                }
                stopSelf()
                return START_NOT_STICKY
            }
            processorJob = serviceScope.launch {
                processConversions()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        processorJob?.cancel()
        processorJob = null
        synchronized(queueLock) {
            pendingConversions.clear()
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun processConversions() {
        var finalStartId = 0
        while (serviceScope.isActive) {
            val request = synchronized(queueLock) {
                pendingConversions.removeFirstOrNull().also { next ->
                    if (next == null) {
                        processorJob = null
                        finalStartId = latestStartId
                    }
                }
            } ?: break
            pendingRunId = request.runId
            runConversion(request)
        }
        if (stopSelfResult(finalStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun runConversion(request: ConversionRequest) {
        try {
            when (backend.state.value) {
                is BackendState.Failed -> backend.retry()
                else -> backend.start()
            }
            updateNotification(buildProgressNotification())
            backend.requireApi().convertSessionAsNovel(
                request.runId,
                ArchiveDialogueChapterRequest(request.sessionId, request.title),
            )
            publishResultNotification(
                request = request,
                title = getString(R.string.novel_conversion_notification_complete_title),
                text = getString(R.string.novel_conversion_notification_complete_text),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            publishResultNotification(
                request = request,
                title = getString(R.string.novel_conversion_notification_failed_title),
                text = error.message
                    ?.takeIf(String::isNotBlank)
                    ?: getString(R.string.novel_conversion_notification_failed_text),
            )
        }
    }

    private fun buildStartingNotification(): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.novel_conversion_notification_starting_title))
            .setContentText(getString(R.string.novel_conversion_notification_starting_text))
            .setProgress(0, 0, true)
            .build()

    private fun buildProgressNotification(): Notification =
        notificationBuilder()
            .setContentTitle(getString(R.string.novel_conversion_notification_running_title))
            .setContentText(getString(R.string.novel_conversion_notification_running_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.novel_conversion_notification_running_text)))
            .setProgress(0, 0, true)
            .build()

    private fun publishResultNotification(
        request: ConversionRequest,
        title: String,
        text: String,
    ) {
        try {
            NotificationManagerCompat.from(this).notify(
                request.notificationTag,
                RESULT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_distillation_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openAppPendingIntent(request.runId, request.requestCode))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
            )
        } catch (_: SecurityException) {
            // The conversion still finishes locally when notification permission is denied.
        }
    }

    private fun notificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_distillation_notification)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun openAppPendingIntent(
        runId: String = pendingRunId,
        requestCode: Int = 0,
    ): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_RUN_ID, runId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.novel_conversion_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.novel_conversion_notification_channel_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                getString(R.string.novel_conversion_notification_result_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.novel_conversion_notification_result_channel_description)
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
            // Foreground service still runs when notification permission is denied.
        }
    }

    companion object {
        internal const val ACTION_START =
            "top.wkbin.zaomeng.action.START_NOVEL_CONVERSION"
        internal const val EXTRA_OPEN_RUN_ID = "open_run_id"
        private const val EXTRA_RUN_ID = "run_id"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_TITLE = "title"
        private const val NOTIFICATION_CHANNEL_ID = "novel_conversion_progress"
        private const val RESULT_CHANNEL_ID = "novel_conversion_result"
        private const val NOTIFICATION_ID = 4201
        private const val RESULT_NOTIFICATION_ID = 4202
    }

    private data class ConversionRequest(
        val runId: String,
        val sessionId: String,
        val title: String,
    ) {
        val notificationTag: String
            get() = "novel-conversion-$runId-$sessionId"
        val requestCode: Int
            get() = notificationTag.hashCode()
    }
}

object NovelConversionForegroundController {
    const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    fun start(context: Context, runId: String, sessionId: String, title: String = ""): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, NovelConversionForegroundService::class.java)
            .setAction(NovelConversionForegroundService.ACTION_START)
            .putExtra("run_id", runId)
            .putExtra("session_id", sessionId)
            .putExtra("title", title)
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
