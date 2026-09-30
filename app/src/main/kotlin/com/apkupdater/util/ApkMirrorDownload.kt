package com.apkupdater.util

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.apkupdater.BuildConfig
import com.apkupdater.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException


/**
 * APKMirror's own download chain, walked only when the user has switched it on in Settings —
 * off by default and behind a warning, because APKMirror does not want programs downloading
 * past its pages (its robots.txt closes download.php) and could block the user's address or the
 * update-check API that every copy of the app uses. Built as test builds 169–173, released
 * from 174.
 *
 * Measured by hand on 2026-09-29:
 * - variant page (the link the app_exists API gives, `…-android-apk-download/`) → the
 *   `a.downloadButton` pointing at `…/download/?key=` → the thank-you page → `#download-link`
 *   (`download.php?id=&key=`) → a 302 to a signed file on Cloudflare R2, which honours Range;
 * - the button says "Download APK" or "Download APK Bundle", and a bundle arrives as `.apkm`:
 *   an unencrypted zip of base.apk plus split_config.*.apk for every ABI and density;
 * - a plain OkHttp User-Agent ("okhttp/…") is answered 403 outright;
 * - a Chrome User-Agent from OkHttp is the one that draws the challenge: builds 169 (HTTP/2) and
 *   170 (HTTP/1.1) were both answered "cf-mitigated: challenge" on the very first page from
 *   Dmitry's phone, while EikeiDev's fork on the same phone and network walked the whole chain
 *   saying only "APKUpdaterOSS-v1.3.0". A client that claims to be Chrome and does not shake
 *   hands like Chrome looks like a bot in disguise; one that says what it is does not. So we say
 *   what we are. Build 171 on the same phone: Samsung Browser (a 317 MB APK) and Galaxy Buds
 *   resolved, downloaded and installed;
 * - after two such chains in quick succession Cloudflare answered the third page with
 *   "Just a moment…" (403), and lifted it a few minutes later. Hence one chain at a time, the
 *   requests spaced [MIN_INTERVAL_MS] apart, and a refusal honoured: no retry, no "solving", and
 *   no further request for [BLOCK_PAUSE_MS] — the rest of an "Update all" queue fails at once
 *   instead of walking into the same challenge one card after another.
 */
class ApkMirrorDownload(private val client: OkHttpClient) {

	/** [url] is the file itself; [bundle] means an `.apkm`, a zip of split APKs. */
	data class Resolved(val url: String, val bundle: Boolean)

	/** A failure with a sentence the user can act on; [reason] is a string resource. */
	class Failure(@StringRes val reason: Int, message: String) : IOException(message)

	/**
	 * The chain was abandoned on purpose — the card was cancelled, or the setting switched off,
	 * while it waited its turn. Not an error, so nothing is reported for it.
	 */
	class Stopped : IOException("APKMirror chain canceled")

	private val mutex = Mutex()

	/** On [SystemClock.elapsedRealtime], so setting the phone's clock back cannot stall the queue. */
	@Volatile
	private var lastRequestAt = 0L

	@Volatile
	private var blockedUntil = 0L

	/**
	 * [shouldStop] is asked before every request. A chain queued behind others can wait minutes
	 * for the lock, and without it a Cancel pressed meanwhile was only noticed at download time —
	 * after three more requests to APKMirror.
	 */
	suspend fun resolve(pageUrl: String, shouldStop: () -> Boolean = { false }): Resolved = mutex.withLock {
		withContext(Dispatchers.IO) {
			val page = html(pageUrl, "$BASE/", shouldStop)
			val button = page.selectFirst("a.downloadButton[href*=/download/?key=]")
				?: page.selectFirst("a[href*=/download/?key=]")
				?: throw Failure(R.string.apkmirror_page_changed, "No download button on $pageUrl")
			val keyUrl = button.absUrl("href").ifBlank {
				throw Failure(R.string.apkmirror_page_changed, "Empty download button on $pageUrl")
			}

			val thanks = html(keyUrl, pageUrl, shouldStop)
			val phpUrl = (thanks.selectFirst("#download-link[href]") ?: thanks.selectFirst("a[href*=download.php?id=]"))
				?.absUrl("href")
				?.takeIf { it.isNotBlank() }
				?: throw Failure(R.string.apkmirror_page_changed, "No download.php link on $keyUrl")

			val file = redirect(phpUrl, keyUrl, shouldStop)
			// The button's words first; the file's own name as a second opinion, so a relabelled
			// button cannot send a zip of splits to an installer expecting one APK.
			val bundle = button.text().contains("bundle", ignoreCase = true) ||
				file.encodedPath.endsWith(".apkm", ignoreCase = true)
			Log.i(TAG, "Resolved ${if (bundle) "a bundle" else "an APK"} for $pageUrl")
			Resolved(file.toString(), bundle)
		}
	}

	private suspend fun html(url: String, referer: String, shouldStop: () -> Boolean): Document {
		val body = call(url, referer, ACCEPT_HTML, shouldStop) { response ->
			if (!response.isSuccessful) throw failureFor(response, url)
			response.body.string()
		}
		return Jsoup.parse(body, url)
	}

	/**
	 * download.php answers with a redirect to the file; anything else means the chain changed.
	 * A redirect back onto apkmirror.com is a page, not the file — an expired key or a challenge —
	 * and following it would download HTML and hand it to the installer.
	 */
	private suspend fun redirect(url: String, referer: String, shouldStop: () -> Boolean): HttpUrl =
		call(url, referer, "*/*", shouldStop) { response ->
			val location = response.header("Location")
			if (response.code in 300..399 && !location.isNullOrBlank()) {
				val target = response.request.url.resolve(location)
				if (target == null || target.host.endsWith("apkmirror.com")) {
					throw Failure(R.string.apkmirror_failed, "download.php redirected to a page: $location")
				}
				target
			} else if (response.isSuccessful) {
				throw Failure(R.string.apkmirror_page_changed, "download.php answered ${response.code} instead of a redirect")
			} else {
				throw failureFor(response, url)
			}
		}

	private suspend fun <T> call(
		url: String,
		referer: String,
		accept: String,
		shouldStop: () -> Boolean,
		read: (Response) -> T
	): T {
		if (SystemClock.elapsedRealtime() < blockedUntil) {
			throw Failure(R.string.apkmirror_blocked, "APKMirror refused recently; not asking again yet")
		}
		// Measured from the END of the previous request, so a slow page does not eat the gap.
		val wait = (lastRequestAt + MIN_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtMost(MIN_INTERVAL_MS)
		if (wait > 0) delay(wait)
		if (shouldStop()) throw Stopped()
		val request = Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.header("Accept", accept)
			.header("Referer", referer)
			.build()
		try {
			return client.newCall(request).execute().use(read)
		} finally {
			lastRequestAt = SystemClock.elapsedRealtime()
		}
	}

	private fun failureFor(response: Response, url: String): Failure {
		val challenge = response.header("cf-mitigated")
		Log.w(TAG, "APKMirror answered ${response.code} (cf-mitigated=$challenge) for $url")
		// Only Cloudflare's own refusals pause the queue: a challenge ("Just a moment…", marked
		// cf-mitigated), a rate limit (429), or a flat 403 (a firewall rule refusing us). Build 175
		// also counted 503 — and APKMirror's origin answers a release the update check already
		// lists but the site has not published yet with its "Page Not Found" page and status 503
		// (MAX 26.34.0 on 2026-09-30, the same for every User-Agent). That showed "limiting access"
		// for an hour and paused every other APKMirror download ten minutes per retry.
		if (challenge != null || response.code == 403 || response.code == 429) {
			blockedUntil = SystemClock.elapsedRealtime() + BLOCK_PAUSE_MS
			return Failure(R.string.apkmirror_blocked, "APKMirror ${response.code} for $url")
		}
		// The page's <title> sits about 20 KB in; peek, so nothing else of the body is consumed.
		val notPublished = response.code == 404 || runCatching {
			response.peekBody(64 * 1024L).string().contains("Page Not Found")
		}.getOrDefault(false)
		return if (notPublished) {
			Failure(R.string.apkmirror_not_published, "APKMirror has no page yet: ${response.code} for $url")
		} else {
			Failure(R.string.apkmirror_failed, "APKMirror ${response.code} for $url")
		}
	}

	companion object {
		private const val TAG = "ApkMirrorDownload"
		private const val BASE = "https://www.apkmirror.com"
		private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

		/** Between any two requests to apkmirror.com. Two chains with 1 s gaps drew a challenge. */
		private const val MIN_INTERVAL_MS = 4_000L

		/** After a refusal, how long no request at all goes to APKMirror. */
		private const val BLOCK_PAUSE_MS = 10 * 60_000L

		/**
		 * Our own name, and a name of its own: the update check (ApkMirrorService) says
		 * "APKUpdater-v…", the download chain "APKUpdater-Download-v…". Should APKMirror ever
		 * refuse the downloads, it can do so by name without taking the update check — used by
		 * every copy of the app, most of which never switch this on — down with them. Never a
		 * browser's name (see above).
		 */
		private const val USER_AGENT = "APKUpdater-Download-v" + BuildConfig.VERSION_NAME

		/**
		 * Only a VARIANT page starts the chain — the one the update check gives
		 * (`…-android-apk-download/`). A search result links to the release page, which lists
		 * variants for a person to choose from, so those keep opening in the browser. The full
		 * "-apk-download" ending, not just "-download", so an app whose name ends in "download"
		 * cannot pass for one.
		 */
		fun isVariantPage(url: String) = url.startsWith(BASE) && url.trimEnd('/').endsWith("-apk-download")
	}

}
