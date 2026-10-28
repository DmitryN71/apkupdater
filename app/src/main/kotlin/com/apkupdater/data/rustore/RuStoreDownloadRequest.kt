package com.apkupdater.data.rustore

import android.content.res.Resources
import android.os.Build


data class RuStoreDownloadRequest(
	val appId: Long,
	val firstInstall: Boolean = true,
	val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
	val sdkVersion: Int = Build.VERSION.SDK_INT,
	/**
	 * False since build 189: the app comes in the parts this device needs, as RuStore's own client
	 * gets it, instead of one universal APK for every processor. True only where the parts cannot
	 * be installed together — root's pm install, and a file saved to a folder.
	 */
	val withoutSplits: Boolean = false,
	/**
	 * The screen's dpi, which picks the density split. Without it RuStore hands out `config.mdpi`,
	 * the lowest-density graphics (measured 2026-10-28: mdpi without it, xxhdpi for 480). The name
	 * was found by trying; `density`, `dpi` and the like, in the body or as headers, are ignored.
	 */
	val screenDensity: Int = Resources.getSystem().displayMetrics.densityDpi
)
