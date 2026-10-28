package com.apkupdater.data.rustore

import com.apkupdater.data.ui.Link


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
 * The download as a [Link] (build 189): one part is a [Link.Url], several are [Link.Splits] to be
 * installed together. Null when RuStore gave no usable URL.
 *
 * Each URL is RuStore's .zip wrapper — the APK deflated, plus baseline profiles beside the base —
 * which PackageInstaller cannot read, so [com.apkupdater.util.Downloader] takes the APK out after
 * the download. Until 189 the link was rewritten to the raw .apk the same path also serves, and
 * the universal APK was asked for, together up to four times the bytes RuStore's own client
 * fetches (measured 2026-10-27: Мой МТС 341 MB instead of 160, МАКС 120 instead of 28, Яндекс
 * Браузер 307 instead of 152). Reported on 4PDA by Maximoff, whose card said 153 MB.
 */
fun RuStoreDownloadResponse.toLink(): Link? {
	val parts = downloadUrls.filter { it.url.isNotEmpty() }.map { Link.Url(it.url, it.size) }
	return when (parts.size) {
		0 -> null
		1 -> parts.single()
		else -> Link.Splits(parts)
	}
}
