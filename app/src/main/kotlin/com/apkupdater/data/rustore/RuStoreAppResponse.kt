package com.apkupdater.data.rustore


data class RuStoreAppResponse(
	val code: String = "",
	val message: String = "",
	val body: RuStoreApp = RuStoreApp()
)
