package com.apkupdater.util

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
import com.apkupdater.BuildConfig
import com.apkupdater.R
import com.apkupdater.data.ui.AppInstallProgress
import android.util.Log
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.zip.ZipFile


class SessionInstaller(
    private val context: Context,
    private val installLog: InstallLog,
    private val downloader: Downloader
) {

    companion object {
        const val INSTALL_ACTION = "installAction"

        /**
         * How long a queued install waits for the one ahead of it before forcing its way in.
         *
         * Generous, because the wait is legitimate: the holder is showing the system
         * confirmation dialog and the user may be slow. But it has to be bounded. An
         * ownership-checked [finish] removed the accidental escape hatch the old
         * unlock-for-anyone had, so if the holder's result never arrives — the dialog was
         * opened and abandoned, and no broadcast is ever sent — an unbounded wait would hang
         * every later install for the life of the process, and with it the download-cache
         * sweep and the foreground notification, since the waiter never reaches its
         * `finally { background.end(id) }`.
         */
        private const val COMMIT_LOCK_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private val installMutex = Mutex()

    /**
     * Which install currently holds [installMutex], or null.
     *
     * The mutex serialises commits so only one system confirmation dialog is up at a time.
     * [finish] used to unlock it for whoever called, which collapsed that sequencing after the
     * first failure in a batch: install A committed and was waiting for the user, install B's
     * download failed, B's cancelInstall called finish(), and C — queued behind the lock —
     * committed on top of A's dialog. Tracked by hand rather than via Mutex's own owner
     * parameter because ids are boxed Ints and the owner comparison is not something to bet
     * a wedged install queue on.
     */
    @Volatile private var lockOwner: Int? = null
    private val ownerLock = Any()

    suspend fun install(id: Int, packageName: String, stream: InputStream, trackProgress: Boolean = true) =
        install(id, packageName, listOf(stream), trackProgress)

    /**
     * Returns the APK's real package name if it does NOT match [expected]
     * (when expected is non-blank), otherwise null. Prevents installing a
     * different app/channel alongside the target — e.g. a Brave Nightly APK
     * (com.brave.browser_nightly) over an installed Beta (com.brave.browser_beta).
     * If the APK can't be parsed we return null (don't block on uncertainty).
     */
    fun verifyPackage(file: File, expected: String): String? {
        if (expected.isBlank()) return null
        val actual = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
        }.getOrNull()
        if (actual == null) {
            // Couldn't parse the APK — allow the install rather than block on uncertainty,
            // but log it so a wrong-package slip-through (e.g. Brave) is diagnosable.
            Log.w("SessionInstaller", "verifyPackage: could not read package of ${file.name}; allowing '$expected'")
            return null
        }
        Log.d("SessionInstaller", "verifyPackage: apk=$actual expected=$expected")
        return if (actual != expected) actual else null
    }

    private suspend fun install(id: Int, packageName: String, streams: List<InputStream>, trackProgress: Boolean = true) {
        val packageInstaller: PackageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)

        if (Build.VERSION.SDK_INT > 24) {
            params.setOriginatingUid(android.os.Process.myUid())
        }

        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }

        val sessionId = packageInstaller.createSession(params)
        var bytes = 0L
        var committed = false
        try {
            packageInstaller.openSession(sessionId).use { session ->
                streams.forEach {
                    session.openWrite("$packageName.${randomUUID()}", 0, -1).use { output ->
                        if (trackProgress) {
                            bytes += it.copyToAndNotify(output, id, installLog, bytes)
                        } else {
                            it.copyTo(output)
                        }
                        it.close()
                        session.fsync(output)
                    }
                }

                // Deliver results to a BroadcastReceiver: unlike an Activity PendingIntent,
                // it is reliably delivered while the app is in the background.
                val intent = Intent(context, InstallReceiver::class.java).apply {
                    action = "$INSTALL_ACTION.$id"
                }

                // Point of no return for THIS install: every stream has been written and
                // fsynced into the session, so a Cancel can no longer stop anything. It has to
                // be marked HERE and not at the call sites, because on the Play and XAPK paths
                // the download itself streams through the loop above — marking it before the
                // call would make a whole 100 MB transfer refuse to cancel.
                downloader.beginInstall(id)
                acquireCommitLock(id)
                val pending = PendingIntent.getBroadcast(context, id, intent, FLAG_MUTABLE)
                try {
                    session.commit(pending.intentSender)
                    committed = true
                } catch (t: Throwable) {
                    // Nothing will ever deliver a result for this session, so nothing would
                    // ever call finish() to release the lock we just took.
                    synchronized(ownerLock) {
                        lockOwner = null
                        runCatching { installMutex.unlock() }
                    }
                    throw t
                }
                session.close()
            }
        } catch (t: Throwable) {
            // An uncommitted session keeps its staged bytes — a whole APK — in
            // /data/app/vmdl*.tmp until the system expires it days later. Cancelling a large
            // install mid-stream used to leak exactly that. Never abandon a committed one:
            // the install is already under way and only the result is still outstanding.
            if (!committed) runCatching { packageInstaller.abandonSession(sessionId) }
            throw t
        }
    }

    /**
     * Returns null on success, or a localized reason on failure — same contract as the Shizuku
     * methods. It used to return a bare Boolean and throw the command output away, so every
     * root failure in the app read "unexpected error" no matter what pm actually said.
     */
    fun rootInstall(file: File, fakePlayStore: Boolean = false): String? {
        val cmd = if (fakePlayStore) {
            "pm install -r -i com.android.vending ${file.absolutePath}"
        } else {
            "pm install -r ${file.absolutePath}"
        }
        // Collect stderr EXPLICITLY. libsu leaves Result.getErr() empty unless the job was
        // given a list for it (or FLAG_REDIRECT_STDERR was set), and plenty of ROMs print the
        // "Failure [INSTALL_FAILED_...]" line there — so reading only stdout would have left
        // the reason blank and put us straight back to "unexpected error".
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = Shell.cmd(cmd).to(out, err).exec()
        file.delete()
        if (result.isSuccess) return null
        val output = (err + out).filter { it.isNotBlank() }.joinToString(" ")
        Log.e("SessionInstaller", "Root install failed: $output")
        return parseInstallError(output)
    }

    private fun shizukuProcess(args: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, args, null, null) as Process
    }

    /** Returns null on success, or error message on failure. */
    fun shizukuInstall(file: File, fakePlayStore: Boolean = false): String? {
        return try {
            val size = file.length()
            if (size == 0L) {
                Log.e("SessionInstaller", "Shizuku install failed: downloaded file is empty")
                file.delete()
                return "Downloaded file is empty"
            }
            val args = mutableListOf("pm", "install", "-r", "-d")
            if (fakePlayStore) args.addAll(listOf("-i", "com.android.vending"))
            args.addAll(listOf("-S", size.toString()))
            val process = shizukuProcess(args.toTypedArray())
            file.inputStream().use { input ->
                process.outputStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            // pm writes "Failure [REASON]" to stdout AND/OR stderr depending on
            // the ROM — read both so signature mismatches aren't lost (was the
            // cause of the generic "Unknown error" before).
            val out = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            file.delete()
            if (exitCode != 0) {
                val combined = listOf(out, error).filter { it.isNotBlank() }.joinToString("\n")
                Log.e("SessionInstaller", "pm install failed (exit $exitCode): $combined")
                parseInstallError(combined)
            } else null
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Shizuku install failed", e)
            file.delete()
            parseInstallError(e.message.orEmpty())
        }
    }

    /** Returns null on success, or error message on failure. */
    fun shizukuInstallSplit(files: List<File>, fakePlayStore: Boolean = false): String? {
        return try {
            val totalSize = files.sumOf { it.length() }

            // Create install session
            val args = mutableListOf("pm", "install-create", "-r", "-d")
            if (fakePlayStore) args.addAll(listOf("-i", "com.android.vending"))
            args.addAll(listOf("-S", totalSize.toString()))
            val createProcess = shizukuProcess(args.toTypedArray())
            val createOutput = createProcess.inputStream.bufferedReader().readText()
            createProcess.waitFor()

            // Parse session ID from "Success: created install session [123456789]"
            val sessionId = Regex("\\[(\\d+)]").find(createOutput)?.groupValues?.get(1)
            if (sessionId == null) {
                Log.e("SessionInstaller", "Failed to parse session ID from: $createOutput")
                files.forEach { it.delete() }
                return "Failed to create install session"
            }

            // Write each APK to session
            files.forEachIndexed { index, file ->
                val writeProcess = shizukuProcess(
                    arrayOf("pm", "install-write", "-S", file.length().toString(), sessionId, "$index.apk")
                )
                file.inputStream().use { input ->
                    writeProcess.outputStream.use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                writeProcess.waitFor()
            }

            // Commit session — read both streams (ROM-dependent, see shizukuInstall)
            val commitProcess = shizukuProcess(arrayOf("pm", "install-commit", sessionId))
            val commitOut = commitProcess.inputStream.bufferedReader().readText()
            val commitErr = commitProcess.errorStream.bufferedReader().readText()
            val exitCode = commitProcess.waitFor()

            files.forEach { it.delete() }
            if (exitCode != 0) {
                val combined = listOf(commitOut, commitErr).filter { it.isNotBlank() }.joinToString("\n")
                Log.e("SessionInstaller", "pm install-commit failed: $combined")
                parseInstallError(combined)
            } else null
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Shizuku split install failed", e)
            files.forEach { it.delete() }
            parseInstallError(e.message.orEmpty())
        }
    }

    /** Returns null on success, or error message on failure. */
    fun shizukuInstallXapk(xapkFile: File, fakePlayStore: Boolean = false): String? {
        return try {
            // Extract APKs from XAPK (zip)
            val zip = ZipFile(xapkFile)
            val allEntries = zip.entries().toList()
            val wanted = selectSplitEntries(allEntries.map { it.name }).toSet()
            val apkEntries = allEntries.filter { it.name in wanted }
            val tempFiles = apkEntries.map { entry ->
                val apkFile = File(context.cacheDir, randomUUID())
                zip.getInputStream(entry).use { input ->
                    apkFile.outputStream().use { output -> input.copyTo(output) }
                }
                apkFile
            }
            zip.close()
            xapkFile.delete()

            shizukuInstallSplit(tempFiles, fakePlayStore)
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Shizuku XAPK install failed", e)
            xapkFile.delete()
            parseInstallError(e.message.orEmpty())
        }
    }

    /** Parses pm install error output into a localized, human-readable message. */
    private fun parseInstallError(error: String): String =
        context.getString(installErrorResId(error))

    /**
     * Takes the commit lock for [id], giving up on the previous holder after
     * [COMMIT_LOCK_TIMEOUT_MS]. A timed-out `lock()` leaves the mutex untouched, so forcing it
     * is safe: the worst case is a second confirmation dialog for a user who really did sit on
     * the first one for five minutes, against a permanent wedge if we waited forever.
     */
    private suspend fun acquireCommitLock(id: Int) {
        val acquired = withTimeoutOrNull(COMMIT_LOCK_TIMEOUT_MS) { installMutex.lock() } != null
        if (!acquired) {
            Log.w("SessionInstaller", "Commit lock held for too long by $lockOwner; taking it")
            synchronized(ownerLock) {
                lockOwner = null
                runCatching { installMutex.unlock() }
            }
            installMutex.lock()
        }
        synchronized(ownerLock) { lockOwner = id }
    }

    /**
     * Releases the commit lock, but only for the install that actually holds it. Called from
     * every path — including root and Shizuku, which never take this lock at all — so the
     * ownership check is what keeps one task's outcome from letting another task's queue jump.
     */
    fun finish(id: Int) = runCatching {
        // Both ViewModels subscribe to the install-status flow, so one result produces two
        // finish() calls on two IO threads. Check-then-act has to be atomic, or both can see
        // themselves as the owner and the second unlock releases whoever acquired in between.
        synchronized(ownerLock) {
            if (lockOwner != id) return@runCatching
            lockOwner = null
            installMutex.unlock()
        }
    }

    fun checkPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if(!context.packageManager.canRequestPackageInstalls()) {
                val uri = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                val intent = Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return false
            }
        }
        return true
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun installXapk(id: Int, packageName: String, stream: InputStream, totalSize: Long = 0L) {
        // Copy file to disk with progress tracking. The copy lands in cacheDir's ROOT, which
        // no sweep reaches — not Downloader.cleanUp(), which only touches cacheDir/downloads —
        // so anything thrown below used to strand a file the size of the whole XAPK, plus an
        // open ZipFile. Hence the finally.
        val file = File(context.cacheDir, randomUUID())
        var zip: ZipFile? = null
        try {
            file.outputStream().use { output ->
                var bytesCopied = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = stream.read(buffer)
                while (bytes >= 0) {
                    output.write(buffer, 0, bytes)
                    bytesCopied += bytes
                    if (totalSize > 0) {
                        installLog.emitProgress(AppInstallProgress(id, bytesCopied, totalSize))
                    }
                    bytes = stream.read(buffer)
                }
            }
            stream.close()

            // Get entries
            zip = ZipFile(file)
            val entries = zip.entries().toList()

            // Install the apks this device needs (skip progress tracking — download phase already
            // tracked). See selectSplitEntries for which ones.
            // TODO: Add root install support
            val wanted = selectSplitEntries(entries.map { it.name }).toSet()
            val apks = entries.filter { it.name in wanted }.map { zip.getInputStream(it) }
            install(id, packageName, apks, trackProgress = false)
        } finally {
            runCatching { stream.close() }
            runCatching { zip?.close() }
            file.delete()
        }
    }

    suspend fun playInstall(id: Int, packageName: String, streams: List<InputStream>) =
        install(id, packageName, streams)

}

/**
 * The entries of a split archive (XAPK, APKMirror's .apkm) worth installing.
 *
 * An .apkm carries a config split for EVERY processor — for Telegram 12.10.5 four of them, about
 * 20 MB each. Installing all of them is legal but writes the app to disk several times over. So:
 * the base, every split that is not an ABI split (densities, languages, features), and of the ABI
 * splits only the device's preferred one, which is what Play itself installs. When there is at
 * most one ABI split, or none of them matches the device, everything goes in as before and the
 * installer decides.
 */
internal fun selectSplitEntries(names: List<String>): List<String> {
    // ENDS in .apk. "Contains" also took folder entries, `.apk.idsig` signatures and OBB data
    // under Android/obb/<package>/ whenever the package name has ".apk" in it — and each of those
    // went into the install session as an "APK" that could not be parsed.
    val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
    fun abiOf(name: String) = ABI_SPLIT.find(name.substringAfterLast('/'))?.groupValues?.get(1)
    val abiSplits = apks.mapNotNull { abiOf(it) }.toSet()
    if (abiSplits.size <= 1) return apks
    val wanted = Build.SUPPORTED_ABIS
        .map { it.replace('-', '_') }
        .firstOrNull { it in abiSplits }
        ?: return apks
    return apks.filter { abiOf(it).let { abi -> abi == null || abi == wanted } }
}

/** `split_config.arm64_v8a.apk` (APKMirror) or `config.arm64_v8a.apk` (Play naming). */
private val ABI_SPLIT = Regex("^(?:split_)?config\\.(arm64_v8a|armeabi_v7a|armeabi|x86_64|x86|mips64|mips)\\.apk$")

/**
 * Maps a raw pm / PackageInstaller error message to a localized string resource id.
 * Shared by Shizuku/root (SessionInstaller) and the standard PackageInstaller path
 * (MainViewModel) so both decode signature mismatches and other failures the same way.
 */
fun installErrorResId(raw: String?): Int = when {
    raw.isNullOrBlank() -> R.string.install_error_unknown
    raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
        raw.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE") ||
        raw.contains("INCONSISTENT_CERTIFICATES", true) ||
        raw.contains("signatures do not match", true) -> R.string.install_error_signature
    raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE") -> R.string.install_error_downgrade
    raw.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> R.string.install_error_storage
    raw.contains("INSTALL_FAILED_INVALID_APK") ||
        raw.contains("INSTALL_PARSE_FAILED") -> R.string.install_error_invalid
    raw.contains("INSTALL_FAILED_OLDER_SDK") -> R.string.install_error_older_sdk
    raw.contains("INSTALL_FAILED_ALREADY_EXISTS") -> R.string.install_error_already_exists
    raw.contains("INSTALL_FAILED_CONFLICTING_PROVIDER") -> R.string.install_error_conflicting_provider
    raw.contains("INSTALL_FAILED_USER_RESTRICTED") -> R.string.install_error_blocked
    // Reported by Dmitry against build 160: Alipay from Aptoide failed with
    // "INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries" and the app said
    // only "unexpected error". The APK is real and installable — on a different processor.
    // A store that serves one build to every device makes this the ordinary way a download can
    // be useless, so it deserves its own sentence rather than the catch-all.
    raw.contains("INSTALL_FAILED_NO_MATCHING_ABIS") ||
        raw.contains("Failed to extract native libraries", true) -> R.string.install_error_abi
    // The base APK of a split app on its own. Also a "the file is fine, it is the wrong file"
    // failure, and the user can do something about it: install from a source that ships splits.
    raw.contains("INSTALL_FAILED_MISSING_SPLIT") -> R.string.install_error_missing_split
    // Play Protect refusing the package, which looks like a mysterious failure otherwise.
    raw.contains("INSTALL_FAILED_VERIFICATION_FAILURE") ||
        raw.contains("INSTALL_FAILED_VERIFICATION_TIMEOUT") -> R.string.install_error_verification
    else -> R.string.install_error_unknown
}

fun InputStream.copyToAndNotify(out: OutputStream, id: Int, installLog: InstallLog, total: Long, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    var bytesCopied: Long = 0
    val buffer = ByteArray(bufferSize)
    var bytes = read(buffer)
    while (bytes >= 0) {
        out.write(buffer, 0, bytes)
        bytesCopied += bytes
        installLog.emitProgress(AppInstallProgress(id, progress = total + bytesCopied))
        bytes = read(buffer)
    }
    return bytesCopied
}
