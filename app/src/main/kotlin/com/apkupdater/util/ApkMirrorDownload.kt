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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap


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
 * - from 2026-10-11 the app pages draw the challenge over HTTP/2 even under our own name, and are
 *   served over HTTP/1.1 — the same client, address and name, requests alternating. The search
 *   page still passes over both. Hence the client is HTTP/1.1-only (MainModule, build 183);
 * - after two such chains in quick succession Cloudflare answered the third page with
 *   "Just a moment…" (403), and lifted it a few minutes later. Hence one chain at a time, the
 *   requests spaced [MIN_INTERVAL_MS] apart, and a refusal honoured: no retry, no "solving", and
 *   no further request for [BLOCK_PAUSE_MS] — the rest of an "Update all" queue fails at once
 *   instead of walking into the same challenge one card after another;
 * - from 2026-10-13 EVERY page under `/apk/discord/` draws the challenge — developer, app,
 *   release and variant pages, from any address — while Telegram, WeChat or Android Auto, asked
 *   the same minute from the same address, are served. A refusal can therefore be one app's
 *   rather than the whole site's. Until build 184 any refusal paused everything, so one tap on
 *   Discord failed every other APKMirror download for ten minutes, and Dmitry took three good
 *   VPN servers for blocked ones. So a refusal now pauses that app alone ([appBlockedUntil]),
 *   and the whole site only when a SECOND app is refused inside the same window, or on a 429.
 */
class ApkMirrorDownload(private val client: OkHttpClient) {

	/** [url] is the file itself; [bundle] means an `.apkm`, a zip of split APKs. */
	data class Resolved(val url: String, val bundle: Boolean)

	/** A failure with a sentence the user can act on; [reason] is a string resource. */
	class Failure(@param:StringRes val reason: Int, message: String) : IOException(message)

	/**
	 * The chain was abandoned on purpose — the card was cancelled, or the setting switched off,
	 * while it waited its turn. Not an error, so nothing is reported for it.
	 */
	class Stopped : IOException("APKMirror chain canceled")

	private val mutex = Mutex()

	/** On [SystemClock.elapsedRealtime], so setting the phone's clock back cannot stall the queue. */
	@Volatile
	private var lastRequestAt = 0L

	/** Until when nothing at all goes to APKMirror: the site refused us, not one page. */
	@Volatile
	private var blockedUntil = 0L

	/** Apps ([appKey]) whose pages were refused, and until when they are left alone. */
	private val appBlockedUntil = ConcurrentHashMap<String, Long>()

	/**
	 * Whether the app behind this page was refused within the last [BLOCK_PAUSE_MS]. The screens
	 * open such a page in the browser on Update, where the person passes the check themselves,
	 * and leave it out of "Update all".
	 */
	fun isAppPaused(pageUrl: String): Boolean =
		(appBlockedUntil[appKey(pageUrl)] ?: 0L) > SystemClock.elapsedRealtime()

	/**
	 * [shouldStop] is asked before every request. A chain queued behind others can wait minutes
	 * for the lock, and without it a Cancel pressed meanwhile was only noticed at download time —
	 * after three more requests to APKMirror.
	 */
	suspend fun resolve(pageUrl: String, shouldStop: () -> Boolean = { false }): Resolved = mutex.withLock {
		withContext(Dispatchers.IO) {
			// Every step is charged to the app the chain started from: download.php lives outside
			// the app's path, and a refusal there is still a refusal of this app's download.
			val app = appKey(pageUrl)
			val page = html(pageUrl, "$BASE/", app, shouldStop)
			val button = page.selectFirst("a.downloadButton[href*=/download/?key=]")
				?: page.selectFirst("a[href*=/download/?key=]")
				?: throw Failure(R.string.apkmirror_page_changed, "No download button on $pageUrl")
			val keyUrl = button.absUrl("href").ifBlank {
				throw Failure(R.string.apkmirror_page_changed, "Empty download button on $pageUrl")
			}

			val thanks = html(keyUrl, pageUrl, app, shouldStop)
			val phpUrl = (thanks.selectFirst("#download-link[href]") ?: thanks.selectFirst("a[href*=download.php?id=]"))
				?.absUrl("href")
				?.takeIf { it.isNotBlank() }
				?: throw Failure(R.string.apkmirror_page_changed, "No download.php link on $keyUrl")

			val file = redirect(phpUrl, keyUrl, app, shouldStop)
			// The button's words first; the file's own name as a second opinion, so a relabelled
			// button cannot send a zip of splits to an installer expecting one APK.
			val bundle = button.text().contains("bundle", ignoreCase = true) ||
				file.encodedPath.endsWith(".apkm", ignoreCase = true)
			Log.i(TAG, "Resolved ${if (bundle) "a bundle" else "an APK"} for $pageUrl")
			Resolved(file.toString(), bundle)
		}
	}

	private suspend fun html(url: String, referer: String, app: String, shouldStop: () -> Boolean): Document {
		val body = call(url, referer, ACCEPT_HTML, app, shouldStop) { response ->
			if (!response.isSuccessful) throw failureFor(response, url, app)
			response.body.string()
		}
		return Jsoup.parse(body, url)
	}

	/**
	 * download.php answers with a redirect to the file; anything else means the chain changed.
	 * A redirect back onto apkmirror.com is a page, not the file — an expired key or a challenge —
	 * and following it would download HTML and hand it to the installer.
	 */
	private suspend fun redirect(url: String, referer: String, app: String, shouldStop: () -> Boolean): HttpUrl =
		call(url, referer, "*/*", app, shouldStop) { response ->
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
				throw failureFor(response, url, app)
			}
		}

	private suspend fun <T> call(
		url: String,
		referer: String,
		accept: String,
		app: String,
		shouldStop: () -> Boolean,
		read: (Response) -> T
	): T {
		val now = SystemClock.elapsedRealtime()
		if (now < blockedUntil) {
			throw Failure(R.string.apkmirror_blocked, "APKMirror refused recently; not asking again yet")
		}
		if (now < (appBlockedUntil[app] ?: 0L)) {
			throw Failure(R.string.apkmirror_app_blocked, "APKMirror refused $app recently; not asking again yet")
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

	private fun failureFor(response: Response, url: String, app: String): Failure {
		val challenge = response.header("cf-mitigated")
		AppJournal.add(TAG, "APKMirror answered ${response.code} (cf-mitigated=$challenge) for $url")
		// Only Cloudflare's own refusals pause the queue: a challenge ("Just a moment…", marked
		// cf-mitigated), a rate limit (429), or a flat 403 (a firewall rule refusing us). Build 175
		// also counted 503 — and APKMirror's origin answers a release the update check already
		// lists but the site has not published yet with its "Page Not Found" page and status 503
		// (MAX 26.34.0 on 2026-09-30, the same for every User-Agent). That showed "limiting access"
		// for an hour and paused every other APKMirror download ten minutes per retry.
		if (challenge != null || response.code == 403 || response.code == 429) {
			val now = SystemClock.elapsedRealtime()
			// Since build 184 a refusal pauses this app first — see the class notes on Discord. A
			// second app refused inside the window means the site is refusing us, whatever the
			// page; and a 429 says outright that it is about our rate, not about a page.
			val othersRefused = appBlockedUntil.any { (key, until) -> key != app && until > now }
			appBlockedUntil[app] = now + BLOCK_PAUSE_MS
			if (othersRefused || response.code == 429) {
				blockedUntil = now + BLOCK_PAUSE_MS
				return Failure(R.string.apkmirror_blocked, "APKMirror ${response.code} for $url")
			}
			return Failure(R.string.apkmirror_app_blocked, "APKMirror ${response.code} for $app: $url")
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

		/**
		 * The app a page belongs to: `discord/discord-chat-for-gamers` for any page under
		 * `/apk/discord/discord-chat-for-gamers/`, whatever release or variant. Anything that does
		 * not have that shape is its own key.
		 */
		fun appKey(url: String): String {
			val segments = url.toHttpUrlOrNull()?.pathSegments ?: return url
			return if (segments.size >= 3 && segments[0] == "apk" && segments[1].isNotEmpty() && segments[2].isNotEmpty()) {
				"${segments[1]}/${segments[2]}"
			} else {
				url
			}
		}
	}

}
