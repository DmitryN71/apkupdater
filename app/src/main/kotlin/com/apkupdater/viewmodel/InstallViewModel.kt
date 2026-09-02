package com.apkupdater.viewmodel

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apkupdater.R
import com.apkupdater.data.snack.SnackType
import com.apkupdater.data.snack.TextSnack
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.rustore.RuStoreDownloadRequest
import com.apkupdater.data.rustore.ruStoreApkUrl
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.data.ui.AppInstallStatus
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.RuStoreSource
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.RuStoreService
import com.apkupdater.util.RuStoreSession
import com.apkupdater.util.AppVisibility
import com.apkupdater.util.BackgroundInstaller
import com.apkupdater.util.DownloadFolder
import com.apkupdater.util.Downloader
import com.apkupdater.util.randomUUID
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.UpdatesNotification
import com.apkupdater.util.installErrorResId
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.aurora.gplayapi.exceptions.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException


abstract class InstallViewModel(
    protected val downloader: Downloader,
    private val installer: SessionInstaller,
    private val prefs: Prefs,
    protected val snackBar: SnackBar,
    protected val stringer: Stringer,
    protected val installLog: InstallLog,
    private val ruStoreService: RuStoreService,
    protected val context: Context,
    protected val background: BackgroundInstaller,
    private val notification: UpdatesNotification
): ViewModel() {

    /**
     * Whether a *successful* install announces itself. Opt-out via Settings, because during a
     * batch update the success popups stack up over the Cancel buttons. Failures ignore this —
     * silent failures are what confused users in the first place.
     */
    protected fun notifyOnInstall() = prefs.notifyOnInstall.get()

    /** After a background install, show a "installed — Open?" notification. */
    protected fun notifyInstalledIfBackground(update: AppUpdate) {
        if (!AppVisibility.foreground && notifyOnInstall()) {
            notification.showInstallSuccessNotification(update.packageName, update.name, update.id)
        }
    }

    fun install(update: AppUpdate, uriHandler: UriHandler) {
        // Some releases have no directly downloadable APK (e.g. a GitLab project that only
        // publishes source archives). Without this, Update silently did nothing and Download
        // failed deep inside OkHttp — open the release page so the user can still get it.
        val link = update.link
        if (link is Link.Url && link.link.isBlank()) {
            snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.no_direct_download)))
            if (update.sourceUrl.isNotEmpty()) uriHandler.openUri(update.sourceUrl)
            return
        }
        when (update.source) {
            ApkMirrorSource -> uriHandler.openUri((update.link as Link.Url).link)
            else -> {
                if (isAlreadyUpToDate(update)) {
                    snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.already_up_to_date)))
                    return
                }
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

    fun getInstalledVersionCode(packageName: String): Long = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        }
    }.getOrElse { 0L }

    /** Returns true if the installed versionCode is already >= what we're about to install. */
    private fun isAlreadyUpToDate(update: AppUpdate): Boolean {
        if (update.versionCode <= 0L) return false
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(update.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(update.packageName, 0).versionCode.toLong()
            }
        }.getOrElse { return false }
        return installed >= update.versionCode
    }

    protected suspend fun resolveLink(update: AppUpdate): Link {
        if (update.source == RuStoreSource && update.link is Link.Url) {
            return runCatching {
                // RuStore answers for one device kind at a time, so try both: a TV app is a 404
                // for a "mobile" request and a phone app is a 404 for a "tv" one.
                val (appInfo, deviceType) = RuStoreSession.DEVICE_TYPES
                    .firstNotNullOfOrNull { type ->
                        runCatching { ruStoreService.getAppInfo(update.packageName, type) }
                            .getOrNull()
                            ?.takeIf { it.code == "OK" && it.body.appId != 0L }
                            ?.let { it to type }
                    } ?: (null to RuStoreSession.DEVICE_MOBILE)

                if (appInfo != null) {
                    val download = ruStoreService.getDownloadLink(RuStoreDownloadRequest(appInfo.body.appId), deviceType)
                    // v3 carries no status field — a usable URL is the success signal.
                    val url = download.downloadUrls.firstOrNull()?.url?.ruStoreApkUrl()
                    if (!url.isNullOrEmpty()) {
                        Link.Url(url, update.link.size)
                    } else update.link
                } else update.link
            }.getOrElse {
                Log.e("InstallViewModel", "Failed to refresh RuStore download URL", it)
                update.link
            }
        }
        return update.link
    }

    // Takes a getter, NOT a snapshot: subclasses call this from init, when their state is still
    // Loading and the list is empty. Capturing it there meant sendInstallSnack could never find
    // the app, so install success/failure messages were silently never shown.
    protected fun subscribeToInstallStatus(updates: () -> List<AppUpdate>) = installLog.status().onEach {
        sendInstallSnack(updates(), it)
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
        val fake = prefs.fakePlayStore.get()
        when (link) {
            is Link.Url -> {
                // Pass the id: without it the call isn't registered anywhere and Cancel can't
                // reach it — which now matters much more, since a failing download retries.
                if (installer.rootInstall(downloader.downloadFile(link.link, id), fake)) {
                    if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
                    finishInstall(id)
                } else {
                    if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
                    snackInstallFailure("", id = id)
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
        if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
        snackInstallFailure("", it, id)
        cancelInstall(id)
    }

    protected fun downloadAndShizukuInstall(id: Int, name: String, link: Link) = runCatching {
        val fake = prefs.fakePlayStore.get()
        // Shizuku methods return null on success, or error message on failure
        val error: String? = when (link) {
            is Link.Url -> {
                val file = downloader.downloadFile(link.link, id) { progress, total ->
                    installLog.emitProgress(AppInstallProgress(id, progress, total))
                }
                installer.shizukuInstall(file, fake)
            }
            is Link.Play -> {
                val files = link.getInstallFiles()
                // Same guard the standard path has had since 117: no usable file means Play
                // refused delivery (a 429 throttle yields entries with an empty url, which
                // PlayRepository now drops). Say so instead of failing deep in the downloader.
                if (files.isEmpty()) {
                    snackBar.snackBar(viewModelScope, TextSnack(
                        stringer.get(R.string.play_no_files), type = SnackType.ERROR))
                    cancelInstall(id)
                    return@runCatching
                }
                val totalSize = files.sumOf { it.size }
                if (totalSize > 0) installLog.emitProgress(AppInstallProgress(id, 0L, totalSize))
                var downloadedSoFar = 0L
                val tempFiles = files.map { playFile ->
                    val offset = downloadedSoFar
                    val file = downloader.downloadFile(playFile.url, id) { progress, _ ->
                        if (totalSize > 0) installLog.emitProgress(AppInstallProgress(id, offset + progress, totalSize))
                    }
                    downloadedSoFar += file.length()
                    file
                }
                installer.shizukuInstallSplit(tempFiles, fake)
            }
            is Link.Xapk -> {
                val file = downloader.downloadFile(link.link, id) { progress, total ->
                    installLog.emitProgress(AppInstallProgress(id, progress, total))
                }
                installer.shizukuInstallXapk(file, fake)
            }
            else -> {
                snackBar.snackBar(viewModelScope, TextSnack(stringer.get(R.string.shizuku_install_not_supported)))
                return@runCatching
            }
        }
        if (error == null) {
            if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
            if (notifyOnInstall()) snackBar.snackBar(viewModelScope, TextSnack(
                stringer.get(R.string.install_success, name), type = SnackType.SUCCESS
            ))
            finishInstall(id)
        } else {
            if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
            val msg = stringer.get(R.string.install_failure, name) + "\n" + error
            snackBar.snackBar(viewModelScope, TextSnack(msg, type = SnackType.ERROR))
            installLog.emitProgress(AppInstallProgress(id, 0L))
            cancelInstall(id)
        }
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndShizukuInstall.", it)
        if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
        // Was: silent for every network error, and a raw install-error lookup otherwise, which
        // is how a plain Cancel produced "unexpected error". The shared helper knows a cancel
        // from a failure, and a download that died on the network deserves saying so too.
        snackInstallFailure(name, it, id)
        installLog.emitProgress(AppInstallProgress(id, 0L))
        cancelInstall(id)
    }

    protected suspend fun downloadAndInstall(id: Int, packageName: String, link: Link, name: String = "") = runCatching {
        when (link) {
            Link.Empty -> { Log.e("InstallViewModel", "downloadAndInstall: Unsupported.")}
            is Link.Play -> {
                val files = link.getInstallFiles()
                if (files.isEmpty()) {
                    // Play gave us no usable file. NOT the paid-app case — that throws
                    // AppNotPurchased and is answered by playErrorResId. In practice this is a
                    // throttled delivery (HTTP 429). Committing an empty session would fail
                    // cryptically, so explain it instead.
                    snackBar.snackBar(viewModelScope, TextSnack(
                        stringer.get(R.string.play_no_files), type = SnackType.ERROR))
                    cancelInstall(id)
                    return@runCatching
                }
                installLog.emitProgress(AppInstallProgress(id, 0L, files.sumOf { it.size }))
                // A null stream means the download failed or the user cancelled. The old !!
                // raised a message-less NPE, which the error classifier could only call an
                // unknown install error — so Cancel on a Play update answered with a red
                // failure toast. Say which of the two it was and let the classifier do its job.
                val streams = files.map { playFile ->
                    downloader.downloadStream(playFile.url, id) ?: throw IOException(
                        if (downloader.isCancelled(id)) "Canceled" else "Download failed"
                    )
                }
                installer.playInstall(id, packageName, streams)
            }
            is Link.Url -> {
                // Download to a temp file so we can verify the APK's package before
                // installing — a streamed install can't be checked and would let a
                // wrong-channel APK (e.g. Brave Nightly over Beta) install alongside.
                val file = downloader.downloadFile(link.link, id) { downloaded, total ->
                    installLog.emitProgress(AppInstallProgress(id, downloaded, total))
                }
                val wrongPackage = installer.verifyPackage(file, packageName)
                if (wrongPackage != null) {
                    file.delete()
                    // Show the message directly (not via the status channel, whose
                    // snack lookup uses a stale list) so the user always sees why.
                    snackBar.snackBar(viewModelScope, TextSnack(
                        stringer.get(R.string.install_error_wrong_package, wrongPackage),
                        type = SnackType.ERROR))
                    installLog.emitProgress(AppInstallProgress(id, 0L))
                    cancelInstall(id)
                    return@runCatching
                }
                // Progress already shown during download — install without re-tracking.
                file.inputStream().use { installer.install(id, packageName, it, trackProgress = false) }
                file.delete()
            }
            is Link.Xapk -> {
                val result = downloader.downloadStreamWithSize(link.link, id)
                if (result != null) {
                    if (result.size > 0) {
                        installLog.emitProgress(AppInstallProgress(id, 0L, result.size))
                    }
                    installer.installXapk(id, packageName, result.stream, result.size)
                } else {
                    // downloadStreamWithSize swallows its own exceptions and returns null, so
                    // nothing ever reached snackInstallFailure — the one branch 117 left silent.
                    Log.e("InstallViewModel", "Failed to download XAPK")
                    installLog.emitProgress(AppInstallProgress(id, 0L))
                    snackInstallFailure(name, IOException(
                        if (downloader.isCancelled(id)) "Canceled" else "Download failed"
                    ), id)
                    cancelInstall(id)
                }
            }
        }
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndInstall.", it)
        if (prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
        // This path — the default one, used unless root/Shizuku is enabled — used to fail in
        // complete silence: the card simply reverted to "Update". Reported as "downloads, then
        // stops with no message at all". Only a deliberate user cancel stays quiet now; even a
        // network drop mid-download gets explained.
        snackInstallFailure(name, it, id)
        installLog.emitProgress(AppInstallProgress(id, 0L))
        cancelInstall(id)
    }

    /**
     * Public cancel triggered from UI. Aborts in-flight HTTP calls; the
     * IOException that surfaces from cancellation propagates to the existing
     * .onFailure handlers, which call cancelInstall(id) to reset UI state.
     */
    fun userCancelInstall(id: Int) {
        downloader.cancel(id)
    }

    /**
     * Reports a failed download/install. Every install path must call this: silence was the
     * single most confusing thing users hit ("it downloads, then nothing happens"). Only a
     * cancel the user asked for stays quiet.
     */
    protected fun snackInstallFailure(name: String, error: Throwable? = null, id: Int? = null) {
        // Ask the downloader outright rather than reading tea leaves in the exception text.
        // Cancelling a download surfaces as any of several socket exceptions, and the ones
        // whose message doesn't happen to say "canceled" were slipping through as a bogus
        // "failed to install — unexpected error" the moment the user pressed Cancel.
        if (id != null && downloader.isCancelled(id)) return
        if (error != null && isCancellation(error)) return
        val playReason = error?.let { playErrorResId(it) }
        val reason = when {
            error == null -> stringer.get(R.string.install_error_unknown)
            playReason != null -> stringer.get(playReason)
            isNetworkError(error) || isDownloadError(error) -> stringer.get(R.string.download_failed)
            else -> stringer.get(installErrorResId(error.message))
        }
        val msg = stringer.get(R.string.install_failure, name).trim() + "\n" + reason
        snackBar.snackBar(viewModelScope, TextSnack(msg, type = SnackType.ERROR))
    }

    private fun sendInstallSnack(updates: List<AppUpdate>, log: AppInstallStatus) {
        if (!log.snack) return
        // Only the screen that actually lists this app speaks. Updates and Search both subscribe
        // to the same install-status flow, so a nameless fallback here meant every install
        // produced two toasts — "<app> installed." from Updates and a bare "installed." from
        // Search, whose list is empty. Failures raised by the install paths themselves go
        // through snackInstallFailure and are unaffected by this.
        val name = updates.find { log.id == it.id }?.name ?: return
        if (log.success) {
            if (!notifyOnInstall()) return
            snackBar.snackBar(viewModelScope, TextSnack(
                stringer.get(R.string.install_success, name).trim(),
                type = SnackType.SUCCESS
            ))
        } else {
            val base = stringer.get(R.string.install_failure, name).trim()
            val text = if (!log.reason.isNullOrBlank()) "$base\n${log.reason}" else base
            snackBar.snackBar(viewModelScope, TextSnack(text, type = SnackType.ERROR))
        }
    }

    private fun isNetworkError(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("unable to resolve host") ||
            msg.contains("failed to connect") ||
            msg.contains("network is unreachable") ||
            msg.contains("connection reset") ||
            msg.contains("connection refused") ||
            msg.contains("timeout") ||
            e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException
    }

    /**
     * Turns a Google Play refusal into a reason a person can act on.
     *
     * Every gplayapi exception calls `Exception()` with NO message — the reason string goes
     * into a private field the base class never sees (verified by disassembling the library).
     * So `error.message` is always null, the generic classifier could only ever answer
     * "unexpected error", and "Play has no build for your device" was indistinguishable from
     * "you don't own this paid app". Matching on the TYPE is compile-checked and survives
     * minification, unlike hunting for substrings that aren't there in the first place.
     */
    private fun playErrorResId(error: Throwable): Int? = when (error) {
        is ApiException.AppNotSupported -> R.string.play_not_supported
        is ApiException.AppNotPurchased -> R.string.play_not_purchased
        is ApiException.AppRemoved, is ApiException.AppNotFound -> R.string.play_app_removed
        is ApiException.EmptyDownloads -> R.string.play_no_files
        else -> null
    }

    /**
     * Downloader gave up: a bad HTTP status, or every retry exhausted on a truncated file.
     * Those read as a download problem, not as an install one.
     */
    private fun isDownloadError(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.startsWith("http ") || msg.contains("download incomplete") ||
            msg.contains("download failed")
    }

    /** Detects OkHttp Call cancellation (user pressed cancel button). */
    protected fun isCancellation(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("canceled") || msg.contains("cancelled") || msg.contains("socket closed")
    }

    // Runs in the process-lifetime scope so the download survives leaving the app;
    // begin()/end() keep the foreground service (and its notification) alive meanwhile.
    fun downloadToFolder(update: AppUpdate) = background.scope.launch(Dispatchers.IO) {
        background.begin(update.id, update.name)
        try {
        runCatching {
            val link = resolveLink(update)
            val safeName = update.name.replace(Regex("[^\\p{L}\\p{N}._\\- ]"), "").trim().ifEmpty { update.packageName }

            startDownloadProgress(update.id)

            when (link) {
                is Link.Play -> downloadPlayToFolder(update.id, safeName, update.version, link)
                is Link.Url, is Link.Xapk -> {
                    val url = when (link) {
                        is Link.Url -> link.link
                        is Link.Xapk -> link.link
                        else -> return@launch
                    }
                    val isXapk = link is Link.Xapk || url.contains(".xapk", true)
                    val ext = if (isXapk) "xapk" else "apk"
                    val fileName = "$safeName-${update.version}.$ext"

                    val tempFile = downloader.downloadFile(url, update.id) { bytesDownloaded, totalBytes ->
                        installLog.emitProgress(AppInstallProgress(update.id, bytesDownloaded, totalBytes))
                    }

                    if (tempFile.length() == 0L) {
                        tempFile.delete()
                        finishDownloadProgress(update.id)
                        snackBar.snackBar(viewModelScope, TextSnack(
                            stringer.get(R.string.download_failed), type = SnackType.ERROR
                        ))
                        return@launch
                    }

                    val saved = saveToFolder(tempFile, fileName)
                    tempFile.delete()
                    downloader.cleanUp()
                    finishDownloadProgress(update.id)

                    snackBar.snackBar(viewModelScope, savedSnack(saved))
                }
                else -> {
                    finishDownloadProgress(update.id)
                    snackBar.snackBar(viewModelScope, TextSnack(
                        stringer.get(R.string.download_not_supported), type = SnackType.ERROR
                    ))
                }
            }
        }.onFailure {
            Log.e("InstallViewModel", "Error downloading to folder", it)
            downloader.cleanUp()
            finishDownloadProgress(update.id)
            // Ask the downloader whether this was the user's Cancel, as the install paths do
            // since 129. Filtering on the exception text hid every real network failure — a
            // Wi-Fi drop that outlived the retries ended in silence — while a cancel whose
            // exception happened not to say "canceled" showed "Download failed".
            if (!downloader.isCancelled(update.id)) {
                snackBar.snackBar(viewModelScope, TextSnack(
                    stringer.get(R.string.download_failed), type = SnackType.ERROR
                ))
            }
        }
        } finally {
            background.end(update.id)
        }
    }

    private fun downloadPlayToFolder(id: Int, safeName: String, version: String, link: Link.Play) {
        val files = link.getInstallFiles()
        // Without this the throttled case ends in a SUCCESS message: zipping zero entries does
        // not fail, it just writes an empty archive, so the user was told "Saved: Foo.apks" and
        // got a 22-byte file that opens in nothing.
        if (files.isEmpty()) throw IOException("Download failed: no download link")
        val totalSize = files.sumOf { it.size }
        if (totalSize > 0) installLog.emitProgress(AppInstallProgress(id, 0L, totalSize))

        // Download all split APKs to temp files
        var downloadedSoFar = 0L
        val tempFiles = files.mapIndexed { index, playFile ->
            val offset = downloadedSoFar
            val tempFile = downloader.downloadFile(playFile.url, id) { progress, _ ->
                if (totalSize > 0) installLog.emitProgress(AppInstallProgress(id, offset + progress, totalSize))
            }
            downloadedSoFar += tempFile.length()
            // Use the real split APK name from Google Play (base.apk, config.ru.apk, etc.)
            // Fall back to numeric index if name is missing or malformed
            val zipName = playFile.name
                .takeIf { it.isNotBlank() && it.endsWith(".apk", true) }
                ?: "$index.apk"
            Pair(zipName, tempFile)
        }

        // Pack into .apks (zip) file
        val apksFile = File(context.cacheDir, randomUUID())
        java.util.zip.ZipOutputStream(apksFile.outputStream()).use { zip ->
            tempFiles.forEach { (name, file) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                file.delete()
            }
        }

        val fileName = "$safeName-${version}.apks"
        // The zip sits in cacheDir's root, which no sweep touches — delete it on every exit,
        // including a save that throws.
        val saved = try { saveToFolder(apksFile, fileName) } finally { apksFile.delete() }
        downloader.cleanUp()
        finishDownloadProgress(id)

        snackBar.snackBar(viewModelScope, savedSnack(saved))
    }

    private fun saveToFolder(file: File, fileName: String) =
        DownloadFolder.save(context, prefs.downloadFolder.get(), file, fileName)

    /**
     * A fallback is reported differently on purpose. The user chose a folder, so telling them
     * "Saved: Foo.apk" while the file went to Downloads would send them looking in the wrong
     * place — the one failure mode of this feature that costs them real time.
     */
    private fun savedSnack(saved: DownloadFolder.Saved) = TextSnack(
        if (saved.usedFallback) stringer.get(R.string.saved_to_downloads_fallback, saved.fileName)
        else stringer.get(R.string.saved_to_downloads, saved.fileName),
        type = if (saved.usedFallback) SnackType.ERROR else SnackType.SUCCESS
    )

    protected abstract fun downloadAndInstall(update: AppUpdate): Job
    protected abstract fun downloadAndRootInstall(update: AppUpdate): Job
    protected abstract fun downloadAndShizukuInstall(update: AppUpdate): Job
    protected abstract fun cancelInstall(id: Int): Job
    protected abstract fun finishInstall(id: Int): Job
    protected abstract fun startDownloadProgress(id: Int)
    protected abstract fun finishDownloadProgress(id: Int)
}
