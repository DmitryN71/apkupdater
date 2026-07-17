package com.apkupdater.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.apkupdater.R
import com.apkupdater.ui.activity.MainActivity
import com.apkupdater.util.BackgroundInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps the process alive while downloads/installs run
 * in [BackgroundInstaller.scope]. It does no work itself — it is a lifecycle
 * anchor plus the ongoing progress notification (with a Cancel action).
 * Started/stopped by [BackgroundInstaller.begin]/[BackgroundInstaller.end].
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloadChannel"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_CANCEL = "com.apkupdater.action.CANCEL_DOWNLOADS"
    }

    private val background: BackgroundInstaller by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        // Refresh the notification only when the visible state changes (task count,
        // whole-percent, or determinate/indeterminate) — this collapses the frequent
        // byte-level progress emits so we don't spam NotificationManager. Self-stop
        // as a safety net if the task set ever empties without an explicit stopService.
        background.tasks
            .map { tasks ->
                val total = tasks.values.sumOf { it.total }
                val prog = tasks.values.sumOf { it.progress }
                Triple(tasks.size, if (total > 0L) (prog * 100L / total).toInt() else -1, total <= 0L)
            }
            .distinctUntilChanged()
            .onEach {
                if (background.tasks.value.isEmpty()) stopSelf()
                else notificationManager().notify(NOTIFICATION_ID, buildNotification())
            }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) background.cancelAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val tasks = background.tasks.value.values.toList()
        val count = tasks.size.coerceAtLeast(1)
        val progressSum = tasks.sumOf { it.progress }
        val totalSum = tasks.sumOf { it.total }
        val indeterminate = totalSum <= 0L
        val percent = if (totalSum > 0L) ((progressSum * 100L) / totalSum).toInt().coerceIn(0, 100) else 0

        val title = if (tasks.size == 1) tasks[0].name else getString(R.string.app_name)
        val text = if (tasks.size == 1 && !indeterminate) "$percent%"
            else resources.getQuantityString(R.plurals.notification_downloading, count, count)

        val contentPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancelPending = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_install)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPending)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(0, getString(R.string.cancel_cd), cancelPending)
            .build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager().createNotificationChannel(channel)
        }
    }

}
