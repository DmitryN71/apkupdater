package com.apkupdater.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.apkupdater.R
import com.apkupdater.data.ui.AppInstallStatus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives PackageInstaller session results.
 *
 * This is a BroadcastReceiver (not an Activity) on purpose: results can arrive
 * while the app is in the background, and Android 10+ blocks background Activity
 * launches — the old Activity-based delivery lost the result there, leaving
 * installs stuck at 100%. A receiver always gets the result. If user
 * confirmation is required while the app is not visible, we post a notification
 * instead of trying to open the dialog from the background.
 */
class InstallReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        const val DISMISS_ACTION = "com.apkupdater.action.DISMISS_NOTIFICATION"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }

    private val installLog: InstallLog by inject()
    private val notification: UpdatesNotification by inject()
    private val stringer: Stringer by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DISMISS_ACTION) {
            val nid = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (nid != -1) NotificationManagerCompat.from(context).cancel(nid)
            return
        }
        val id = intent.getAppId() ?: 0
        when (intent.extras?.getInt(PackageInstaller.EXTRA_STATUS)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getIntentExtra()
                if (confirm == null) {
                    // Used to `return` silently, leaving the card stuck on "installing" forever
                    // with no dialog and no error — report it instead.
                    Log.e("InstallReceiver", "No confirmation intent in PENDING_USER_ACTION")
                    installLog.emitStatus(AppInstallStatus(false, id, reason = stringer.get(R.string.install_error_unknown)))
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (AppVisibility.foreground) {
                    runCatching { context.startActivity(confirm) }
                        .onFailure { notification.showConfirmInstallNotification(confirm, id) }
                } else {
                    notification.showConfirmInstallNotification(confirm, id)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                notification.cancelConfirmInstallNotification(id)
                installLog.emitStatus(AppInstallStatus(true, id))
                // If the app isn't in front, post a "installed — Open?" notification.
                if (!AppVisibility.foreground) {
                    val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                    if (!pkg.isNullOrBlank()) {
                        val label = runCatching {
                            context.packageManager.getApplicationLabel(
                                context.packageManager.getApplicationInfo(pkg, 0)
                            ).toString()
                        }.getOrDefault(pkg)
                        notification.showInstallSuccessNotification(pkg, label, id)
                    }
                }
            }
            else -> {
                val status = intent.extras?.getInt(PackageInstaller.EXTRA_STATUS) ?: -1
                val rawMessage = intent.extras?.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
                notification.cancelConfirmInstallNotification(id)
                installLog.emitStatus(AppInstallStatus(false, id, reason = installErrorReason(status, rawMessage)))
                Log.e("InstallReceiver", "Install failed (status $status): $rawMessage")
            }
        }
    }

    private fun installErrorReason(status: Int, rawMessage: String?): String {
        // The raw message carries the most specific INSTALL_FAILED_* code when
        // present, so scan it first, then fall back to the numeric status.
        val fromRaw = rawMessage?.let { installErrorResId(it) }
        val resId = when {
            fromRaw != null && fromRaw != R.string.install_error_unknown -> fromRaw
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_error_aborted
            status == PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_error_blocked
            status == PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.install_error_signature
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.install_error_incompatible
            status == PackageInstaller.STATUS_FAILURE_INVALID -> R.string.install_error_invalid
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_error_storage
            else -> R.string.install_error_unknown
        }
        return stringer.get(resId)
    }

}
