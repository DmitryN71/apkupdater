package com.apkupdater.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.apkupdater.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Fallback resolver for APKMirror's 3-step download chain using Android's system Chromium WebView.
 *
 * Used only when [ApkMirrorDownload]'s plain HTTP/1.1 OkHttp client receives a Cloudflare
 * challenge (`403 cf-mitigated: challenge`) – either because the app is on APKMirror's protected
 * publisher list (Discord, Spotify, WhatsApp, Instagram) or because the user's VPN exit ASN is
 * challenged by Cloudflare when approached with non-browser TLS fingerprints.
 *
 * Critical anti-detection rules for Cloudflare Turnstile (`challenges.cloudflare.com`) on Android:
 * 1. Never modify `settings.userAgentString`: stripping `; wv)` or `Version/4.0` causes Chromium to
 *    drop or mismatch `Sec-CH-UA` Client Hints while `navigator.userAgentData.brands` still reports
 *    `"Android WebView"`, which Turnstile flags as a spoofed User-Agent loop.
 * 2. Always construct the [WebView] with the real `Activity` context already attached to the
 *    primary focused window – never start Turnstile detached offscreen with an `Application`
 *    context or inside an unfocused secondary Dialog window.
 * 3. Never run `evaluateJavascript` or `shouldInterceptRequest` while the Cloudflare challenge page
 *    (`"Just a moment..."`) is active. Wait passively using native Kotlin `webView.title` callbacks
 *    until Turnstile passes and the real `- APKMirror` page loads.
 */
class ApkMirrorWebViewResolver(@Suppress("UNUSED_PARAMETER") appContext: Context) {

	class ChallengeSession(
		val pageUrl: String,
		internal val attachWebView: (Context) -> WebView,
		internal val detachWebView: (WebView) -> Unit,
		private val onDismiss: (openInBrowser: Boolean) -> Unit
	) {
		private val _verified = MutableStateFlow(false)
		val verified: StateFlow<Boolean> = _verified

		internal fun markVerified() {
			_verified.value = true
		}

		fun createWebView(activityContext: Context): WebView = attachWebView(activityContext)

		fun releaseWebView(webView: WebView) = detachWebView(webView)

		fun cancel(openInBrowser: Boolean = false) {
			onDismiss(openInBrowser)
		}
	}

	private val _session = MutableStateFlow<ChallengeSession?>(null)
	val session: StateFlow<ChallengeSession?> = _session

	@SuppressLint("SetJavaScriptEnabled")
	suspend fun resolve(
		pageUrl: String,
		shouldStop: () -> Boolean
	): ApkMirrorDownload.Resolved = withContext(Dispatchers.Main) {
		if (!AppVisibility.foreground) {
			throw IOException("Cloudflare challenge requires foreground window for $pageUrl")
		}

		val outcome = CompletableDeferred<ApkMirrorDownload.Resolved>()
		var activeWebView: WebView? = null
		var bundle = false
		var sawStep1Or2 = false
		val startedAt = SystemClock.elapsedRealtime()

		fun completeWithFile(fileUrl: String, contentDisposition: String? = null) {
			if (outcome.isCompleted) return
			val cleanPath = fileUrl.substringBefore('?')
			val isBundle = bundle ||
				cleanPath.endsWith(".apkm", ignoreCase = true) ||
				contentDisposition?.contains(".apkm", ignoreCase = true) == true
			Log.i(TAG, "WebView intercepted ${if (isBundle) "bundle" else "APK"} URL for $pageUrl")
			_session.value = null
			outcome.complete(ApkMirrorDownload.Resolved(fileUrl, isBundle))
		}

		suspend fun runStepProbeIfOnApkMirror(webView: WebView) {
			if (outcome.isCompleted || !isRealApkMirrorPage(webView)) return
			_session.value?.markVerified()
			val status = evaluateJs(webView, STEP_JS)
			when {
				status.startsWith("step1|") -> {
					val parts = status.split('|', limit = 3)
					val btnText = parts.getOrNull(1).orEmpty()
					sawStep1Or2 = true
					if (btnText.contains("bundle", ignoreCase = true)) bundle = true
				}
				status.startsWith("step2|") -> {
					val parts = status.split('|', limit = 3)
					val isBundleNote = parts.getOrNull(1) == "1"
					sawStep1Or2 = true
					if (isBundleNote) bundle = true
				}
				status == "not_found" -> {
					outcome.completeExceptionally(
						ApkMirrorDownload.Failure(
							R.string.apkmirror_not_published,
							"APKMirror Page Not Found in WebView for $pageUrl"
						)
					)
				}
			}
		}

		val challengeSession = ChallengeSession(
			pageUrl = pageUrl,
			attachWebView = { activityContext ->
				activeWebView?.let { existing ->
					(existing.parent as? ViewGroup)?.removeView(existing)
					return@ChallengeSession existing
				}
				WebView(activityContext).apply {
					activeWebView = this
					layoutParams = ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.MATCH_PARENT
					)
					isFocusable = true
					isFocusableInTouchMode = true

					CookieManager.getInstance().let { cm ->
						cm.setAcceptCookie(true)
						cm.setAcceptThirdPartyCookies(this, true)
					}

					settings.apply {
						javaScriptEnabled = true
						domStorageEnabled = true
						useWideViewPort = true
						loadWithOverviewMode = true
						javaScriptCanOpenWindowsAutomatically = false
						setSupportMultipleWindows(false)
					}

					webChromeClient = object : WebChromeClient() {
						override fun onReceivedTitle(view: WebView?, title: String?) {
							super.onReceivedTitle(view, title)
							if (view != null && isRealApkMirrorPage(view)) {
								_session.value?.markVerified()
							}
						}
					}

					webViewClient = object : WebViewClient() {
						override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
							super.onPageStarted(view, url, favicon)
							if (url != null && looksLikeFileRedirect(url)) {
								completeWithFile(url)
							}
						}

						override fun onPageCommitVisible(view: WebView, url: String) {
							super.onPageCommitVisible(view, url)
							if (isRealApkMirrorPage(view)) {
								_session.value?.markVerified()
							}
						}

						override fun onPageFinished(view: WebView, url: String) {
							super.onPageFinished(view, url)
							if (isRealApkMirrorPage(view)) {
								_session.value?.markVerified()
							}
						}

						override fun shouldOverrideUrlLoading(
							view: WebView,
							request: WebResourceRequest
						): Boolean {
							val uri = request.url ?: return false
							val scheme = uri.scheme?.lowercase().orEmpty()
							if (scheme != "http" && scheme != "https") return true
							val host = uri.host?.lowercase().orEmpty()
							val url = uri.toString()
							if (host.endsWith("apkmirror.com") || host.endsWith("cloudflare.com")) {
								return false
							}
							if (request.isForMainFrame && (sawStep1Or2 || looksLikeFileRedirect(url))) {
								completeWithFile(url)
							}
							return true
						}
					}

					setDownloadListener { url, _, contentDisposition, _, _ ->
						val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
						if (host.isNotEmpty() && !host.endsWith("apkmirror.com")) {
							completeWithFile(url, contentDisposition)
						}
					}

					post {
						requestFocus()
						loadUrl(pageUrl)
					}
				}
			},
			detachWebView = { view ->
				(view.parent as? ViewGroup)?.removeView(view)
			},
			onDismiss = { openInBrowser ->
				_session.value = null
				if (!outcome.isCompleted) {
					outcome.completeExceptionally(
						if (openInBrowser) ApkMirrorDownload.OpenInBrowser(pageUrl)
						else ApkMirrorDownload.Stopped()
					)
				}
			}
		)

		_session.value = challengeSession

		val watcher = launch {
			while (isActive && !outcome.isCompleted) {
				if (shouldStop()) {
					outcome.completeExceptionally(ApkMirrorDownload.Stopped())
					break
				}
				if (SystemClock.elapsedRealtime() - startedAt > INTERACTIVE_TIMEOUT_MS) {
					outcome.completeExceptionally(IOException("APKMirror WebView timed out"))
					break
				}
				// IMPORTANT: Only execute JS after Turnstile has completed and the native WebView
				// title confirms we are on an actual APKMirror page, never during the challenge.
				activeWebView?.let { wv ->
					if (isRealApkMirrorPage(wv)) {
						runStepProbeIfOnApkMirror(wv)
					}
				}
				if (!outcome.isCompleted) delay(POLL_INTERVAL_MS)
			}
		}

		try {
			outcome.await()
		} finally {
			watcher.cancel()
			_session.value = null
			runCatching { CookieManager.getInstance().flush() }
			activeWebView?.let { wv ->
				runCatching {
					(wv.parent as? ViewGroup)?.removeView(wv)
					wv.stopLoading()
					wv.loadUrl("about:blank")
					wv.onPause()
					wv.destroy()
				}
			}
			activeWebView = null
		}
	}

	private fun isChallengeTitle(title: String): Boolean {
		if (title.isBlank()) return true
		return title.contains("Just a moment", ignoreCase = true) ||
			title.contains("Один момент", ignoreCase = true) ||
			title.contains("Attention Required", ignoreCase = true) ||
			title.contains("Cloudflare", ignoreCase = true)
	}

	/**
	 * Pure Kotlin check (zero `evaluateJavascript` calls) to determine whether the WebView has
	 * passed Cloudflare Turnstile and arrived on a real APKMirror document.
	 */
	private fun isRealApkMirrorPage(webView: WebView): Boolean {
		val title = webView.title.orEmpty()
		if (isChallengeTitle(title)) return false
		return title.contains("APKMirror", ignoreCase = true)
	}

	private suspend fun evaluateJs(webView: WebView, script: String): String =
		suspendCancellableCoroutine { cont ->
			runCatching {
				webView.evaluateJavascript(script) { raw ->
					if (cont.isActive) {
						val unquoted = raw?.trim()
							?.takeIf { it != "null" }
							?.removeSurrounding("\"")
							?.replace("\\\"", "\"")
							?.replace("\\\\", "\\")
							.orEmpty()
						cont.resume(unquoted)
					}
				}
			}.onFailure {
				if (cont.isActive) cont.resume("")
			}
		}

	companion object {
		private const val TAG = "ApkMirrorWebView"
		private const val POLL_INTERVAL_MS = 450L
		private const val INTERACTIVE_TIMEOUT_MS = 90_000L

		private fun looksLikeFileRedirect(url: String): Boolean {
			val parsed = url.toHttpUrlOrNull() ?: return false
			val host = parsed.host.lowercase()
			if (host.endsWith("apkmirror.com") || host.endsWith("cloudflare.com")) {
				return false
			}
			val path = parsed.encodedPath.lowercase()
			val query = parsed.encodedQuery?.lowercase().orEmpty()
			return host.endsWith("cloudflarestorage.com") ||
				host.endsWith("backblazeb2.com") ||
				path.endsWith(".apk") ||
				path.endsWith(".apkm") ||
				query.contains("x-amz-signature")
		}

		/**
		 * Runs ONLY after [isRealApkMirrorPage] returns true (never on the Cloudflare challenge
		 * screen). On Step 1 (variant page), it first tries a fast same-origin `fetch()` of the
		 * Step 2 `/download/?key=` HTML to extract `download.php?id=` in ~150 ms without waiting
		 * for Step 2's ad scripts to render, falling back to `window.location.assign(btnHref)`.
		 */
		private val STEP_JS = """
			(function() {
				try {
					var now = Date.now();
					var dl = document.querySelector('#download-link[href]') || document.querySelector('a[href*="download.php?id="]');
					if (dl && dl.href) {
						var dlHref = dl.href;
						var bodyStr = (document.body && document.body.innerText) ? document.body.innerText : '';
						var isBundle = bodyStr.indexOf('install this bundle') !== -1 ? '1' : '0';
						if (!window.__apku_step2_at || (now - window.__apku_step2_at > 6000)) {
							window.__apku_step2_at = now;
							dl.removeAttribute('id');
							window.location.assign(dlHref);
						}
						return 'step2|' + isBundle + '|' + dlHref;
					}
					var btn = document.querySelector('a.downloadButton[href*="/download/?key="]') || document.querySelector('a[href*="/download/?key="]');
					if (btn && btn.href) {
						var btnHref = btn.href;
						var btnText = (btn.innerText || btn.textContent || '').replace(/\|/g, ' ').trim();
						if (!window.__apku_step1_at || (now - window.__apku_step1_at > 6000)) {
							window.__apku_step1_at = now;
							fetch(btnHref, { credentials: 'include' })
								.then(function(r) { return r.ok ? r.text() : Promise.reject(r.status); })
								.then(function(html) {
									var doc = new DOMParser().parseFromString(html, 'text/html');
									var link = doc.querySelector('#download-link[href]') || doc.querySelector('a[href*="download.php?id="]');
									if (link && link.getAttribute('href')) {
										var phpUrl = new URL(link.getAttribute('href'), btnHref).href;
										window.location.assign(phpUrl);
									} else {
										window.location.assign(btnHref);
									}
								})
								.catch(function() {
									window.location.assign(btnHref);
								});
						}
						return 'step1|' + btnText + '|' + btnHref;
					}
					var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
					if (bodyText.indexOf('Page Not Found') !== -1) {
						return 'not_found';
					}
					return 'loading';
				} catch (e) {
					return 'error';
				}
			})()
		""".trimIndent()
	}
}
