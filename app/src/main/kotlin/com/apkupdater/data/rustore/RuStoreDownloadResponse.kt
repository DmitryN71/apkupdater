package com.apkupdater.data.rustore


/**
 * Response of `v3/showcase/apps/download-link`, the endpoint the current RuStore client uses.
 *
 * It differs from the older v2 one in shape: the fields sit at the top level instead of being
 * wrapped in `body`, and there is no `code` — a call succeeded if a usable URL came back, which
 * is what the callers check. Verified against the live API that v3 accepts the same
 * device-targeting request body as v2 and answers identically for every app tried.
 */
data class RuStoreDownloadResponse(
	val appId: Long = 0L,
	val versionCode: Long = 0L,
	val downloadUrls: List<RuStoreDownloadUrl> = emptyList()
)

data class RuStoreDownloadUrl(
	val url: String = "",
	val size: Long = 0L
)

/**
 * RuStore's download-link now returns a ".zip" wrapper (the real APK plus baseline
 * profiles), which PackageInstaller rejects as "invalid or corrupted APK". The same
 * path served with a ".apk" extension is the raw, directly-installable APK.
 */
fun String.ruStoreApkUrl(): String =
	if (endsWith(".zip", ignoreCase = true)) dropLast(4) + ".apk" else this
