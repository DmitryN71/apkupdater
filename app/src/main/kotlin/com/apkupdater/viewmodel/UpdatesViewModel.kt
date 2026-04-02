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
import com.apkupdater.util.Badger
import com.apkupdater.util.Downloader
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
	context: Context
) : InstallViewModel(downloader, installer, prefs, snackBar, stringer, installLog, ruStoreService, context) {

	private val mutex = Mutex()
	private val installMutex = Mutex()
	private val activeInstalls = AtomicInteger(0)
	private val state = MutableStateFlow<UpdatesUiState>(UpdatesUiState.Loading())
	private val _refreshProgress = MutableStateFlow<String?>(null)
	val refreshProgress: StateFlow<String?> = _refreshProgress

	init {
		subscribeToInstallStatus(state.value.updates())
		subscribeToInstallProgress { progress ->
			state.value = UpdatesUiState.Success(state.value.mutableUpdates().setProgress(progress))
		}
	}

	fun state(): StateFlow<UpdatesUiState> = state

	fun refresh(load: Boolean = true) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		if (load) state.value = UpdatesUiState.Loading()
		_refreshProgress.value = null
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
		val updates = state.value.mutableUpdates().setIsInstalled(id)
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

	override fun downloadAndRootInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		activeInstalls.incrementAndGet()
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
		val link = resolveLink(update)
		if (link !is com.apkupdater.data.ui.Link.Url) {
			snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.root_install_not_supported)))
			cancelInstall(update.id)
			return@launch
		}
		// Download in parallel (no mutex) — rootInstall() deletes the file itself
		val file = runCatching { downloader.downloadFile(link.link) }.getOrElse {
			cancelInstall(update.id)
			return@launch
		}
		// Install sequentially
		installMutex.withLock {
			runCatching {
				val fake = prefs.fakePlayStore.get()
				if (installer.rootInstall(file, fake)) {
					cleanUpIfLast()
					finishInstall(update.id)
				} else {
					file.delete()
					cleanUpIfLast()
					cancelInstall(update.id)
				}
			}.getOrElse {
				file.delete()
				cleanUpIfLast()
				cancelInstall(update.id)
			}
		}
	}

	override fun downloadAndShizukuInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		activeInstalls.incrementAndGet()
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
		val link = resolveLink(update)
		// Download in parallel (no mutex) — shizuku install methods delete files themselves
		val files = runCatching {
			when (link) {
				is com.apkupdater.data.ui.Link.Url -> {
					val file = downloader.downloadFile(link.link) { progress, total ->
						installLog.emitProgress(AppInstallProgress(update.id, progress, total))
					}
					listOf(file)
				}
				is com.apkupdater.data.ui.Link.Xapk -> {
					val file = downloader.downloadFile(link.link) { progress, total ->
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
						val file = downloader.downloadFile(playFile.url) { progress, _ ->
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
			cancelInstall(update.id)
			return@launch
		}
		// Install sequentially
		installMutex.withLock {
			runCatching {
				val fake = prefs.fakePlayStore.get()
				val error = when (link) {
					is com.apkupdater.data.ui.Link.Xapk -> installer.shizukuInstallXapk(files.first(), fake)
					is com.apkupdater.data.ui.Link.Play -> installer.shizukuInstallSplit(files, fake)
					else -> installer.shizukuInstall(files.first(), fake)
				}
				if (error == null) {
					cleanUpIfLast()
					snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.install_success, update.name), type = com.apkupdater.data.snack.SnackType.SUCCESS))
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
	}

	override fun downloadAndInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		if (installer.checkPermission()) {
			state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
			// No ViewModel mutex — downloads run in parallel.
			// SessionInstaller has its own mutex for commit sequencing.
			val link = resolveLink(update)
			downloadAndInstall(update.id, update.packageName, link)
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

	private fun setSuccess(updates: List<AppUpdate>) = updates
		.filterIgnoredVersions(prefs.ignoredVersions.get())
		.distinctBy { it.id }
		.let {
			state.value = UpdatesUiState.Success(it)
			badger.changeUpdatesBadge(it.size.toString())
		}

}
