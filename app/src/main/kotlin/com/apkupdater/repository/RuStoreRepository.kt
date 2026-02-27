package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.rustore.RuStoreBatchEntry
import com.apkupdater.data.rustore.RuStoreBatchRequest
import com.apkupdater.data.rustore.RuStoreDownloadRequest
import com.apkupdater.data.rustore.toAppUpdate
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.getApp
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.RuStoreService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow


class RuStoreRepository(
	private val service: RuStoreService,
	private val prefs: Prefs
) {

	suspend fun updates(apps: List<AppInstalled>) = flow {
		// Step 1: Batch check all apps at once (single request)
		val batchRequest = RuStoreBatchRequest(
			apps.map { RuStoreBatchEntry(it.packageName, it.versionCode) }
		)
		val batchResponse = service.getBatchUpdates(batchRequest)
		val appsWithUpdates = batchResponse.body.content

		if (appsWithUpdates.isEmpty()) {
			emit(emptyList())
			return@flow
		}

		// Step 2: For each app with an update, fetch details + download link (in parallel)
		val updates = coroutineScope {
			appsWithUpdates.map { batchApp ->
				async {
					runCatching {
						val detailResponse = service.getAppInfo(batchApp.packageName)
						if (detailResponse.code != "OK" || detailResponse.body.appId == 0L) return@async null

						val ruStoreApp = detailResponse.body
						if (!filterAlpha(ruStoreApp.versionName) || !filterBeta(ruStoreApp.versionName)) return@async null

						val downloadResponse = service.getDownloadLink(RuStoreDownloadRequest(ruStoreApp.appId))
						if (downloadResponse.code != "OK" || downloadResponse.body.apkUrl.isEmpty()) return@async null

						val app = apps.getApp(batchApp.packageName)
						ruStoreApp.toAppUpdate(app, downloadResponse.body.apkUrl)
					}.getOrNull()
				}
			}.awaitAll().filterNotNull()
		}

		emit(updates)
	}.catch {
		emit(emptyList())
		Log.e("RuStoreRepository", "Error looking for updates.", it)
	}

	suspend fun search(text: String) = flow {
		// RuStore does not have a public search API
		emit(Result.success(emptyList<AppUpdate>()))
	}.catch {
		emit(Result.failure(it))
		Log.e("RuStoreRepository", "Error searching.", it)
	}

	private fun filterAlpha(version: String) = when {
		prefs.ignoreAlpha.get() && version.contains("alpha", true) -> false
		else -> true
	}

	private fun filterBeta(version: String) = when {
		prefs.ignoreBeta.get() && version.contains("beta", true) -> false
		else -> true
	}

}
