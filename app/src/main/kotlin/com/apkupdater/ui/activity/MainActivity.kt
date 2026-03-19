package com.apkupdater.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.apkupdater.ui.screen.MainScreen


class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		cleanUpCache()
		setContent { MainScreen() }
	}

	/** Clean up leftover download cache from previous installs (e.g. after self-update). */
	private fun cleanUpCache() = runCatching {
		cacheDir.listFiles()?.filter { it.isFile && it.length() > 100_000 }?.forEach { it.delete() }
	}
}
