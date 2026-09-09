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
	val updateDate: String = "",
	/**
	 * The source said this is a pre-release, whatever the version string looks like. Only
	 * GitHub can tell us — its releases carry the flag — and it is worth having, because a
	 * project can perfectly well publish "2.0.0" with the flag set and nothing in the name.
	 */
	val isPreRelease: Boolean = false,
	/** Source.name this app was last installed from BY US, "" if never. See util/InstallRecords.kt. */
	val installedFrom: String = "",
	val id: Int = "${source.name}.$packageName.$versionCode.$version".hashCode()
) {
	/**
	 * Derived rather than stored, so no source transform has to be taught about it and no
	 * existing call site changes. Cheap: two or three regex scans of a short string, and only
	 * for the cards actually on screen.
	 */
	val releaseType: ReleaseType get() = ReleaseType.from(version, isPreRelease)
}

fun List<AppUpdate>.indexOf(id: Int) = indexOfFirst { it.id == id }

/**
 * After [done] was installed, every card of the same package — this source's and the others' —
 * learns where the app now comes from, so the caption is right immediately and not only after
 * the next refresh.
 */
fun List<AppUpdate>.markInstalledFrom(done: AppUpdate?): List<AppUpdate> =
	if (done == null) this
	else map { if (it.packageName == done.packageName) it.copy(installedFrom = done.source.name) else it }

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
