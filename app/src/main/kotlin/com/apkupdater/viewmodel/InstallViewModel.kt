package com.apkupdater.viewmodel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
                    // Play returned nothing to download. This is what happens for PAID apps: we
                    // sign in to Play with an anonymous shared account (Aurora's dispenser), which
                    // owns no purchases, so the delivery request comes back empty. Committing an
                    // empty session would fail cryptically — explain it instead.
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
                    Log.e("InstallViewModel", "Failed to download XAPK")
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

                    // Use application/octet-stream for both .apk and .xapk to prevent
                    // MediaStore from appending an extension based on MIME type
                    // (previously .xapk was getting saved as .xapk.zip).
                    val mime = "application/octet-stream"
                    saveToDownloads(tempFile, fileName, mime)
                    tempFile.delete()
                    downloader.cleanUp()
                    finishDownloadProgress(update.id)

                    snackBar.snackBar(viewModelScope, TextSnack(
                        stringer.get(R.string.saved_to_downloads, fileName), type = SnackType.SUCCESS
                    ))
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
            if (!isNetworkError(it) && !isCancellation(it)) {
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
        saveToDownloads(apksFile, fileName, "application/octet-stream")
        apksFile.delete()
        downloader.cleanUp()
        finishDownloadProgress(id)

        snackBar.snackBar(viewModelScope, TextSnack(
            stringer.get(R.string.saved_to_downloads, fileName), type = SnackType.SUCCESS
        ))
    }

    private fun saveToDownloads(file: File, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "APKUpdater")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "APKUpdater").apply { if (!exists()) mkdirs() }
            val dest = File(targetDir, fileName)
            file.copyTo(dest, overwrite = true)
        }
    }

    protected abstract fun downloadAndInstall(update: AppUpdate): Job
    protected abstract fun downloadAndRootInstall(update: AppUpdate): Job
    protected abstract fun downloadAndShizukuInstall(update: AppUpdate): Job
    protected abstract fun cancelInstall(id: Int): Job
    protected abstract fun finishInstall(id: Int): Job
    protected abstract fun startDownloadProgress(id: Int)
    protected abstract fun finishDownloadProgress(id: Int)
}
