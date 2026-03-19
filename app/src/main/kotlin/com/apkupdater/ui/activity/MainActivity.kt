package com.apkupdater.ui.activity

import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.apkupdater.ui.screen.MainScreen


class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		cleanUpDownloadCache()
		setContent { MainScreen() }
	}

	/** Clean up leftover APK downloads from previous installs (e.g. after self-update). */
	private fun cleanUpDownloadCache() = runCatching {
		File(cacheDir, "downloads").listFiles()?.forEach { it.delete() }
	}
}
