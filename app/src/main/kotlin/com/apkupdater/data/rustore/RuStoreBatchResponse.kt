package com.apkupdater.data.rustore


data class RuStoreBatchResponse(
	val body: RuStoreBatchBody = RuStoreBatchBody()
)

data class RuStoreBatchBody(
	val content: List<RuStoreBatchApp> = emptyList()
)

data class RuStoreBatchApp(
	val appId: Long = 0L,
	val packageName: String = "",
	val appName: String = "",
	val updatedAt: String = "",
	val versionCode: Long = 0L
)
