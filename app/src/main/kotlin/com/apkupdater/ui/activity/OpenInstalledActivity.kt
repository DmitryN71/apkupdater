package com.apkupdater.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat

/**
 * Invisible trampoline for the "installed — Open" notification. Cancels that notification and
 * opens the freshly installed app.
 *
 * It's an Activity (not the InstallReceiver) on purpose: notification actions that go through a
 * BroadcastReceiver/Service can't start an activity on Android 12+ (the notification-trampoline
 * restriction), whereas a foreground activity launched from the notification may freely start the
 * target app. Transparent + noHistory + excludeFromRecents so nothing is ever visible.
 */
class OpenInstalledActivity : Activity() {

	companion object {
		const val EXTRA_PACKAGE = "com.apkupdater.extra.OPEN_PACKAGE"
		const val EXTRA_NOTIFICATION_ID = "com.apkupdater.extra.OPEN_NOTIFICATION_ID"
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
		if (notificationId != -1) NotificationManagerCompat.from(this).cancel(notificationId)

		val packageName = intent.getStringExtra(EXTRA_PACKAGE)
		if (!packageName.isNullOrBlank()) {
			runCatching {
				packageManager.getLaunchIntentForPackage(packageName)
					?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
					?.let { startActivity(it) }
			}
		}

		finish()
	}
}
