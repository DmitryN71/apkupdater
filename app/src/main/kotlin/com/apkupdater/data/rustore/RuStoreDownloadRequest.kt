package com.apkupdater.data.rustore

import android.os.Build


data class RuStoreDownloadRequest(
	val appId: Long,
	val firstInstall: Boolean = true,
	val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
	val sdkVersion: Int = Build.VERSION.SDK_INT,
	val withoutSplits: Boolean = true
)
