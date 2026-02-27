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
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.ui.activity.MainActivity
import android.util.Log
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile


class SessionInstaller(
    private val context: Context,
    private val installLog: InstallLog
) {

    companion object {
        const val INSTALL_ACTION = "installAction"
    }

    private val installMutex = AtomicBoolean(false)

    suspend fun install(id: Int, packageName: String, stream: InputStream) =
        install(id, packageName, listOf(stream))

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

            val intent = Intent(context, MainActivity::class.java).apply {
                action = "$INSTALL_ACTION.$id"
            }

            installMutex.lock()
            val pending = PendingIntent.getActivity(context, 0, intent, FLAG_MUTABLE)
            session.commit(pending.intentSender)
            session.close()
        }
    }

    fun rootInstall(file: File): Boolean {
        val res = Shell.cmd("pm install -r ${file.absolutePath}").exec().isSuccess
        file.delete()
        return res
    }

    @Suppress("DEPRECATION")
    fun shizukuInstall(file: File): Boolean {
        return try {
            val size = file.length()
            val process = Shizuku.newProcess(arrayOf("pm", "install", "-S", size.toString()), null, null)
            file.inputStream().use { input ->
                process.outputStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            val exitCode = process.waitFor()
            file.delete()
            exitCode == 0
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Shizuku install failed", e)
            file.delete()
            false
        }
    }

    @Suppress("DEPRECATION")
    fun shizukuInstallSplit(files: List<File>): Boolean {
        return try {
            val totalSize = files.sumOf { it.length() }

            // Create install session
            val createProcess = Shizuku.newProcess(
                arrayOf("pm", "install-create", "-S", totalSize.toString()), null, null
            )
            val createOutput = createProcess.inputStream.bufferedReader().readText()
            createProcess.waitFor()

            // Parse session ID from "Success: created install session [123456789]"
            val sessionId = Regex("\\[(\\d+)]").find(createOutput)?.groupValues?.get(1)
            if (sessionId == null) {
                Log.e("SessionInstaller", "Failed to parse session ID from: $createOutput")
                files.forEach { it.delete() }
                return false
            }

            // Write each APK to session
            files.forEachIndexed { index, file ->
                val writeProcess = Shizuku.newProcess(
                    arrayOf("pm", "install-write", "-S", file.length().toString(), sessionId, "$index.apk"),
                    null, null
                )
                file.inputStream().use { input ->
                    writeProcess.outputStream.use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                writeProcess.waitFor()
            }

            // Commit session
            val commitProcess = Shizuku.newProcess(
                arrayOf("pm", "install-commit", sessionId), null, null
            )
            val exitCode = commitProcess.waitFor()

            files.forEach { it.delete() }
            exitCode == 0
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Shizuku split install failed", e)
            files.forEach { it.delete() }
            false
        }
    }

    @Suppress("DEPRECATION")
    fun shizukuInstallXapk(xapkFile: File): Boolean {
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

            shizukuInstallSplit(tempFiles)
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Shizuku XAPK install failed", e)
            xapkFile.delete()
            false
        }
    }

    fun finish() = installMutex.unlock()

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
