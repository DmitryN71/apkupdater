package com.apkupdater.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.apkupdater.R
import com.apkupdater.data.snack.TextSnack
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.UpdatesUiState
import com.apkupdater.data.ui.removeId
import com.apkupdater.data.ui.setIsInstalled
import com.apkupdater.data.ui.setIsInstalling
import com.apkupdater.data.ui.setProgress
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.UpdatesRepository
import com.apkupdater.service.RuStoreService
import com.apkupdater.util.AppVisibility
import com.apkupdater.util.BackgroundInstaller
import com.apkupdater.util.Badger
import com.apkupdater.util.clearDownloadCacheBytes
import com.apkupdater.util.downloadCacheSizeBytes
import com.apkupdater.util.Downloader
import com.apkupdater.util.UpdatesNotification
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.apkupdater.util.launchWithMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger


class UpdatesViewModel(
	private val updatesRepository: UpdatesRepository,
	private val installer: SessionInstaller,
	private val prefs: Prefs,
	private val badger: Badger,
	downloader: Downloader,
	snackBar: SnackBar,
	stringer: Stringer,
	installLog: InstallLog,
	ruStoreService: RuStoreService,
	context: Context,
	background: BackgroundInstaller,
	notification: UpdatesNotification
) : InstallViewModel(downloader, installer, prefs, snackBar, stringer, installLog, ruStoreService, context, background, notification) {

	private val mutex = Mutex()
	private val installMutex = Mutex()
	private val state = MutableStateFlow<UpdatesUiState>(UpdatesUiState.Loading())
	private val _refreshProgress = MutableStateFlow<String?>(null)
	val refreshProgress: StateFlow<String?> = _refreshProgress

	/**
	 * True while an update check is running, so the Refresh button can spin instead of sitting
	 * idle next to a separate indicator — and so tapping it can stop the check. Worth stopping:
	 * one slow source (F-Droid Main, reliably) holds the whole check up long after the others
	 * have answered.
	 */
	private val _isChecking = MutableStateFlow(false)
	val isChecking: StateFlow<Boolean> = _isChecking

	/**
	 * How far the running check has got, 0f..1f, or null while that is not yet known.
	 *
	 * The sources have been reporting their completion counts since build 140 — the Loading
	 * state has carried `completed` and `total` all along and nothing has ever read them. The
	 * button fills a ring with them now instead of turning meaninglessly. Null covers the two
	 * moments where a fraction would be a lie: before the app list is read, when even the
	 * number of sources is unknown, and at nought answered, where an empty ring on a
	 * just-pressed button reads as a freeze. Both spin instead.
	 */
	private val _checkProgress = MutableStateFlow<Float?>(null)
	val checkProgress: StateFlow<Float?> = _checkProgress
	private var refreshJob: Job? = null
	/** Bumped by every new check and by every cancel, so a superseded one keeps its hands off. */
	private val refreshGeneration = AtomicInteger(0)

	/**
	 * Everything the sources have answered with so far in the running check.
	 *
	 * Deliberately NOT published as it arrives. Publishing one source at a time would make the
	 * list shrink and then re-sort under the user on every answer, and a list that reorders
	 * itself mid-read is what build 130 removed for stranding D-pad focus on a television.
	 * It is kept here so that stopping the check can show what was found instead of nothing.
	 */
	@Volatile
	private var partialResults: List<AppUpdate> = emptyList()

	/**
	 * Aborts a running check. Whatever the sources already returned stays on screen.
	 *
	 * The screen is put right HERE rather than in the job's own finally, because cancelling a
	 * coroutine does not stop it — it asks it to stop, and the job stays alive until its
	 * children unwind. A source in the middle of a long blocking read can take a moment, and
	 * waiting for that left the button still spinning after the tap, which read as "the button
	 * does nothing". The finally checks the generation and does not undo any of this.
	 */
	fun cancelRefresh() {
		val job = refreshJob ?: return
		refreshJob = null
		refreshGeneration.incrementAndGet()
		job.cancel()
		_isChecking.value = false
		_refreshProgress.value = null
		_checkProgress.value = null
		// What the sources managed to answer with, if any of them did. Stopping a check with
		// only one slow source left used to publish an empty list and claim "All up to date",
		// because the results of the eight that had already answered lived nowhere the screen
		// could see them. Falling back to the list Loading is carrying covers the other case:
		// stopped before anything answered at all, so put back what was on screen before.
		if (partialResults.isNotEmpty()) {
			setSuccess(partialResults)
		} else {
			state.update {
				if (it is UpdatesUiState.Loading) UpdatesUiState.Success(it.updates) else it
			}
			badger.changeUpdatesBadge(state.value.updates().size.toString())
		}
	}

	private val _cacheSize = MutableStateFlow(0L)
	val cacheSize: StateFlow<Long> = _cacheSize

	fun refreshCacheSize() = viewModelScope.launch(Dispatchers.IO) {
		_cacheSize.value = context.downloadCacheSizeBytes()
	}

	fun clearCache() = viewModelScope.launch(Dispatchers.IO) {
		// The other door into the download directory, and the only one that also wipes the
		// resumable partials. Tapping the chip during "Update all" would delete the APKs of
		// everything still queued behind the install lock — the exact failure the sweep guard
		// in Downloader.cleanUp() exists to prevent, reached from a different button.
		if (background.tasks.value.isNotEmpty()) {
			snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.cache_busy)))
			return@launch
		}
		context.clearDownloadCacheBytes()
		_cacheSize.value = 0L
	}

	init {
		subscribeToInstallStatus { state.value.updates() }
		subscribeToInstallProgress { progress ->
			state.update { it.withUpdates(it.mutableUpdates().setProgress(progress)) }
		}
	}

	fun state(): StateFlow<UpdatesUiState> = state

	fun refresh(load: Boolean = true): Job {
		// One check at a time. A second call would only queue behind the mutex, and refreshJob
		// would then point at the QUEUED job — so Stop would cancel something that had not
		// started while the real check ran on, with the button spinning and the button dead.
		// Returning the running job also keeps MainViewModel's invokeOnCompletion honest.
		// Safe without locking: every caller reaches this on the main thread.
		refreshJob?.takeIf { it.isActive }?.let { return it }
		val mine = refreshGeneration.incrementAndGet()
		val job = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
			_isChecking.value = true
			_checkProgress.value = null
			var emitted = false
			partialResults = emptyList()
			try {
				// The WHOLE list is carried into Loading, not just the in-flight cards.
				//
				// setSuccess() looks here for downloads and installs still running, so a Refresh
				// during a download does not bring their cards back reading "Update" — that part
				// would only need the in-flight ones. The rest is carried because a check that
				// fails before ANY source answers never reaches setSuccess, and then this is all
				// the finally block has to put back on screen. setSuccess re-filters to in-flight
				// inside its own update block, so carrying everything costs nothing normally.
				if (load) state.update { current ->
					UpdatesUiState.Loading(updates = current.updates())
				}
				_refreshProgress.value = stringer.get(R.string.checking_updates)
				badger.changeUpdatesBadge("")
				updatesRepository.updates(
					onSourceError = { errors, total ->
						if (refreshGeneration.get() == mine) {
							snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.source_errors, errors, total)))
						}
					},
					onSourceComplete = { completed, total, remaining ->
						// Same guard as the finally below, and for the same reason. A stopped
						// check keeps unwinding long after the tap, and every source still
						// running reports its completion on the way out — writing the banner
						// back over the blank one cancelRefresh had just set. Nothing clears it
						// afterwards, because the finally is generation-guarded too, so the
						// stale "Checking: <source>" stayed up until the app was restarted.
						if (refreshGeneration.get() == mine) {
							_refreshProgress.value = if (remaining.isNotEmpty()) {
								stringer.get(R.string.checking_sources, remaining.joinToString(", "))
							} else null
							_checkProgress.value =
								if (total > 0 && completed > 0) completed.toFloat() / total else null
							// copy(), not a fresh Loading: a new one would drop the in-flight
							// cards this state is carrying. Inside update{} so the check and the
							// write cannot be separated by a concurrent install result landing
							// between them.
							state.update {
								if (it is UpdatesUiState.Loading) it.copy(completed = completed, total = total)
								else it
							}
						}
					}
				).collect {
					// One emission per source now, each carrying everything found so far.
					// Recorded rather than published — see partialResults for why — and the
					// banner is left to onSourceComplete, which clears it when nothing is
					// left. The last emission is the complete answer, published below.
					emitted = true
					partialResults = it
				}
				if (emitted) setSuccess(partialResults)
				refreshCacheSize()
			} finally {
				// Only if this is still the current check. A stopped one can take a while to
				// unwind, and by the time it gets here the user may have started another —
				// clearing the flag or replacing the list then would break the new one.
				if (refreshGeneration.get() == mine) {
					_isChecking.value = false
					_refreshProgress.value = null
					_checkProgress.value = null
					// The net for a check that published nothing — it failed before any source
					// answered, so the state is still Loading. Put back the list the shimmer
					// replaced rather than leaving the shimmer up for good. A finished check
					// has already published Success, and a stopped one was handled by
					// cancelRefresh, so in both of those this is a no-op.
					state.update {
						if (it is UpdatesUiState.Loading) UpdatesUiState.Success(it.updates) else it
					}
					badger.changeUpdatesBadge(state.value.updates().size.toString())
				}
			}
		}
		refreshJob = job
		return job
	}

	fun hideUpdate(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val updated = state.updateAndGet { it.withUpdates(it.mutableUpdates().removeId(id)) }
		badger.changeUpdatesBadge(updated.updates().size.toString())
	}

	fun ignoreVersion(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val ignored = prefs.ignoredVersions.get().toMutableList()
		if (ignored.contains(id)) ignored.remove(id) else ignored.add(id)
		prefs.ignoredVersions.put(ignored)
		setSuccess(state.value.mutableUpdates())
	}

	override fun cancelInstall(id: Int): Job {
		// Released BEFORE queuing behind the refresh mutex. It guards nothing that mutex
		// protects, and refresh() holds that mutex for a whole multi-source check — so the
		// next install in a batch sat suspended on the commit lock until the check finished.
		installer.finish(id)
		return viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
			state.update { it.withUpdates(it.mutableUpdates().setIsInstalling(id, false)) }
		}
	}

	override fun finishInstall(id: Int): Job {
		// Same reason as cancelInstall: never behind the refresh mutex.
		installer.finish(id)
		return viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
			// Deliberately NOT sorted here any more. Floating a finished app to the top made
			// the whole list jump the instant an install completed — everything below shifted,
			// and on a D-pad that strands the focus somewhere the user did not put it. The
			// ordering still happens in setSuccess(), i.e. on the next refresh, so a long list
			// still gathers the handled ones at the top; it just stops moving under the user
			// mid-session. A finished card is already obvious in place: it turns tertiary and
			// its button becomes Open.
			val updated = state.updateAndGet { it.withUpdates(it.mutableUpdates().setIsInstalled(id)) }
			badger.changeUpdatesBadge(updated.updates().count { !it.isInstalled }.toString())
		}
	}

	// Install work runs in the process-lifetime background scope (not viewModelScope)
	// so it survives leaving the app; begin()/end() keep the foreground service alive.
	override fun downloadAndRootInstall(update: AppUpdate) = background.scope.launch {
		background.begin(update.id, update.name)
		try {
			state.update { it.withUpdates(it.mutableUpdates().setIsInstalling(update.id, true)) }
			val link = resolveLink(update)
			if (link !is com.apkupdater.data.ui.Link.Url) {
				snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.root_install_not_supported)))
				cancelInstall(update.id)
				return@launch
			}
			// Download in parallel (no mutex) — rootInstall() deletes the file itself
			val file = runCatching { downloader.downloadFile(link.link, update.id) }.getOrElse {
				snackInstallFailure(update.name, it, update.id)
				cancelInstall(update.id)
				return@launch
			}
			// The download is done and the file is the installer's now: from here a Cancel would
			// stop nothing, and setting its flag would go on to mute this install's own failure.
			downloader.beginInstall(update.id)
			// Same package guard as the standard path (root installs via pm install too).
			val wrongPackage = installer.verifyPackage(file, update.packageName)
			if (wrongPackage != null) {
				file.delete()
				snackBar.snackBar(viewModelScope, TextSnack(
					stringer.get(R.string.install_error_wrong_package, wrongPackage),
					type = com.apkupdater.data.snack.SnackType.ERROR))
				cancelInstall(update.id)
				return@launch
			}
			// Install sequentially
			installMutex.withLock {
				runCatching {
					val fake = prefs.fakePlayStore.get()
					// Returns the reason now instead of a bare Boolean, so a root failure finally
					// says what pm said — and a root SUCCESS finally says anything at all, which
					// it never did on this path.
					val error = installer.rootInstall(file, fake)
					if (error == null) {
						// Only when the user is looking. notifyInstalledIfBackground posts a
						// notification otherwise, and emitting both meant a backgrounded success
						// arrived twice: as a notification, then again as a queued message.
						if (notifyOnInstall() && AppVisibility.foreground) {
							snackBar.snackBar(viewModelScope, TextSnack(
								stringer.get(R.string.install_success, update.name),
								type = com.apkupdater.data.snack.SnackType.SUCCESS))
						}
						notifyInstalledIfBackground(update)
						finishInstall(update.id)
					} else {
						file.delete()
						reportFailure(update.name, error, update.id)
						cancelInstall(update.id)
					}
				}.getOrElse {
					file.delete()
					snackInstallFailure(update.name, it, update.id)
					cancelInstall(update.id)
				}
			}
		} finally {
			background.end(update.id)
		}
	}

	override fun downloadAndShizukuInstall(update: AppUpdate) = background.scope.launch {
		background.begin(update.id, update.name)
		try {
		state.update { it.withUpdates(it.mutableUpdates().setIsInstalling(update.id, true)) }
		val link = resolveLink(update)
		// Download in parallel (no mutex) — shizuku install methods delete files themselves
		val files = runCatching {
			when (link) {
				is com.apkupdater.data.ui.Link.Url -> {
					val file = downloader.downloadFile(link.link, update.id) { progress, total ->
						installLog.emitProgress(AppInstallProgress(update.id, progress, total))
					}
					listOf(file)
				}
				is com.apkupdater.data.ui.Link.Xapk -> {
					val file = downloader.downloadFile(link.link, update.id) { progress, total ->
						installLog.emitProgress(AppInstallProgress(update.id, progress, total))
					}
					listOf(file)
				}
				is com.apkupdater.data.ui.Link.Play -> {
					val playFiles = link.getInstallFiles()
					// Belt only: a refusal throws since gplayapi 3.6 and is worded by
					// playErrorResId; an empty list here would be a library change.
					if (playFiles.isEmpty()) {
						snackBar.snackBar(viewModelScope, TextSnack(
							stringer.get(R.string.play_no_files),
							type = com.apkupdater.data.snack.SnackType.ERROR))
						cancelInstall(update.id)
						return@launch
					}
					val totalSize = playFiles.sumOf { it.size }
					if (totalSize > 0) installLog.emitProgress(AppInstallProgress(update.id, 0L, totalSize))
					var downloadedSoFar = 0L
					playFiles.map { playFile ->
						val offset = downloadedSoFar
						val file = downloader.downloadFile(playFile.url, update.id) { progress, _ ->
							if (totalSize > 0) installLog.emitProgress(AppInstallProgress(update.id, offset + progress, totalSize))
						}
						downloadedSoFar += file.length()
						file
					}
				}
				else -> {
					snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.shizuku_install_not_supported)))
					cancelInstall(update.id)
					return@launch
				}
			}
		}.getOrElse {
			// Clear the bar too, not just the button. Without this the card kept the
			// percentage it died at, so restarting the download flashed the old figure
			// for an instant before jumping back to 0 — it read like a failed resume.
			installLog.emitProgress(AppInstallProgress(update.id, 0L))
			snackInstallFailure(update.name, it, update.id)
			cancelInstall(update.id)
			return@launch
		}
		// Downloads are done; the wait for the install lock below can be long, and a Cancel
		// pressed during it would stop nothing while muting the failure message.
		downloader.beginInstall(update.id)
		// Install sequentially
		installMutex.withLock {
			runCatching {
				val fake = prefs.fakePlayStore.get()
				// Same package guard as the standard path — Shizuku installs via
				// pm install and would otherwise let a wrong-channel APK
				// (e.g. Brave Nightly over Beta) install alongside.
				val wrongPackage = if (link is com.apkupdater.data.ui.Link.Url)
					installer.verifyPackage(files.first(), update.packageName) else null
				if (wrongPackage != null) {
					files.forEach { it.delete() }
					snackBar.snackBar(viewModelScope, TextSnack(
						stringer.get(R.string.install_error_wrong_package, wrongPackage),
						type = com.apkupdater.data.snack.SnackType.ERROR))
					installLog.emitProgress(AppInstallProgress(update.id, 0L))
					cancelInstall(update.id)
					return@runCatching
				}
				val error = when (link) {
					is com.apkupdater.data.ui.Link.Xapk -> installer.shizukuInstallXapk(files.first(), fake)
					is com.apkupdater.data.ui.Link.Play -> installer.shizukuInstallSplit(files, fake)
					else -> installer.shizukuInstall(files.first(), fake)
				}
				if (error == null) {
					if (notifyOnInstall() && AppVisibility.foreground) {
						snackBar.snackBar(viewModelScope, TextSnack(
							stringer.get(R.string.install_success, update.name),
							type = com.apkupdater.data.snack.SnackType.SUCCESS))
					}
					notifyInstalledIfBackground(update)
					finishInstall(update.id)
				} else {
					files.forEach { it.delete() }
					reportFailure(update.name, error, update.id)
					installLog.emitProgress(AppInstallProgress(update.id, 0L))
					cancelInstall(update.id)
				}
			}.getOrElse {
				files.forEach { it.delete() }
				// Was silent: every sibling branch in this function reports, this one did not.
				snackInstallFailure(update.name, it, update.id)
				cancelInstall(update.id)
			}
		}
		} finally {
			background.end(update.id)
		}
	}

	override fun downloadAndInstall(update: AppUpdate) = background.scope.launch {
		if (installer.checkPermission()) {
			background.begin(update.id, update.name)
			try {
				state.update { it.withUpdates(it.mutableUpdates().setIsInstalling(update.id, true)) }
				// No ViewModel mutex — downloads run in parallel.
				// SessionInstaller has its own mutex for commit sequencing.
				val link = resolveLink(update)
				downloadAndInstall(update.id, update.packageName, link, update.name)
			} finally {
				background.end(update.id)
			}
		}
	}

	fun installAll(
		uriHandler: androidx.compose.ui.platform.UriHandler,
		notificationPermission: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>? = null
	) {
		// ApkMirror has no direct download — install() opens its page in the browser — so
		// "Update all" used to open one browser tab per ApkMirror update on top of the real
		// installs. Those stay a manual tap.
		val updates = state.value.updates().filter {
			!it.isInstalling && !it.isInstalled && it.source != com.apkupdater.data.ui.ApkMirrorSource
		}
		// Only the first carries the launcher: Android allows one permission request at a
		// time and answers the rest with an immediate cancel, filling the log with warnings.
		updates.forEachIndexed { index, update ->
			install(update, uriHandler, if (index == 0) notificationPermission else null)
		}
	}

	override fun startDownloadProgress(id: Int) {
		state.update { it.withUpdates(it.mutableUpdates().setIsInstalling(id, true)) }
	}

	override fun finishDownloadProgress(id: Int) {
		state.update { it.withUpdates(it.mutableUpdates().setIsInstalling(id, false)) }
	}

	private fun List<AppUpdate>.filterIgnoredVersions(ignoredVersions: List<Int>) = this
		.filter { !ignoredVersions.contains(it.id) }

	/**
	 * Finished updates float to the top, so in a long list it stays obvious which ones were
	 * already handled. Deliberately only applied once an install COMPLETES — a card that is
	 * still installing keeps its place, otherwise its progress would jump out of view the
	 * moment the user tapped Update.
	 */
	private fun List<AppUpdate>.sortFinishedFirst() = sortedWith(
		compareByDescending<AppUpdate> { it.isInstalled }.thenBy { it.name.lowercase() }
	)

	private fun setSuccess(updates: List<AppUpdate>) {
		// Read the ignore list once, outside: the block below can be re-run under contention
		// and must stay free of side effects.
		val ignored = prefs.ignoredVersions.get()
		// A refresh rebuilds the list from scratch, but downloads/installs keep running in
		// BackgroundInstaller's process-wide scope. Carry their state over, otherwise a refresh
		// would reset a running download's card back to "Update" while it is still downloading.
		// The carry-over is read INSIDE the update block on purpose: the writers that flip a
		// card to installing run on the background scope and take no mutex, so a tap landing
		// between a separate read and the write was published and then thrown away.
		val merged = state.updateAndGet { current ->
			val inFlight = current.updates()
				.filter { it.isInstalling || it.isInstalled }
				.associateBy { it.id }
			UpdatesUiState.Success(
				updates
					.filterIgnoredVersions(ignored)
					.distinctBy { it.id }
					.map { fresh ->
						inFlight[fresh.id]?.let {
							fresh.copy(
								isInstalling = it.isInstalling,
								isInstalled = it.isInstalled,
								progress = it.progress,
								total = it.total
							)
						} ?: fresh
					}
					.sortFinishedFirst()
			)
		}
		badger.changeUpdatesBadge(merged.updates().size.toString())
	}

}
