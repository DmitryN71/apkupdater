package com.apkupdater.ui.activity

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.apkupdater.ui.screen.MainScreen
import com.apkupdater.util.AppVisibility
import com.apkupdater.util.Downloader
import com.apkupdater.util.InstallLog
import com.apkupdater.util.UpdatesNotification
import org.koin.android.ext.android.inject


class MainActivity : ComponentActivity() {

	private val downloader: Downloader by inject()
	private val installLog: InstallLog by inject()
	private val notification: UpdatesNotification by inject()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		cleanUpDownloadCache()
		setContent { MainScreen() }
	}

	override fun onResume() {
		super.onResume()
		AppVisibility.foreground = true
		showPendingInstallConfirmations()
	}

	override fun onPause() {
		AppVisibility.foreground = false
		super.onPause()
	}

	/**
	 * Clean up leftover APK downloads from previous installs (e.g. after self-update).
	 *
	 * Goes through [Downloader.cleanUp] rather than sweeping the directory here, because that
	 * one refuses to delete while a download or install is running. This runs on every
	 * onCreate — which includes every screen rotation, the Activity being destroyed and
	 * rebuilt while work carries on in the background scope. Sweeping regardless used to
	 * delete the APK of an app that had finished downloading and was waiting its turn to
	 * install, so rotating the phone during "Update all" failed the queued installs with an
	 * error that named nothing.
	 *
	 * The partial directory is skipped in there too: it holds half-finished downloads waiting
	 * to be resumed, and they have to outlive a restart — that is the whole point of resume.
	 */
	private fun cleanUpDownloadCache() {
		downloader.cleanUp()
	}

	/**
	 * Shows an install confirmation that the system asked for while the app was not on screen.
	 * See [InstallLog.pendingConfirm] for why one can be waiting here at all. In practice
	 * there is at most one: commits are serialised, so only one install can be awaiting
	 * confirmation at a time.
	 */
	private fun showPendingInstallConfirmations() {
		installLog.takePendingConfirms().forEach { (id, intent) ->
			runCatching {
				startActivity(intent)
				notification.cancelConfirmInstallNotification(id)
			}.onFailure {
				// Put it back. takePendingConfirms() removed it, and dropping it here would
				// undo the whole point of keeping it: the card stays on "Cancel 100%" and the
				// commit lock it holds is never released.
				installLog.rememberConfirm(id, intent)
				Log.e("MainActivity", "Could not show the pending install confirmation", it)
			}
		}
	}
}
