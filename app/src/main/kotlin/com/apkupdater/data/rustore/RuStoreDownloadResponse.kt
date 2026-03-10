package com.apkupdater.data.rustore


data class RuStoreDownloadResponse(
	val code: String = "",
	val message: String = "",
	val body: RuStoreDownloadBody = RuStoreDownloadBody()
)

data class RuStoreDownloadBody(
	val downloadUrls: List<RuStoreDownloadUrl> = emptyList()
)

data class RuStoreDownloadUrl(
	val url: String = "",
	val type: String = ""
)
