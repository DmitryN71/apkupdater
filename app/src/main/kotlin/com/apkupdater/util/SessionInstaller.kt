package com.apkupdater.util

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
import androidx.core.content.ContextCompat.startActivity
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
import java.util.zip.ZipFile


class SessionInstaller(
    private val context: Context,
    private val installLog: InstallLog
) {

    companion object {
        const val INSTALL_ACTION = "installAction"
    }

    private val installMutex = Mutex()

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

            installMutex.lock()
            val pending = PendingIntent.getBroadcast(context, id, intent, FLAG_MUTABLE)
            session.commit(pending.intentSender)
            session.close()
        }
    }

    fun rootInstall(file: File, fakePlayStore: Boolean = false): Boolean {
        val cmd = if (fakePlayStore) {
            "pm install -r -i com.android.vending ${file.absolutePath}"
        } else {
            "pm install -r ${file.absolutePath}"
        }
        val res = Shell.cmd(cmd).exec().isSuccess
        file.delete()
        return res
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
            val apkEntries = zip.entries().toList().filter { it.name.contains(".apk") }
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

    fun finish() = runCatching { installMutex.unlock() }

    fun checkPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if(!context.packageManager.canRequestPackageInstalls()) {
                val uri = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                val intent = Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(context, intent, null)
                return false
            }
        }
        return true
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun installXapk(id: Int, packageName: String, stream: InputStream, totalSize: Long = 0L) {
        // Copy file to disk with progress tracking
        val file = File(context.cacheDir, randomUUID())
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
        val zip = ZipFile(file)
        val entries = zip.entries().toList()

        // Install all the apks (skip progress tracking — download phase already tracked)
        // TODO: Try to install only needed apks
        // TODO: Add root install support
        val apks = entries.filter { it.name.contains(".apk") }.map { zip.getInputStream(it) }
        install(id, packageName, apks, trackProgress = false)

        // Cleanup
        zip.close()
        file.delete()
    }

    suspend fun playInstall(id: Int, packageName: String, streams: List<InputStream>) =
        install(id, packageName, streams)

}

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
