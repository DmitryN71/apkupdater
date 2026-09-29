package com.apkupdater.data.appgallery

import android.net.Uri
import androidx.core.net.toUri
import com.apkupdater.data.ui.AppGallerySource
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.util.formatIsoDate
import com.google.gson.annotations.SerializedName


/**
 * The AppGallery client protocol, as measured on 2026-09-28 — see the appgallery-api note.
 *
 * Every call is a form POST to `https://<zone host>/hwmarket/api/clientApi` and answers JSON with
 * an `rtnCode`, where 0 means success. All fields carry defaults: Gson builds these through the
 * no-arg constructor Kotlin generates when every parameter has one, so a field the server omits
 * keeps its default instead of becoming null under a non-null type.
 */
data class AppGalleryHandshake(
	val rtnCode: Int = -1,
	/** Required by every other call, and only obtainable here. */
	val sign: String = "",
	/** CN / RU / a DR2 country / anything else, which decides the host. */
	val serviceZone: String = ""
)

/** What `client.updateCheck` answers: one entry per app that HAS an update, and no download url. */
data class AppGalleryUpdatesResponse(
	val rtnCode: Int = -1,
	val count: Int = 0,
	val list: List<AppGalleryUpdateEntry> = emptyList()
)

data class AppGalleryUpdateEntry(
	// "package" is a Kotlin keyword, hence the rename here and in the request below.
	@SerializedName("package") val packageName: String = "",
	val name: String = "",
	/** The store id ("C102626077") — the only key `client.appDetailById` accepts. */
	val id: String = "",
	/** The version NAME, confusingly: "17.12.0". The number is [versionCode]. */
	val version: String = "",
	val versionCode: Long = 0L,
	val size: Long = 0L,
	val icon: String = "",
	val releaseDate: String = "",
	/**
	 * The download url, right here in the batch answer — which makes the detail call below
	 * unnecessary for almost every app. Both names appear; `downurl` is the one Alipay came
	 * with, `fullDownUrl` the one beside it, and either is a direct APK on the zone's CDN.
	 */
	val downurl: String = "",
	val fullDownUrl: String = "",
	val sha256: String = "",
	/** The release notes the store shows, in the app's own language. */
	val newFeatures: String = "",
	/**
	 * A per-app digest of Huawei's own, and NOT the app's signing certificate.
	 *
	 * It looked like the answer to "will this build install over what is already there", and
	 * build 161 filtered on it — which hid every AppGallery update, because it matches nothing
	 * we can compute. Measured on Telegram 12.10.4 downloaded from AppGallery: `sSha2` is
	 * ab63a7b5…, while the APK's real certificate is 49c15225… and its public key 795ad6d7…,
	 * and none of the SHA-1 or MD5 forms come close. Kept here only so the next person can see
	 * it is accounted for; do not compare it with AppInstalled.signatureSha256.
	 */
	val sSha2: List<String> = emptyList()
) {
	/** Whichever of the two url fields the server filled in. */
	fun downloadUrl() = downurl.ifEmpty { fullDownUrl }
}

/** What `client.appDetailById` answers. Only needed when a batch entry carries no url. */
data class AppGalleryDetailResponse(
	val rtnCode: Int = -1,
	val detailInfo: List<AppGalleryDetail> = emptyList()
)

data class AppGalleryDetail(
	@SerializedName("package") val packageName: String = "",
	val name: String = "",
	val versionName: String = "",
	val versionCode: Long = 0L,
	val size: Long = 0L,
	/** A direct APK on the zone's CDN. No referer, no token: any User-Agent is served. */
	val url: String = "",
	val sha256: String = "",
	/**
	 * The detail answer calls it `icoUri`; the update check and the search rows call the same
	 * picture `icon`. Reading only `icon` here left every search card with the placeholder robot
	 * until build 168.
	 */
	@SerializedName(value = "icoUri", alternate = ["icon"]) val icon: String = "",
	val releaseDate: String = ""
)

/**
 * The body of `client.updateCheck`, serialised into its `pkgInfo` parameter.
 *
 * The field names matter and are not the ones the ANSWER uses: `package`/`version` are accepted,
 * while `pkgName`/`versionCode` are answered with rtnCode 0 and an EMPTY list — a wrong shape
 * looks exactly like "nothing to update". [version] is the installed versionCode.
 */
data class AppGalleryUpdateRequest(val params: List<AppGalleryInstalled>)

data class AppGalleryInstalled(
	@SerializedName("package") val packageName: String,
	val version: Long
)

/** A card from the batch answer alone — the ordinary case, and no second request. */
fun AppGalleryUpdateEntry.toAppUpdate(app: AppInstalled?, url: String) = AppUpdate(
	name = name.ifEmpty { packageName },
	packageName = packageName,
	version = version,
	oldVersion = app?.version ?: "?",
	versionCode = versionCode,
	oldVersionCode = app?.versionCode ?: 0L,
	source = AppGallerySource,
	iconUri = if (icon.isNotEmpty()) icon.toUri() else Uri.EMPTY,
	link = Link.Url(url, size),
	whatsNew = newFeatures,
	updateDate = formatIsoDate(releaseDate),
	sourceUrl = if (id.isNotEmpty()) "https://appgallery.huawei.com/app/$id" else ""
)

/**
 * A card from the detail call: the fallback for an update entry that carried no url, and the
 * ordinary path for a search result, which starts from a store id and nothing else.
 */
fun AppGalleryDetail.toAppUpdate(app: AppInstalled?, storeId: String) = AppUpdate(
	name = name.ifEmpty { packageName },
	packageName = packageName,
	version = versionName,
	oldVersion = app?.version ?: "?",
	versionCode = versionCode,
	oldVersionCode = app?.versionCode ?: 0L,
	source = AppGallerySource,
	iconUri = if (icon.isNotEmpty()) icon.toUri() else Uri.EMPTY,
	link = Link.Url(url, size),
	updateDate = formatIsoDate(releaseDate),
	// The page a person can read: the store id is what its address is built from.
	sourceUrl = if (storeId.isNotEmpty()) "https://appgallery.huawei.com/app/$storeId" else ""
)

/**
 * What `client.getTabDetail` answers for `searchApp|<query>`: rows of layouts, each with a list
 * of apps that are sometimes wrapped in an `appInfo` object and sometimes not.
 *
 * Their search is a text search over names, and a fuzzy one — "Сбербанк" puts a Chinese store
 * app above СберБанк Онлайн — so the caller keeps the order but our own ranking sorts it out.
 * It cannot resolve a package name: asking it for "ru.sberbankmobile" returns games.
 */
data class AppGallerySearchResponse(
	val rtnCode: Int = -1,
	val layoutData: List<AppGalleryLayout> = emptyList()
)

data class AppGalleryLayout(val dataList: List<AppGallerySearchItem> = emptyList())

data class AppGallerySearchItem(
	val appid: String = "",
	val appId: String = "",
	@SerializedName("package") val packageName: String = "",
	val name: String = "",
	/** Some rows wrap the app in this; others are the app. See [app]. */
	val appInfo: AppGallerySearchItem? = null
) {
	/** The row itself, or the app inside it. */
	fun app(): AppGallerySearchItem = appInfo ?: this

	fun storeId(): String = appid.ifEmpty { appId }
}
