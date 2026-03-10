package com.apkupdater.repository

import android.os.SystemClock
import android.util.Log
import com.apkupdater.data.rustore.RuStoreBatchEntry
import com.apkupdater.data.rustore.RuStoreBatchRequest
import com.apkupdater.data.rustore.RuStoreDownloadRequest
import com.apkupdater.data.rustore.toAppUpdate
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.getApp
import com.apkupdater.prefs.Prefs
import com.apkupdater.prefs.RuStore404Entry
import com.apkupdater.service.RuStoreService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicBoolean


class RuStoreRepository(
	private val service: RuStoreService,
	private val prefs: Prefs
) {

	companion object {
		private const val MIN_BATCH_DELAY_MS = 300L
		private const val MAX_BATCH_DELAY_MS = 1200L
		private const val DELAY_STEP_MS = 150L
		private const val MAX_RATE_LIMIT_RETRIES = 1
		private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
		private const val RUSTORE_404_TTL_MS = 7 * 24 * 60 * 60 * 1000L
	}

	@Volatile
	private var currentBatchDelayMs = MIN_BATCH_DELAY_MS
	@Volatile
	private var lastRateLimitTimestamp = 0L

	suspend fun updates(apps: List<AppInstalled>) = flow {
		val ignoredPackages = getActiveRuStore404Set()

		// Step 1: Batch check all apps at once (single request), filtering out cached 404s
		val filteredApps = apps.filterNot { ignoredPackages.contains(it.packageName) }
		val batchRequest = RuStoreBatchRequest(
			filteredApps.map { RuStoreBatchEntry(it.packageName, it.versionCode) }
		)
		val batchResponse = service.getBatchUpdates(batchRequest)
		val appsWithUpdates = batchResponse.body.content

		if (appsWithUpdates.isEmpty()) {
			emit(emptyList())
			return@flow
		}

		// Step 2: For each app with an update, fetch details + download link with rate limiting
		val updates = processAppsWithRateLimiting(appsWithUpdates) { batchApp, markRateLimit ->
			retryOnRateLimit {
				var hitRateLimit = false
				val result = runCatching {
					val detailResponse = service.getAppInfo(batchApp.packageName)
					if (detailResponse.code != "OK" || detailResponse.body.appId == 0L) return@runCatching null

					val ruStoreApp = detailResponse.body
					clearRuStore404(batchApp.packageName)
					if (!filterAlpha(ruStoreApp.versionName) || !filterBeta(ruStoreApp.versionName)) return@runCatching null

					val downloadUrl = getDownloadUrl(ruStoreApp.appId)
					if (downloadUrl.isEmpty()) return@runCatching null

					val app = apps.getApp(batchApp.packageName)
					ruStoreApp.toAppUpdate(app, downloadUrl)
				}.onFailure { exception ->
					when {
						exception is HttpException && exception.code() == 429 -> {
							hitRateLimit = true
							Log.w("RuStoreRepository", "Rate limit hit for ${batchApp.packageName}")
							markRateLimit()
						}
						exception is HttpException && exception.code() == 404 -> {
							markRuStore404(batchApp.packageName)
							Log.w("RuStoreRepository", "404 for ${batchApp.packageName}, caching")
						}
						else -> Log.e("RuStoreRepository", "Error checking ${batchApp.packageName}", exception)
					}
				}.getOrNull()

				AttemptResult(result, hitRateLimit)
			}
		}

		emit(updates)
	}.catch {
		emit(emptyList())
		Log.e("RuStoreRepository", "Error looking for updates.", it)
	}

	suspend fun search(text: String) = flow {
		val response = service.searchApps(text)
		if (response.code == "OK" && response.body != null) {
			val ignoredPackages = getActiveRuStore404Set()
			val filteredResults = response.body.content.filterNot { ignoredPackages.contains(it.packageName) }

			val updates = processAppsWithRateLimiting(filteredResults) { searchApp, markRateLimit ->
				retryOnRateLimit {
					var hitRateLimit = false
					val result = runCatching {
						val detailResponse = service.getAppInfo(searchApp.packageName)
						if (detailResponse.code != "OK" || detailResponse.body.appId == 0L) return@runCatching null

						clearRuStore404(searchApp.packageName)
						val details = detailResponse.body
						val downloadUrl = getDownloadUrl(details.appId)
						if (downloadUrl.isEmpty()) return@runCatching null

						details.toAppUpdate(null, downloadUrl)
					}.onFailure { exception ->
						when {
							exception is HttpException && exception.code() == 429 -> {
								hitRateLimit = true
								Log.w("RuStoreRepository", "Rate limit hit for ${searchApp.packageName}")
								markRateLimit()
							}
							exception is HttpException && exception.code() == 404 -> {
								markRuStore404(searchApp.packageName)
								Log.w("RuStoreRepository", "404 for ${searchApp.packageName}, caching")
							}
							else -> Log.e("RuStoreRepository", "Error fetching ${searchApp.packageName}", exception)
						}
					}.getOrNull()

					AttemptResult(result, hitRateLimit)
				}
			}
			emit(Result.success(updates))
		} else {
			emit(Result.success(emptyList()))
		}
	}.catch {
		emit(Result.failure(it))
		Log.e("RuStoreRepository", "Error searching.", it)
	}

	// --- Rate limiting ---

	private data class AttemptResult<R>(val value: R?, val hitRateLimit: Boolean)

	private suspend fun <T, R> processAppsWithRateLimiting(
		items: List<T>,
		processor: suspend (T, () -> Unit) -> R?
	): List<R> = coroutineScope {
		val results = mutableListOf<R>()
		val batchSize = if (shouldUseParallelBatches()) 2 else 1
		val batches = items.chunked(batchSize)

		batches.forEachIndexed { index, batch ->
			val chunkRateLimitHit = AtomicBoolean(false)

			val batchResults = batch.map { item ->
				async {
					processor(item) {
						chunkRateLimitHit.set(true)
						increaseDelay()
						lastRateLimitTimestamp = SystemClock.elapsedRealtime()
					}
				}
			}.awaitAll()

			results.addAll(batchResults.filterNotNull())

			if (chunkRateLimitHit.get()) {
				increaseDelay()
			} else {
				decreaseDelay()
			}

			if (index < batches.lastIndex) {
				delay(currentBatchDelayMs)
			}
		}
		results
	}

	private suspend fun <R> retryOnRateLimit(
		maxRetries: Int = MAX_RATE_LIMIT_RETRIES,
		attempt: suspend () -> AttemptResult<R>
	): R? {
		var retries = 0
		while (true) {
			val result = attempt()
			if (result.value != null) return result.value
			if (result.hitRateLimit && retries < maxRetries) {
				delay(currentBatchDelayMs)
				retries++
				continue
			}
			return null
		}
	}

	private suspend fun getDownloadUrl(appId: Long): String {
		return retryOnRateLimit {
			var hitRateLimit = false
			val result = runCatching {
				val request = RuStoreDownloadRequest(appId = appId)
				val response = service.getDownloadLink(request)
				if (response.code == "OK") {
					response.body.downloadUrls.firstOrNull()?.url ?: ""
				} else ""
			}.onFailure { exception ->
				when {
					exception is HttpException && exception.code() == 429 -> {
						hitRateLimit = true
						Log.w("RuStoreRepository", "Rate limit getting download for appId=$appId")
						increaseDelay()
						lastRateLimitTimestamp = SystemClock.elapsedRealtime()
					}
					exception is HttpException && exception.code() == 404 ->
						Log.w("RuStoreRepository", "Download 404 for appId=$appId")
					else -> Log.e("RuStoreRepository", "Error getting download for appId=$appId", exception)
				}
			}.getOrDefault("")

			AttemptResult(result.takeIf { it.isNotEmpty() }, hitRateLimit)
		} ?: ""
	}

	@Synchronized
	private fun increaseDelay() {
		currentBatchDelayMs = (currentBatchDelayMs + DELAY_STEP_MS).coerceAtMost(MAX_BATCH_DELAY_MS)
	}

	@Synchronized
	private fun decreaseDelay() {
		currentBatchDelayMs = (currentBatchDelayMs - DELAY_STEP_MS).coerceAtLeast(MIN_BATCH_DELAY_MS)
	}

	private fun shouldUseParallelBatches(): Boolean {
		val sinceLastRateLimit = SystemClock.elapsedRealtime() - lastRateLimitTimestamp
		return currentBatchDelayMs == MIN_BATCH_DELAY_MS && sinceLastRateLimit > RATE_LIMIT_COOLDOWN_MS
	}

	// --- 404 caching ---

	private fun getActiveRuStore404Set(): Set<String> {
		val now = System.currentTimeMillis()
		val entries = prefs.ruStore404Packages.get()
		val filtered = entries.filter { now - it.timestamp <= RUSTORE_404_TTL_MS }
		if (filtered.size != entries.size) {
			prefs.ruStore404Packages.put(filtered)
		}
		return filtered.map { it.packageName }.toSet()
	}

	private fun markRuStore404(identifier: Any) {
		val packageName = identifier.toString()
		val now = System.currentTimeMillis()
		val current = prefs.ruStore404Packages.get()
		val updated = current.filterNot { it.packageName == packageName } + RuStore404Entry(packageName, now)
		prefs.ruStore404Packages.put(updated)
	}

	private fun clearRuStore404(packageName: String) {
		val current = prefs.ruStore404Packages.get()
		val filtered = current.filterNot { it.packageName == packageName }
		if (filtered.size != current.size) {
			prefs.ruStore404Packages.put(filtered)
		}
	}

	// --- Filters ---

	private fun filterAlpha(version: String) = when {
		prefs.ignoreAlpha.get() && version.contains("alpha", true) -> false
		else -> true
	}

	private fun filterBeta(version: String) = when {
		prefs.ignoreBeta.get() && version.contains("beta", true) -> false
		else -> true
	}

}
