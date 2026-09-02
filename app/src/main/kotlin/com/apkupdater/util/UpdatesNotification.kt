package com.apkupdater.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apkupdater.R
import com.apkupdater.ui.activity.MainActivity
import com.apkupdater.ui.activity.OpenInstalledActivity
import java.util.concurrent.ConcurrentHashMap

class UpdatesNotification(private val context: Context) {

    companion object {
        const val UpdateAction = "updateAction"
        private const val CONFIRM_CHANNEL_ID = "installConfirmChannel"
        private const val SUCCESS_CHANNEL_ID = "installSuccessChannel"
        private const val FAILURE_CHANNEL_ID = "installFailureChannel"
    }

    /**
     * Notification id of the last failure posted for each app label.
     *
     * The install id is derived from source, package, versionCode AND version, so it changes
     * the moment a newer version appears — and a retry would then clear a different id than
     * the one the old failure was posted under, leaving it in the shade for good.
     */
    private val failureIds = ConcurrentHashMap<String, Int>()

    private val notificationManager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = context.getString(R.string.notification_channel_id)
    private val channelName = context.getString(R.string.notification_channel_name)
    private val updateTitle = context.getString(R.string.notification_update_title)
    private val updateId = 42

    @SuppressLint("MissingPermission")
    fun showUpdateNotification(num: Int) {
        // Intent for the notification click
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            action = UpdateAction
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_install)
            .setContentTitle(updateTitle)
            .setContentText(context.resources.getQuantityString(R.plurals.notification_update_description, num, num))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)

        createNotificationChannel()
        if (areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(updateId, builder.build())
        }
    }

    /**
     * Posted when an install needs user confirmation while the app is in the
     * background. Tapping launches the system installer dialog directly.
     */
    @SuppressLint("MissingPermission")
    fun showConfirmInstallNotification(confirmIntent: Intent, id: Int) {
        createConfirmChannel()
        val pending = PendingIntent.getActivity(
            context, id, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CONFIRM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_install)
            .setContentTitle(context.getString(R.string.notification_confirm_title))
            .setContentText(context.getString(R.string.notification_confirm_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }

    fun cancelConfirmInstallNotification(id: Int) =
        NotificationManagerCompat.from(context).cancel(id)

    /**
     * Posted after a successful (usually background) install, with an Open action
     * that launches the freshly installed app and a Dismiss action.
     */
    @SuppressLint("MissingPermission")
    fun showInstallSuccessNotification(packageName: String, label: String, id: Int) {
        createSuccessChannel()
        // Route Open through OpenInstalledActivity so tapping it (the button OR the body) also
        // cancels this notification — a plain notification action button never auto-dismisses.
        val hasLauncher = context.packageManager.getLaunchIntentForPackage(packageName) != null
        val openPending = if (hasLauncher) {
            PendingIntent.getActivity(
                context, id,
                Intent(context, OpenInstalledActivity::class.java).apply {
                    putExtra(OpenInstalledActivity.EXTRA_PACKAGE, packageName)
                    putExtra(OpenInstalledActivity.EXTRA_NOTIFICATION_ID, id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        val dismissPending = PendingIntent.getBroadcast(
            context, id,
            Intent(context, InstallReceiver::class.java).apply {
                action = InstallReceiver.DISMISS_ACTION
                putExtra(InstallReceiver.EXTRA_NOTIFICATION_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, SUCCESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_install)
            .setContentTitle(context.getString(R.string.notification_installed_title, label))
            .setContentText(context.getString(R.string.notification_installed_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        openPending?.let {
            builder.setContentIntent(it)
            builder.addAction(0, context.getString(R.string.open_cd), it)
        }
        builder.addAction(0, context.getString(R.string.dismiss_cd), dismissPending)
        if (areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }

    /**
     * Posted when an install fails while the app is not on screen.
     *
     * Until now a failure only ever produced an in-app message. Those survive the app merely
     * being stopped — the collector lives in the composition and queues them — but they are
     * emitted into a flow with no replay, so once the Activity is DESTROYED and the work
     * carries on in the process-lifetime scope, there is nobody to receive them and they are
     * dropped. Swipe the app away mid-batch and every failure went unreported: the card just
     * read "Update" again. Success has had a notification since build 112; this is its other
     * half. See InstallViewModel.reportFailure for which of the two routes is chosen.
     *
     * Shares the install id with the confirm and success notifications on purpose — one
     * install occupies one slot, and a failure replaces the confirmation it came from.
     */
    @SuppressLint("MissingPermission")
    fun showInstallFailureNotification(label: String, reason: String, id: Int): Boolean {
        createFailureChannel()
        val openPending = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_install)
            .setContentTitle(context.getString(R.string.notification_failed_title, label))
            .setContentText(reason)
            // The reason is a whole sentence and the collapsed line truncates it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPending)
            .setAutoCancel(true)
        // Returns whether it was really posted. The caller needs to know: without the
        // permission this silently does nothing, and a caller that assumed success would leave
        // the user with no message at all.
        if (!areNotificationsEnabled()) return false
        failureIds[label] = id
        NotificationManagerCompat.from(context).notify(id, builder.build())
        return true
    }

    /** Clears a failure this app left behind, whatever version it was posted for. */
    fun cancelFailureFor(label: String) {
        failureIds.remove(label)?.let { NotificationManagerCompat.from(context).cancel(it) }
    }

    /** Clears whatever this install last left in the shade — a failure, or its confirmation. */
    fun cancelInstallNotification(id: Int) =
        NotificationManagerCompat.from(context).cancel(id)

    private fun createFailureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FAILURE_CHANNEL_ID,
                context.getString(R.string.notification_failure_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createSuccessChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SUCCESS_CHANNEL_ID,
                context.getString(R.string.notification_success_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createConfirmChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CONFIRM_CHANNEL_ID,
                context.getString(R.string.notification_confirm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun checkNotificationPermission(launcher: ManagedActivityResultLauncher<String, Boolean>) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (!areNotificationsEnabled()) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun areNotificationsEnabled() = NotificationManagerCompat
        .from(context)
        .areNotificationsEnabled()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

}
