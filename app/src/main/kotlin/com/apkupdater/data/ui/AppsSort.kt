package com.apkupdater.data.ui

import androidx.annotation.StringRes
import com.apkupdater.R

/**
 * How the installed-app list is ordered.
 *
 * Stored as an ordinal in Prefs.appsSortOrder, so the ORDER OF THESE ENTRIES IS PART OF THE
 * SAVED DATA — append, never insert. Name stays first because it is the default and the only
 * one that reads the same on every device.
 */
enum class AppsSort(@StringRes val labelRes: Int) {
	Name(R.string.sort_by_name),
	RecentlyUpdated(R.string.sort_recently_updated),
	RecentlyInstalled(R.string.sort_recently_installed);

	companion object {
		/** Never throws on a value written by a future build, or by a hand-edited config. */
		fun from(ordinal: Int) = entries.getOrElse(ordinal) { Name }
	}
}

/**
 * The repository already hands the list over sorted by name, so that case is returned untouched
 * rather than sorted a second time.
 */
fun List<AppInstalled>.orderedBy(sort: AppsSort): List<AppInstalled> = when (sort) {
	AppsSort.Name -> this
	AppsSort.RecentlyUpdated -> sortedByDescending { it.lastUpdateTime }
	AppsSort.RecentlyInstalled -> sortedByDescending { it.firstInstallTime }
}
