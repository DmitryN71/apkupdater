package com.apkupdater.data.rustore


data class RuStoreSearchResponse(
	val code: String = "",
	val body: RuStoreSearchBody? = null
)

data class RuStoreSearchBody(
	val content: List<RuStoreSearchApp> = emptyList(),
	val totalPages: Int = 0
)

data class RuStoreSearchApp(
	val packageName: String = "",
	val appName: String = "",
	val iconUrl: String = ""
)
