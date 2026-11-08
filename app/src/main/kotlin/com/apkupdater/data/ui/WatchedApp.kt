package com.apkupdater.data.ui

import android.net.Uri

/**
 * An app that is not installed but followed for new versions (build 193, asked for on 4PDA by
 * BehemothXxx — "like App Watcher"). Added from a search result; see util/WatchList.kt.
 *
 * The check treats it as installed at [seenVersion]: every source compares what it has against
 * that, exactly as against an installed version, so a new version anywhere shows up. "Seen" moves
 * on when the user marks a card as seen, and only then — a version looked at and left alone is
 * not offered again, the next one is.
 */
data class WatchedApp(
	val packageName: String = "",
	val name: String = "",
	/** The icon of the search result it was added from; "" when that had none. */
	val iconUrl: String = "",
	val seenVersion: String = "",
	/** 0 when the source it was seen in gives no version codes (GitHub). */
	val seenVersionCode: Long = 0L,
	/** When [seenVersion] was seen, epoch millis. */
	val seenAt: Long = 0L,
	/** Source.name of the result it was added from, for the row's caption. */
	val source: String = "",
	/**
	 * When it was added, epoch millis; 0 for entries older than the field. Seen does not move it, so
	 * the Watched side can tell an app no check has looked at yet from one with nothing new.
	 */
	val addedAt: Long = 0L
) {
	val iconUri: Uri get() = if (iconUrl.isEmpty()) Uri.EMPTY else Uri.parse(iconUrl)

	fun toAppInstalled() = AppInstalled(
		name = name,
		packageName = packageName,
		version = seenVersion,
		versionCode = seenVersionCode,
		iconUri = iconUri
	)
}
