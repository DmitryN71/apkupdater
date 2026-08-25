package com.apkupdater.ui.activity

import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.apkupdater.ui.screen.MainScreen
import com.apkupdater.util.AppVisibility
import com.apkupdater.util.Downloader


class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		cleanUpDownloadCache()
		setContent { MainScreen() }
	}

	override fun onResume() {
		super.onResume()
		AppVisibility.foreground = true
	}

	override fun onPause() {
		AppVisibility.foreground = false
		super.onPause()
	}

	/**
	 * Clean up leftover APK downloads from previous installs (e.g. after self-update).
	 *
	 * Skips Downloader's "partial" subdirectory: it holds half-finished downloads waiting to
	 * be resumed, and they have to outlive a restart — that is the whole point of resume.
	 * Downloader prunes them once they go stale.
	 */
	private fun cleanUpDownloadCache() = runCatching {
		File(cacheDir, "downloads").listFiles()?.forEach {
			if (it.name != Downloader.PARTIAL_DIR) it.delete()
		}
	}
}
