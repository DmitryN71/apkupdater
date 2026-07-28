package com.apkupdater.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.SearchUiState
import com.apkupdater.data.ui.removeId
import com.apkupdater.data.ui.setIsInstalled
import com.apkupdater.data.ui.setIsInstalling
import com.apkupdater.data.ui.setProgress
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.SearchRepository
import com.apkupdater.service.RuStoreService
import com.apkupdater.util.BackgroundInstaller
import com.apkupdater.util.Badger
import com.apkupdater.util.Downloader
import com.apkupdater.util.UpdatesNotification
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.apkupdater.util.launchWithMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val installer: SessionInstaller,
    private val badger: Badger,
    downloader: Downloader,
    prefs: Prefs,
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
    private val state = MutableStateFlow<SearchUiState>(SearchUiState.Success(emptyList()))
    private var job: Job? = null

    init {
        subscribeToInstallStatus { state.value.updates() }
        subscribeToInstallProgress { progress ->
            state.value = SearchUiState.Success(state.value.mutableUpdates().setProgress(progress))
        }
    }

    fun state(): StateFlow<SearchUiState> = state

    fun search(text: String) {
        job?.cancel()
        job = searchJob(text)
    }

    private fun searchJob(text: String) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
        state.value = SearchUiState.Loading
        badger.changeSearchBadge("")
        searchRepository.search(text).collect {
            it.onSuccess { apps ->
                val enriched = apps.map { app ->
                    val installed = getInstalledVersionCode(app.packageName)
                    if (installed > 0L) app.copy(oldVersionCode = installed) else app
                }
                state.value = SearchUiState.Success(enriched)
                badger.changeSearchBadge(enriched.size.toString())
            }.onFailure {
                badger.changeSearchBadge("!")
                state.value = SearchUiState.Error
            }
        }
    }

    override fun cancelInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
        state.value = SearchUiState.Success(state.value.mutableUpdates().setIsInstalling(id, false))
        installer.finish()
    }

    override fun finishInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
        val updates = state.value.mutableUpdates().setIsInstalled(id)
        state.value = SearchUiState.Success(updates)
        badger.changeSearchBadge(updates.count { !it.isInstalled }.toString())
        installer.finish()
    }

    // Install work runs in the process-lifetime background scope (not viewModelScope)
    // so it survives leaving the app; begin()/end() keep the foreground service alive.
    override fun downloadAndRootInstall(update: AppUpdate) = background.scope.launch {
        background.begin(update.id, update.name)
        try {
            state.value = SearchUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
            installMutex.withLock {
                val link = resolveLink(update)
                downloadAndRootInstall(update.id, link)
            }
        } finally {
            background.end(update.id)
        }
    }

    override fun downloadAndShizukuInstall(update: AppUpdate) = background.scope.launch {
        background.begin(update.id, update.name)
        try {
            state.value = SearchUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
            installMutex.withLock {
                val link = resolveLink(update)
                downloadAndShizukuInstall(update.id, update.name, link)
            }
        } finally {
            background.end(update.id)
        }
    }

    override fun downloadAndInstall(update: AppUpdate) = background.scope.launch {
        if(installer.checkPermission()) {
            background.begin(update.id, update.name)
            try {
                state.value = SearchUiState.Success(state.value.mutableUpdates().setIsInstalling(update.id, true))
                installMutex.withLock {
                    val link = resolveLink(update)
                    downloadAndInstall(update.id, update.packageName, link, update.name)
                }
            } finally {
                background.end(update.id)
            }
        }
    }

    override fun startDownloadProgress(id: Int) {
        state.value = SearchUiState.Success(state.value.mutableUpdates().setIsInstalling(id, true))
    }

    override fun finishDownloadProgress(id: Int) {
        state.value = SearchUiState.Success(state.value.mutableUpdates().setIsInstalling(id, false))
    }

}
