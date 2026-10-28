package com.apkupdater.data.rustore

import android.net.Uri
import androidx.core.net.toUri
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.RuStoreSource


data class RuStoreApp(
	val appId: Long = 0L,
	val packageName: String = "",
	val appName: String = "",
	val versionName: String = "",
	val versionCode: Long = 0L,
	val shortDescription: String = "",
	val fullDescription: String = "",
	val iconUrl: String = "",
	val fileSize: Long = 0L,
	val companyName: String = "",
	val whatsNew: String = ""
)

/** [link] is the download as RuStore described it ([toLink]); its size is what will be fetched. */
fun RuStoreApp.toAppUpdate(app: AppInstalled?, link: Link) = AppUpdate(
	name = appName,
	packageName = packageName,
	version = versionName,
	oldVersion = app?.version ?: "?",
	versionCode = versionCode,
	oldVersionCode = app?.versionCode ?: 0L,
	source = RuStoreSource,
	iconUri = if (iconUrl.isNotEmpty()) iconUrl.toUri() else Uri.EMPTY,
	link = link,
	whatsNew = whatsNew,
	sourceUrl = "https://apps.rustore.ru/app/$packageName"
)
