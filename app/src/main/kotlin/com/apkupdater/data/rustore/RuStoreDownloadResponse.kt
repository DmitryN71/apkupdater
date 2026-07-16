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

/**
 * RuStore's download-link now returns a ".zip" wrapper (the real APK plus baseline
 * profiles), which PackageInstaller rejects as "invalid or corrupted APK". The same
 * path served with a ".apk" extension is the raw, directly-installable APK.
 */
fun String.ruStoreApkUrl(): String =
	if (endsWith(".zip", ignoreCase = true)) dropLast(4) + ".apk" else this
