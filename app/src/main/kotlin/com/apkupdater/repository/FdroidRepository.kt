package com.apkupdater.repository

import android.os.Build
import android.util.Log
import com.apkupdater.data.fdroid.FdroidApp
import com.apkupdater.data.fdroid.FdroidData
import com.apkupdater.data.fdroid.FdroidUpdate
import com.apkupdater.data.fdroid.toAppUpdate
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.Source
import com.apkupdater.data.ui.getApp
import com.apkupdater.data.ui.getVersionCode
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.FdroidService
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import okhttp3.ResponseBody
import java.io.InputStream
import java.util.jar.JarInputStream


class FdroidRepository(
    private val service: FdroidService,
    private val url: String,
    private val source: Source,
    private val prefs: Prefs
) {
    private val arch = Build.SUPPORTED_ABIS.toSet()
    private val api = Build.VERSION.SDK_INT

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val response = service.getJar("${url}index-v1.jar")
        val data = response.toDataStoppably()
        val appNames = apps.map { it.packageName }
        val updates = data.apps
            .asSequence()
            .filter { appNames.contains(it.packageName) }
            .filter { filterSignature(apps.getApp(it.packageName)!!, it) }
            .map { FdroidUpdate(data.packages[it.packageName]!![0], it) }
            .filter { it.apk.versionCode > apps.getVersionCode(it.app.packageName) }
            .parseUpdates(apps)
        emit(updates)
    }.catch {
        emit(emptyList())
        Log.e("FdroidRepository", "Error looking for updates.", it)
    }

    suspend fun search(text: String) = flow {
        val response = service.getJar("${url}index-v1.jar")
        val data = response.toDataStoppably()
        val updates = data.apps
            .asSequence()
            .map { FdroidUpdate(data.packages[it.packageName]!![0], it) }
            .filter { it.app.name.contains(text, true) || it.app.packageName.contains(text, true) || it.apk.apkName.contains(text, true) }
            .parseUpdates(null)
        emit(Result.success(updates))
    }.catch {
        emit(Result.failure(it))
        Log.e("FdroidRepository", "Error searching.", it)
    }

    private fun Sequence<FdroidUpdate>.parseUpdates(apps: List<AppInstalled>?) = this
        .filter { it.apk.minSdkVersion <= api }
        .filter { filterArch(it) }
        .filter { filterAlpha(it) }
        .filter { filterBeta(it) }
        .map { it.toAppUpdate(apps?.getApp(it.app.packageName), source, url) }
        .toList()

    private fun filterSignature(installed: AppInstalled, update: FdroidApp) = when {
        update.allowedAPKSigningKeys.isEmpty() -> true
        update.allowedAPKSigningKeys.contains(installed.signatureSha256) -> true
        else -> false
    }

    private fun filterAlpha(update: FdroidUpdate) = when {
        prefs.ignoreAlpha.get() && update.apk.versionName.contains("alpha", true) -> false
        else -> true
    }

    private fun filterBeta(update: FdroidUpdate) = when {
        prefs.ignoreBeta.get() && update.apk.versionName.contains("beta", true) -> false
        else -> true
    }

    private fun filterArch(update: FdroidUpdate) = when {
        update.apk.nativecode.isEmpty() -> true
        update.apk.nativecode.intersect(arch).isNotEmpty() -> true
        else -> false
    }

    /**
     * Parses the index, and gives up when the check is stopped.
     *
     * This is the one source that ignored cancellation. The index is several megabytes of
     * JSON streamed straight off the network into Gson — a single blocking call with no
     * suspension point anywhere inside it — and coroutine cancellation is cooperative, so
     * there was nothing to notice the check had been cancelled. Pressing Stop appeared to do
     * nothing at all whenever F-Droid (or Izzy, which is this same class) was enabled, while
     * with F-Droid switched off it stopped instantly.
     *
     * Closing the body from the job's completion handler breaks the read out of Gson with an
     * IOException. The handler runs on the thread doing the cancelling, so it does not have to
     * wait for the blocked one, and it also fires on the source timeout.
     */
    private suspend fun ResponseBody.toDataStoppably(): FdroidData {
        val job = currentCoroutineContext().job
        // Belt: breaks a read that has not started yet, and covers the socket going quiet.
        val handle = job.invokeOnCompletion { runCatching { close() } }
        return try {
            jarToJson(byteStream().stoppable(job))
        } finally {
            handle.dispose()
        }
    }

    /**
     * The braces: a stream that refuses to keep reading once the check is cancelled.
     *
     * Closing the body from a completion handler was the first attempt and it was not enough —
     * closing an OkHttp body does not reliably break a read that is already in flight, so the
     * parse carried on to the end and the whole check hung on it. Gson pulls this stream in 8 KB
     * chunks, so checking between chunks stops it within a few kilobytes, and those checks are
     * the only cancellation points a single blocking parse of a 14 MB index has.
     */
    private fun InputStream.stoppable(job: Job) = object : InputStream() {
        override fun read(): Int {
            job.ensureActive()
            return this@stoppable.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            job.ensureActive()
            return this@stoppable.read(b, off, len)
        }

        override fun available() = this@stoppable.available()

        override fun close() = this@stoppable.close()
    }

    private fun jarToJson(stream: InputStream): FdroidData {
        val jar = JarInputStream(stream)
        var entry = jar.nextJarEntry
        while (entry != null) {
            if (entry.name == "index-v1.json") {
                return Gson().fromJson(jar.reader(), FdroidData::class.java)
            }
            entry = jar.nextJarEntry
        }
        return FdroidData()
    }

}
