package com.apkupdater.data.rustore


data class RuStoreBatchRequest(
	val content: List<RuStoreBatchEntry>
)

data class RuStoreBatchEntry(
	val packageName: String,
	val versionCode: Long
)
