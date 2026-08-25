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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
	private val activeInstalls = AtomicInteger(0)
	private val state = MutableStateFlow<UpdatesUiState>(UpdatesUiState.Loading())
	private val _refreshProgress = MutableStateFlow<String?>(null)
	val refreshProgress: StateFlow<String?> = _refreshProgress
	private val _cacheSize = MutableStateFlow(0L)
	val cacheSize: StateFlow<Long> = _cacheSize

	fun refreshCacheSize() = viewModelScope.launch(Dispatchers.IO) {
		_cacheSize.value = context.downloadCacheSizeBytes()
	}

	fun clearCache() = viewModelScope.launch(Dispatchers.IO) {
		context.clearDownloadCacheBytes()
		_cacheSize.value = 0L
	}

	init {
		subscribeToInstallStatus { state.value.updates() }
		subscribeToInstallProgress { progress ->
			state.value = UpdatesUiState.Success(state.value.mutableUpdates().setProgress(progress))
		}
	}

	fun state(): StateFlow<UpdatesUiState> = state

	fun refresh(load: Boolean = true) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		if (load) state.value = UpdatesUiState.Loading()
		_refreshProgress.value = stringer.get(R.string.checking_updates)
		badger.changeUpdatesBadge("")
		updatesRepository.updates(
			onSourceError = { errors, total ->
				snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.source_errors, errors, total)))
			},
			onSourceComplete = { completed, total, remaining ->
				_refreshProgress.value = if (remaining.isNotEmpty()) {
					stringer.get(R.string.checking_sources, remaining.joinToString(", "))
				} else null
				if (state.value is UpdatesUiState.Loading) {
					state.value = UpdatesUiState.Loading(completed, total)
				}
			}
		).collect {
			setSuccess(it)
			_refreshProgress.value = null
		}
		refreshCacheSize()
	}

	fun hideUpdate(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val updates = state.value.mutableUpdates().removeId(id)
		state.value = UpdatesUiState.Success(updates)
		badger.changeUpdatesBadge(updates.size.toString())
	}

	fun ignoreVersion(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val ignored = prefs.ignoredVersions.get().toMutableList()
		if (ignored.contains(id)) ignored.remove(id) else ignored.add(id)
		prefs.ignoredVersions.put(ignored)
		setSuccess(state.value.mutableUpdates())
	}

	override fun cancelInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(id, false))
		installer.finish()
	}

	override fun finishInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val updates = state.value.mutableUpdates().setIsInstalled(id).sortFinishedFirst()
		state.value = UpdatesUiState.Success(updates)
		badger.changeUpdatesBadge(updates.count { !it.isInstalled }.toString())
		installer.finish()
	}

	/** Calls cleanUp() only when the last parallel install completes. */
	private fun cleanUpIfLast() {
		if (prefs.cleanUpAfterInstall.get() && activeInstalls.decrementAndGet() == 0) {
			downloader.cleanUp()
		}
	}

	// Install work runs in the process-lifetime background scope (not viewModelScope)
	// so it survives leaving the app; begin()/end() keep the foreground service alive.
	override fun downloadAndRootInstall(update: AppUpdate) = background.scope.launch {
		background.begin(update.id, update.name)
		try {
			activeInstalls.incrementAndGet()
			state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
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
			// Same package guard as the standard path (root installs via pm install too).
			val wrongPackage = installer.verifyPackage(file, update.packageName)
			if (wrongPackage != null) {
				file.delete()
				cleanUpIfLast()
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
					if (installer.rootInstall(file, fake)) {
						cleanUpIfLast()
						notifyInstalledIfBackground(update)
						finishInstall(update.id)
					} else {
						file.delete()
						cleanUpIfLast()
						snackInstallFailure(update.name, id = update.id)
						cancelInstall(update.id)
					}
				}.getOrElse {
					file.delete()
					cleanUpIfLast()
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
		activeInstalls.incrementAndGet()
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
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
					cleanUpIfLast()
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
					cleanUpIfLast()
					if (notifyOnInstall()) snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.install_success, update.name), type = com.apkupdater.data.snack.SnackType.SUCCESS))
					notifyInstalledIfBackground(update)
					finishInstall(update.id)
				} else {
					files.forEach { it.delete() }
					cleanUpIfLast()
					val msg = stringer.get(R.string.install_failure, update.name) + "\n" + error
					snackBar.snackBar(viewModelScope, TextSnack(msg, type = com.apkupdater.data.snack.SnackType.ERROR))
					installLog.emitProgress(AppInstallProgress(update.id, 0L))
					cancelInstall(update.id)
				}
			}.getOrElse {
				files.forEach { it.delete() }
				cleanUpIfLast()
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
				state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
				// No ViewModel mutex — downloads run in parallel.
				// SessionInstaller has its own mutex for commit sequencing.
				val link = resolveLink(update)
				downloadAndInstall(update.id, update.packageName, link, update.name)
			} finally {
				background.end(update.id)
			}
		}
	}

	fun installAll(uriHandler: androidx.compose.ui.platform.UriHandler) {
		val updates = state.value.updates().filter { !it.isInstalling && !it.isInstalled }
		updates.forEach { update -> install(update, uriHandler) }
	}

	override fun startDownloadProgress(id: Int) {
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(id, true))
	}

	override fun finishDownloadProgress(id: Int) {
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(id, false))
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
		// A refresh rebuilds the list from scratch, but downloads/installs keep running in
		// BackgroundInstaller's process-wide scope. Carry their state over, otherwise a refresh
		// would reset a running download's card back to "Update" while it is still downloading.
		val inFlight = state.value.updates()
			.filter { it.isInstalling || it.isInstalled }
			.associateBy { it.id }
		updates
			.filterIgnoredVersions(prefs.ignoredVersions.get())
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
			.let {
				state.value = UpdatesUiState.Success(it)
				badger.changeUpdatesBadge(it.size.toString())
			}
	}

}
