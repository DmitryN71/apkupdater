package com.apkupdater.data.rustore


data class RuStoreDownloadResponse(
	val code: String = "",
	val message: String = "",
	val body: RuStoreDownloadBody = RuStoreDownloadBody()
)

data class RuStoreDownloadBody(
	val appId: Long = 0L,
	val apkUrl: String = "",
	val versionCode: Long = 0L
)
