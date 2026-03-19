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

	override fun downloadAndRootInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
		installMutex.withLock {
			val link = resolveLink(update)
			downloadAndRootInstall(update.id, link)
		}
	}

	override fun downloadAndShizukuInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
		installMutex.withLock {
			val link = resolveLink(update)
			downloadAndShizukuInstall(update.id, update.name, link)
		}
	}

	override fun downloadAndInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		if(installer.checkPermission()) {
			state.value = UpdatesUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
			installMutex.withLock {
				val link = resolveLink(update)
				downloadAndInstall(update.id, update.packageName, link)
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

	private fun setSuccess(updates: List<AppUpdate>) = updates
		.filterIgnoredVersions(prefs.ignoredVersions.get())
		.distinctBy { it.id }
		.let {
			state.value = UpdatesUiState.Success(it)
			badger.changeUpdatesBadge(it.size.toString())
		}

}
