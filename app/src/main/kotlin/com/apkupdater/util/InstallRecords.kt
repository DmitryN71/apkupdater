package com.apkupdater.util

import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.InstallRecord
import com.apkupdater.prefs.Prefs

/**
 * Where each app on this device was last installed from — as far as THIS app did the installing.
 *
 * Android's own installer field cannot answer that, and it is this app that spoils it: a session
 * install records com.apkupdater as the installer, the "Installer — Play Store" mode tells the
 * system it was Google Play whatever the truth was, and root or Shizuku without it leaves the
 * field empty. Half our sources — ApkMirror, ApkPure, GitHub, GitLab — are sites, not installer
 * apps, and never leave a trace at all. So the only honest record is one we keep ourselves, at
 * the one moment the origin is certain: when an install we started succeeds.
 *
 * Two readers today. The update cards say "installed from here" on the source the user actually
 * used last time — the list deliberately shows one app from several sources, and this saves
 * choosing blind. And Copy App Logs leads with it, because "where did you install it from?" is
 * the question every forum report begins with and the reporter usually cannot answer.
 *
 * Kept per package, one entry each; pruned against the installed list so it cannot grow for
 * ever. The lock is because installs finish on the background scope and prunes run on IO.
 */
private val recordsLock = Any()

fun Prefs.recordInstall(update: AppUpdate) = synchronized(recordsLock) {
	val record = InstallRecord(
		update.packageName, update.source.name, update.versionCode, System.currentTimeMillis()
	)
	installRecords.put(installRecords.get().filterNot { it.packageName == update.packageName } + record)
}

/** packageName → Source.name, for stamping a freshly built list of cards. */
fun Prefs.installedFromMap(): Map<String, String> =
	installRecords.get().associate { it.packageName to it.source }

/** Drops records for packages no longer on the device. Give it the COMPLETE installed list. */
fun Prefs.pruneInstallRecords(installed: Set<String>) = synchronized(recordsLock) {
	val current = installRecords.get()
	val kept = current.filter { it.packageName in installed }
	if (kept.size != current.size) installRecords.put(kept)
}

/** Newest first, one per line, for the log dump. */
fun Prefs.installRecordsReport(): String = installRecords.get()
	.sortedByDescending { it.time }
	.joinToString("\n") { "${it.packageName}  <-  ${it.source}  (${it.versionCode}, ${formatDate(it.time)})" }
