package com.apkupdater.ui.activity

import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.apkupdater.ui.screen.MainScreen
import com.apkupdater.util.AppVisibility


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

	/** Clean up leftover APK downloads from previous installs (e.g. after self-update). */
	private fun cleanUpDownloadCache() = runCatching {
		File(cacheDir, "downloads").listFiles()?.forEach { it.delete() }
	}
}
