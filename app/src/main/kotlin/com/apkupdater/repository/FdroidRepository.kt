package com.apkupdater.repository

import android.os.Build
import android.os.SystemClock
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
import com.apkupdater.util.AppJournal
import com.google.gson.Gson
import com.google.gson.JsonIOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
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
        val data = index()
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
        Log.e("FdroidRepository", "Error looking for updates.", it)
        // Rethrown, not swallowed into an empty list: UpdatesRepository then counts this source as
        // failed and keeps its earlier cards. An empty answer here read as "no updates" — with
        // no network at all, a check of this one source said so on the screen.
        throw it
    }

    suspend fun search(text: String) = flow {
        val data = index()
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

    /**
     * The repository's index, and a journal line saying whether it came from the HTTP cache
     * (unchanged since the last check: a 304) or was downloaded, and how long it took (build 188).
     */
    private suspend fun index(): FdroidData {
        val started = SystemClock.elapsedRealtime()
        val response = service.getJar("${url}index-v1.jar")
        val body = response.body() ?: throw HttpException(response)
        val data = body.toDataStoppably()
        val network = response.raw().networkResponse
        val how = if (network == null || network.code == 304) "unchanged, read from the cache" else "downloaded"
        val seconds = "%.1f".format(java.util.Locale.US, (SystemClock.elapsedRealtime() - started) / 1000.0)
        AppJournal.add("FdroidRepository", "${source.name}: index $how in $seconds s")
        return data
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
            val stream = byteStream().stoppable(job)
            jarToJson(stream).also {
                // Read to the end (build 188). The index is the jar's last entry, so a few hundred
                // bytes are left after it, and until the body is read out OkHttp's cache does not
                // keep the response — every check downloaded the whole 16.5 MB again, and the
                // unclosed body was the "A resource failed to call close" in Dmitry's log.
                stream.skip(Long.MAX_VALUE)
                while (stream.read() != -1) Unit
            }
        } catch (e: JsonIOException) {
            // Gson wraps whatever the stream throws. A check that was stopped then read as F-Droid
            // failing ("JsonIOException: JobCancellationException …" in the journal), and the
            // call timeout as a parse error. Unwrapped, a stop is a stop again.
            val cause = e.cause
            if (cause is CancellationException || cause is IOException) throw cause
            throw e
        } finally {
            handle.dispose()
            close()
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
