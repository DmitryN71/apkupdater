package com.apkupdater.data.ui

import android.net.Uri

data class AppUpdate(
	val name: String,
	val packageName: String,
	val version: String,
	val oldVersion: String,
	val versionCode: Long,
	val oldVersionCode: Long,
	val source: Source,
	val iconUri: Uri = Uri.EMPTY,
	val link: Link = Link.Empty,
	val whatsNew: String = "",
	val isInstalling: Boolean = false,
	val isInstalled: Boolean = false,
	val total: Long = 0L,
	val progress: Long = 0L,
	val sourceUrl: String = "",
	val id: Int = "${source.name}.$packageName.$versionCode.$version".hashCode()
)

fun List<AppUpdate>.indexOf(id: Int) = indexOfFirst { it.id == id }

fun MutableList<AppUpdate>.setIsInstalling(id: Int, b: Boolean): List<AppUpdate> {
	val index = this.indexOf(id)
	if (index != -1) {
		this[index] = this[index].copy(isInstalling = b)
	}
	return this
}

fun MutableList<AppUpdate>.removeId(id: Int): List<AppUpdate> {
	val index = this.indexOf(id)
	if (index != -1) this.removeAt(index)
	return this
}

fun MutableList<AppUpdate>.setIsInstalled(id: Int): List<AppUpdate> {
	val index = this.indexOf(id)
	if (index != -1) {
		this[index] = this[index].copy(isInstalled = true, isInstalling = false, progress = 0L, total = 0L)
	}
	return this
}

fun MutableList<AppUpdate>.setProgress(progress: AppInstallProgress): MutableList<AppUpdate> {
	val index = this.indexOf(progress.id)
	if (index != -1) {
		var item = this[index]
		progress.total?.let { item = item.copy(total = it) }
		progress.progress?.let { p ->
			// If downloaded bytes exceed reported total (e.g. RuStore size mismatch), update total
			val newTotal = if (item.total in 1 until p) p else item.total
			item = item.copy(progress = p, total = newTotal)
		}
		this[index] = item
	}
	return this
}
