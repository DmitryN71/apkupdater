package com.apkupdater.data.ui

/**
 * One line of this app's own memory of where it installed something from.
 *
 * Under data/ so the Gson keep rule covers it; every field has a default so a record written by
 * an older or newer build still decodes. [source] is Source.name, which is what the update cards
 * compare against.
 */
data class InstallRecord(
	val packageName: String = "",
	val source: String = "",
	val versionCode: Long = 0L,
	val time: Long = 0L
)
