package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.rustore.RuStoreDownloadRequest
import com.apkupdater.data.rustore.toAppUpdate
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.getApp
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.RuStoreService
import com.apkupdater.util.combine
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow


class RuStoreRepository(
	private val service: RuStoreService,
	private val prefs: Prefs
) {

	suspend fun updates(apps: List<AppInstalled>) = flow {
		val checks = mutableListOf<Flow<List<AppUpdate>>>()

		apps.forEach { app ->
			checks.add(checkApp(apps, app.packageName, app.version))
		}

		if (checks.isNotEmpty()) {
			checks.combine { all ->
				emit(all.flatMap { it })
			}.collect()
		} else {
			emit(emptyList())
		}
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

	private fun checkApp(
		apps: List<AppInstalled>,
		packageName: String,
		currentVersion: String
	) = flow {
		val response = service.getAppInfo(packageName)
		if (response.code == "OK" && response.body.appId != 0L) {
			val ruStoreApp = response.body
			if (ruStoreApp.versionName.isNotEmpty() && Version(ruStoreApp.versionName) > Version(currentVersion)) {
				if (filterAlpha(ruStoreApp.versionName) && filterBeta(ruStoreApp.versionName)) {
					val downloadResponse = service.getDownloadLink(RuStoreDownloadRequest(ruStoreApp.appId))
					if (downloadResponse.code == "OK" && downloadResponse.body.apkUrl.isNotEmpty()) {
						val app = apps.getApp(packageName)
						emit(listOf(ruStoreApp.toAppUpdate(app, downloadResponse.body.apkUrl)))
					} else {
						emit(emptyList())
					}
				} else {
					emit(emptyList())
				}
			} else {
				emit(emptyList())
			}
		} else {
			emit(emptyList())
		}
	}.catch {
		emit(emptyList())
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
