package com.apkupdater.util

import android.content.Context
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.WatchedApp
import com.apkupdater.prefs.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The apps followed without being installed (build 193) — see data/ui/WatchedApp.kt.
 *
 * One instance for the whole process, so Search, the Updates tab and Settings' import all see the
 * same list the moment one of them changes it. Every write goes through here, under one lock: the
 * list is read, changed and written back whole, and two writers interleaving would lose one.
 */
class WatchList(private val context: Context, private val prefs: Prefs) {

	private val lock = Any()
	private val _apps = MutableStateFlow(read())
	val apps: StateFlow<List<WatchedApp>> = _apps

	fun isWatched(packageName: String) = _apps.value.any { it.packageName == packageName }

	/** Starts following [update]'s app, with its version as the one already seen. */
	fun watch(update: AppUpdate) = edit { list ->
		if (list.any { it.packageName == update.packageName }) list
		else list + WatchedApp(
			packageName = update.packageName,
			name = update.name,
			iconUrl = update.iconUri.toString().takeIf { it.startsWith("http") }.orEmpty(),
			seenVersion = update.version,
			seenVersionCode = update.versionCode,
			seenAt = System.currentTimeMillis(),
			source = update.source.name,
			addedAt = System.currentTimeMillis()
		)
	}

	fun unwatch(packageName: String) = edit { list -> list.filterNot { it.packageName == packageName } }

	/**
	 * [version] is seen now. [versionCode] 0 — a source without codes — keeps the code seen before,
	 * or every source that does give codes would offer the same version again on the next check.
	 */
	fun markSeen(packageName: String, version: String, versionCode: Long) = edit { list ->
		list.map {
			if (it.packageName != packageName) it
			else it.copy(
				seenVersion = version,
				seenVersionCode = if (versionCode > 0L) versionCode else it.seenVersionCode,
				seenAt = System.currentTimeMillis()
			)
		}
	}

	/**
	 * The watched apps that are still not installed. One that has been installed since — from here
	 * or anywhere else — leaves the list: it is checked as an installed app from now on, and kept
	 * here as well it would show up on both sides of the Updates tab.
	 */
	fun notInstalled(): List<WatchedApp> {
		val installed = _apps.value.filter { isInstalled(it.packageName) }
		if (installed.isNotEmpty()) {
			AppJournal.add("WatchList", "Installed now, no longer watched: ${installed.joinToString { it.packageName }}")
			val gone = installed.mapTo(HashSet()) { it.packageName }
			edit { list -> list.filterNot { it.packageName in gone } }
		}
		return _apps.value
	}

	/** What [import] did with a list: added, left out as installed, and already watched. */
	data class ImportResult(val added: Int, val installed: Int, val known: Int)

	/**
	 * Adds another app's watchlist — App Watcher's export. Its installed apps are left out and counted:
	 * they are checked as installed apps anyway, and App Watcher lists those too.
	 */
	fun import(incoming: List<WatchedApp>): ImportResult {
		val apps = clean(incoming).distinctBy { it.packageName }
		val installed = apps.filter { isInstalled(it.packageName) }.mapTo(HashSet()) { it.packageName }
		var added = 0
		var known = 0
		edit { list ->
			val have = list.mapTo(HashSet()) { it.packageName }
			val candidates = apps.filter { it.packageName !in installed }
			val fresh = candidates.filter { it.packageName !in have }
			added = fresh.size
			known = candidates.size - fresh.size
			list + fresh
		}
		return ImportResult(added, installed.size, known)
	}

	/** Adds the entries of an imported settings file; an app already here keeps its own entry. */
	fun merge(incoming: List<WatchedApp>) = edit { list ->
		val known = list.mapTo(HashSet()) { it.packageName }
		list + clean(incoming).filter { it.packageName !in known }.distinctBy { it.packageName }
	}

	private fun edit(change: (List<WatchedApp>) -> List<WatchedApp>) = synchronized(lock) {
		val next = change(_apps.value)
		if (next != _apps.value) {
			prefs.watchedApps.put(next)
			_apps.value = next
		}
	}

	private fun read(): List<WatchedApp> = clean(runCatching { prefs.watchedApps.get() }.getOrElse { emptyList() })

	/**
	 * Rebuilt field by field: Gson fills a field missing from the stored JSON — or from a
	 * hand-edited settings file — with null, whatever Kotlin's types say.
	 */
	private fun clean(raw: List<WatchedApp?>): List<WatchedApp> = raw.mapNotNull { entry ->
		if (entry == null) return@mapNotNull null
		val packageName: String? = entry.packageName
		if (packageName.isNullOrBlank()) return@mapNotNull null
		val name: String? = entry.name
		val iconUrl: String? = entry.iconUrl
		val seenVersion: String? = entry.seenVersion
		val source: String? = entry.source
		WatchedApp(
			packageName = packageName,
			name = name.orEmpty(),
			iconUrl = iconUrl.orEmpty(),
			seenVersion = seenVersion.orEmpty(),
			seenVersionCode = entry.seenVersionCode,
			seenAt = entry.seenAt,
			source = source.orEmpty(),
			addedAt = entry.addedAt
		)
	}

	private fun isInstalled(packageName: String) =
		runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
}
