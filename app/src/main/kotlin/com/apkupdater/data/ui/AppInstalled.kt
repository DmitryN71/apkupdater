package com.apkupdater.data.ui

import android.net.Uri
import com.apkupdater.data.aptoide.ApksData
import com.apkupdater.util.toSha1Aptoide

data class AppInstalled(
	val name: String,
	val packageName: String,
	val version: String,
	val versionCode: Long,
	val iconUri: Uri = Uri.EMPTY,
	val ignored: Boolean = false,
	val signature: String = "",
	val signatureSha256: String = "",
	/** When the system last replaced this app. Sorting by it shows what changed behind you. */
	val lastUpdateTime: Long = 0L,
	val firstInstallTime: Long = 0L,
	/** Package that installed it, "" when nothing claims it — i.e. sideloaded. */
	val installer: String = "",
	/** Source.name this app installed it from, "" if it never did. Outranks [installer]. */
	val installedFrom: String = ""
)

fun List<AppInstalled>.getApp(packageName: String) = find { packageName == it.packageName }

fun List<AppInstalled>.getVersionCode(packageName: String) = getApp(packageName)
	?.versionCode
	?: 0L

fun List<AppInstalled>.getVersion(packageName: String) = getApp(packageName)
	?.version
	?: ""

fun List<AppInstalled>.getSignature(packageName: String) = getApp(packageName)
	?.signature
	.orEmpty()

fun List<AppInstalled>.getPackageNames() = filter { !it.ignored }.map { it.packageName }

private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/**
 * Whether [packageName] has the shape Android and store batch APIs accept (e.g. Google Play's
 * `/fdfe/bulkDetails` answers HTTP 400 DF-DFERH-01 to the whole batch if one entry has a hyphen,
 * space, or slash – such as an unlinked `owner/repo` watched entry or a custom-ROM overlay).
 * Inner segments may start with a digit (`ru.4pda.client`, `ru.2gis.general`).
 */
fun isPackageName(packageName: String): Boolean = PACKAGE_NAME_REGEX.matches(packageName)

fun AppInstalled.toApksData() = ApksData(
	packageName = packageName,
	vercode = versionCode.toString(),
	signature = signature.toSha1Aptoide(),
	isEnabled = true
)
