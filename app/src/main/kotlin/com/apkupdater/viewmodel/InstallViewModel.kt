package com.apkupdater.viewmodel

import android.util.Log
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apkupdater.R
import com.apkupdater.data.snack.TextSnack
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.data.ui.AppInstallStatus
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.Downloader
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


abstract class InstallViewModel(
    private val downloader: Downloader,
    private val installer: SessionInstaller,
    private val prefs: Prefs,
    protected val snackBar: SnackBar,
    protected val stringer: Stringer,
    private val installLog: InstallLog
): ViewModel() {

    fun install(update: AppUpdate, uriHandler: UriHandler) {
        when (update.source) {
            ApkMirrorSource -> uriHandler.openUri((update.link as Link.Url).link)
            else -> {
                if (prefs.rootInstall.get()) {
                    downloadAndRootInstall(update)
                } else if (prefs.shizukuInstall.get()) {
                    downloadAndShizukuInstall(update)
                } else {
                    downloadAndInstall(update)
                }
            }
        }
    }

    protected fun subscribeToInstallStatus(updates: List<AppUpdate>) = installLog.status().onEach {
        sendInstallSnack(updates, it)
        if (it.success) {
            if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
            finishInstall(it.id).join()
        } else {
            if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
            installLog.emitProgress(AppInstallProgress(it.id, 0L))
            cancelInstall(it.id).join()
        }
    }.launchIn(viewModelScope)

    protected fun subscribeToInstallProgress(
        block: (AppInstallProgress) -> Unit
    ) = installLog.progress().onEach {
        block(it)
    }.launchIn(viewModelScope)

    protected fun downloadAndRootInstall(id: Int, link: Link) = runCatching {
        when (link) {
            is Link.Url -> {
                if (installer.rootInstall(downloader.download(link.link))) {
                    finishInstall(id)
                } else {
                    cancelInstall(id)
                }
            }
            else -> snackBar.snackBar(
                viewModelScope,
                TextSnack(stringer.get(R.string.root_install_not_supported))
            )
        }
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndRootInstall.", it)
        cancelInstall(id)
    }

    protected fun downloadAndShizukuInstall(id: Int, name: String, link: Link) = runCatching {
        val success = when (link) {
            is Link.Url -> {
                val file = downloader.downloadFile(link.link) { progress, total ->
                    installLog.emitProgress(AppInstallProgress(id, progress, total))
                }
                installer.shizukuInstall(file)
            }
            is Link.Play -> {
                val files = link.getInstallFiles()
                val totalSize = files.sumOf { it.size }
                if (totalSize > 0) installLog.emitProgress(AppInstallProgress(id, 0L, totalSize))
                var downloadedSoFar = 0L
                val tempFiles = files.map { playFile ->
                    val offset = downloadedSoFar
                    val file = downloader.downloadFile(playFile.url) { progress, _ ->
                        if (totalSize > 0) installLog.emitProgress(AppInstallProgress(id, offset + progress, totalSize))
                    }
                    downloadedSoFar += file.length()
                    file
                }
                installer.shizukuInstallSplit(tempFiles)
            }
            is Link.Xapk -> {
                val file = downloader.downloadFile(link.link) { progress, total ->
                    installLog.emitProgress(AppInstallProgress(id, progress, total))
                }
                installer.shizukuInstallXapk(file)
            }
            else -> {
                snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.shizuku_install_not_supported)))
                return@runCatching
            }
        }
        if (success) {
            if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
            snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.install_success, name)))
            finishInstall(id)
        } else {
            if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
            snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.install_failure, name)))
            installLog.emitProgress(AppInstallProgress(id, 0L))
            cancelInstall(id)
        }
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndShizukuInstall.", it)
        if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
        snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.install_failure, name)))
        installLog.emitProgress(AppInstallProgress(id, 0L))
        cancelInstall(id)
    }

    protected suspend fun downloadAndInstall(id: Int, packageName: String, link: Link) = runCatching {
        when (link) {
            Link.Empty -> { Log.e("InstallViewModel", "downloadAndInstall: Unsupported.")}
            is Link.Play -> {
                val files = link.getInstallFiles()
                installLog.emitProgress(AppInstallProgress(id, 0L, files.sumOf { it.size }))
                installer.playInstall(id, packageName, files.map { downloader.downloadStream(it.url)!! })
            }
            is Link.Url -> {
                val result = downloader.downloadStreamWithSize(link.link)!!
                val totalSize = if (link.size > 0) link.size else result.size
                installLog.emitProgress(AppInstallProgress(id, 0L, totalSize))
                installer.install(id, packageName, result.stream)
            }
            is Link.Xapk -> {
                val result = downloader.downloadStreamWithSize(link.link)
                if (result != null) {
                    if (result.size > 0) {
                        installLog.emitProgress(AppInstallProgress(id, 0L, result.size))
                    }
                    installer.installXapk(id, packageName, result.stream, result.size)
                } else {
                    Log.e("InstallViewModel", "Failed to download XAPK")
                    cancelInstall(id)
                }
            }
        }
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndInstall.", it)
        cancelInstall(id)
    }

    private fun sendInstallSnack(updates: List<AppUpdate>, log: AppInstallStatus) {
        if (log.snack) {
            updates.find { log.id == it.id }?.let { app ->
                val message = if (log.success) R.string.install_success else R.string.install_failure
                snackBar.snackBar(viewModelScope, TextSnack(stringer.get(message, app.name)))
            }
        }
    }

    protected abstract fun downloadAndInstall(update: AppUpdate): Job
    protected abstract fun downloadAndRootInstall(update: AppUpdate): Job
    protected abstract fun downloadAndShizukuInstall(update: AppUpdate): Job
    protected abstract fun cancelInstall(id: Int): Job
    protected abstract fun finishInstall(id: Int): Job
}
