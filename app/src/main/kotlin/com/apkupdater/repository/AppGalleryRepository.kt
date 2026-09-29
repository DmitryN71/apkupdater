package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.appgallery.AppGalleryInstalled
import com.apkupdater.data.appgallery.AppGalleryUpdateEntry
import com.apkupdater.data.appgallery.AppGalleryUpdateRequest
import com.apkupdater.data.appgallery.toAppUpdate
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.getApp
import com.apkupdater.data.ui.getVersionCode
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.AppGalleryService
import com.apkupdater.util.AppGallerySession
import com.google.gson.Gson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.io.IOException


/**
 * Huawei AppGallery, in the same two steps as RuStore: one batch request naming every installed
 * package, then a detail request for each app that has an update — the batch answers versions
 * but no download url.
 *
 * Worth having for what it carries: Russian banking and state apps that left Google Play are
 * published here, and a check of 61 packages found 30 of them.
 */
class AppGalleryRepository(
	private val service: AppGalleryService,
	private val session: AppGallerySession,
	private val gson: Gson,
	private val prefs: Prefs
) {

	suspend fun updates(apps: List<AppInstalled>) = flow {
		val current = session.current() ?: throw IOException("AppGallery handshake failed")
		val url = session.apiUrl(current.host)

		// One request for everything installed. The shape of pkgInfo is unforgiving: the keys
		// have to be `package`/`version`, and a wrong name is answered with rtnCode 0 and an
		// empty list rather than an error — "no updates" that is really "you asked wrongly".
		val request = AppGalleryUpdateRequest(
			apps.map { AppGalleryInstalled(it.packageName, it.versionCode) }
		)
		val response = service.updateCheck(
			url,
			session.params(
				current.sign,
				mapOf("method" to "client.updateCheck", "pkgInfo" to gson.toJson(request))
			)
		)
		if (response.rtnCode != 0) {
			throw IOException("AppGallery update check refused with rtnCode ${response.rtnCode}")
		}

		val offered = response.list
			.filter { it.packageName.isNotEmpty() }
			// The store already compared against the versions we sent; this is the same guard
			// every other source has, against an answer that is somehow not newer after all.
			// It does most of the work here: AppGallery often carries an OLDER build than Play,
			// and 144 answers came down to 4 on one of Dmitry's checks because of this line.
			.filter { it.versionCode > apps.getVersionCode(it.packageName) }
			.filter { filterAlpha(it.version) && filterBeta(it.version) }

		// Almost always free: the batch answer already carries the download url, so a card needs
		// no second request. The detail call is the fallback for an entry that came without one.
		val updates = offered.mapNotNull { entry ->
			val direct = entry.downloadUrl()
			if (direct.isNotEmpty()) {
				entry.toAppUpdate(apps.getApp(entry.packageName), direct)
			} else if (entry.id.isNotEmpty()) {
				resolve(url, current.sign, entry, apps)
			} else {
				null
			}
		}
		Log.i(
			"AppGalleryRepository",
			"AppGallery on ${current.host}: ${response.list.size} offered, ${updates.size} usable of ${apps.size} installed."
		)
		emit(updates)
	}.catch {
		Log.e("AppGalleryRepository", "Error looking for updates.", it)
		// Rethrown so a failure counts as a failed source rather than "no updates" — see
		// SourceFailure.kt and build 156.
		throw it
	}

	/** The download url, and with it a card. Null when the detail call fails or offers no file. */
	private suspend fun resolve(
		url: String,
		sign: String,
		entry: AppGalleryUpdateEntry,
		apps: List<AppInstalled>
	): AppUpdate? = runCatching {
		val detail = service.detail(
			url,
			session.params(sign, mapOf("method" to "client.appDetailById", "id" to entry.id))
		).detailInfo.firstOrNull()
		if (detail == null || detail.url.isEmpty()) {
			Log.w("AppGalleryRepository", "No download url for ${entry.packageName} (${entry.id}).")
			return null
		}
		detail.toAppUpdate(apps.getApp(entry.packageName), entry.id)
	}.getOrElse {
		// One app's detail failing is not the source failing: the others still have their cards.
		Log.w("AppGalleryRepository", "Details failed for ${entry.packageName}", it)
		null
	}

	/**
	 * The store's own search, then one detail call per result for its version and download url.
	 *
	 * Capped at [SEARCH_LIMIT]: the answer holds twenty-odd apps, most of them only loosely
	 * related — their search matches names, fuzzily — and each one costs a request. Our own
	 * ranking in SearchRepository puts the relevant ones first afterwards.
	 */
	suspend fun search(text: String) = flow {
		val current = session.current() ?: throw IOException("AppGallery handshake failed")
		val url = session.apiUrl(current.host)
		// The language is decided by the handshake (see AppGallerySession.localeFor); sending the
		// same locale here only keeps every request of the session saying the same thing.
		val locale = session.localeFor(current.host)
		val response = service.search(
			url,
			session.params(
				current.sign,
				mapOf(
					"method" to "client.getTabDetail",
					"uri" to "searchApp|$text",
					"maxResults" to "25",
					"reqPageNum" to "1",
					"isSupportPage" to "1",
					"locale" to locale
				)
			)
		)
		if (response.rtnCode != 0) {
			throw IOException("AppGallery search refused with rtnCode ${response.rtnCode}")
		}
		val found = response.layoutData
			.flatMap { it.dataList }
			.map { it.app() }
			.filter { it.packageName.isNotEmpty() && it.storeId().isNotEmpty() }
			.distinctBy { it.packageName }
			.take(SEARCH_LIMIT)
		val updates = found.mapNotNull { item ->
			runCatching {
				val detail = service.detail(
					url,
					session.params(
						current.sign,
						mapOf("method" to "client.appDetailById", "id" to item.storeId(), "locale" to locale)
					)
				).detailInfo.firstOrNull()
				detail?.takeIf { it.url.isNotEmpty() }?.toAppUpdate(null, item.storeId())
			}.getOrElse {
				Log.w("AppGalleryRepository", "Search details failed for ${item.packageName}", it)
				null
			}
		}
		Log.i(
			"AppGalleryRepository",
			"AppGallery search on ${current.host} ($locale): ${found.size} found, ${updates.size} with a download."
		)
		emit(Result.success(updates))
	}.catch {
		Log.e("AppGalleryRepository", "Error searching.", it)
		emit(Result.failure(it))
	}

	private fun filterAlpha(version: String) = when {
		prefs.ignoreAlpha.get() && version.contains("alpha", true) -> false
		else -> true
	}

	private fun filterBeta(version: String) = when {
		prefs.ignoreBeta.get() && version.contains("beta", true) -> false
		else -> true
	}

	companion object {
		/** How many search results are worth a detail call each. */
		private const val SEARCH_LIMIT = 10
	}

}
