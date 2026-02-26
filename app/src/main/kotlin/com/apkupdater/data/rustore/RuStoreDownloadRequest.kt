package com.apkupdater.data.rustore


data class RuStoreDownloadRequest(
	val appId: Long,
	val firstInstall: Boolean = true
)
