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
import java.util.concurrent.atomic.AtomicInteger


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
    private val generation = AtomicInteger(0)

    private val _searching = MutableStateFlow(false)
    /**
     * True while sources are still answering. Results arrive one source at a time, so a list
     * with items in it is NOT a finished search — until now there was no way at all to tell
     * "still looking" from "that is everything".
     */
    val searching: StateFlow<Boolean> = _searching

    private val _query = MutableStateFlow("")
    /** Last query actually sent, so an empty result can say "nothing found" instead of
     *  repeating the "type something to search" hint as if nothing had been asked. */
    val query: StateFlow<String> = _query

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

    /**
     * Drops the results and stops any search in flight. Bumping the generation matters: coroutine
     * cancellation is cooperative, and the collect body below has no suspension point in it, so a
     * clearSearch landing mid-body would be overwritten a moment later by the very results it cleared —
     * leaving a stale list under an emptied field, which is the bug this is here to prevent.
     */
    fun clearSearch() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        _query.value = ""
        _searching.value = false
        state.value = SearchUiState.Success(emptyList())
        badger.changeSearchBadge("")
    }

    private fun searchJob(text: String) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
        // Everything below runs inside the mutex, so a previous search that is still unwinding
        // has already passed its finally and cannot clear the flag we are about to set.
        val mine = generation.incrementAndGet()
        _query.value = text
        _searching.value = true
        state.value = SearchUiState.Loading
        badger.changeSearchBadge("")
        try {
            searchRepository.search(text).collect {
                // Superseded by a clear() or a newer query — see the note on clearSearch().
                if (generation.get() != mine) return@collect
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
        } finally {
            _searching.value = false
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
