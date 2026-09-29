package com.apkupdater.util

import android.util.Log
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


class Downloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val auroraClient: OkHttpClient,
    private val dir: File
) {

    companion object {
        /**
         * Extra attempts after the first one fails on a recoverable error. Five attempts with
         * the backoff below span ~25 s, which is what the feature is actually for: a Wi-Fi to
         * mobile handover regularly takes ten seconds or more, and a shorter budget would burn
         * through every retry while the phone is still switching networks.
         */
        private const val MAX_RETRIES = 5
        /** Backoff doubles per attempt (1, 2, 4, 8 s) but stops growing here. */
        private const val MAX_BACKOFF_MS = 10_000L
        /**
         * Partial downloads live in this subdirectory of the download dir. Public because
         * anything that sweeps the download dir has to leave it alone (see MainActivity).
         */
        const val PARTIAL_DIR = "partial"
        /** Partials nobody came back for are dropped after this long. */
        private const val PARTIAL_TTL_MS = 24 * 60 * 60 * 1000L
        /** Progress is reported at most this often; a 100 MB APK is thousands of reads. */
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val BUFFER_SIZE = 64 * 1024
        /** Server-side hiccups worth another attempt; a 404 or 403 will never fix itself. */
        private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)

        /**
         * A download slower than [SLOW_BYTES_PER_SECOND] over [SLOW_WINDOW_MS] moves to another
         * host — but only where another host can serve the very same file (see [candidatesFor]).
         * 200 KB/s turns a 100 MB APK into an eight-minute wait; eight seconds is long enough
         * not to judge a host on its TCP ramp-up.
         */
        private const val SLOW_WINDOW_MS = 8_000L
        private const val SLOW_BYTES_PER_SECOND = 200L * 1024

        /** AppGallery's download CDN: `appdlc-<region>.hispace.dbankcloud.com|ru`. */
        private val APPGALLERY_HOST = Regex("^appdlc-dr[a-z]+\\.hispace\\.dbankcloud\\.(?:com|ru)$")

        /**
         * Every regional AppGallery CDN, fastest first as measured from Moscow on 2026-09-29
         * (16 MB of one APK each: Russia 14.4 MB/s, Europe 11.5, China 9.2, Asia 7.4). All four
         * served the same file on the same path, so any of them can finish what another began.
         */
        private val APPGALLERY_HOSTS = listOf(
            "appdlc-drru.hispace.dbankcloud.ru",
            "appdlc-dre.hispace.dbankcloud.com",
            "appdlc-drcn.hispace.dbankcloud.com",
            "appdlc-dra.hispace.dbankcloud.com"
        )
    }

    /**
     * Per-install download state. The cancelled flag deliberately outlives the individual
     * Calls: a retry builds a NEW Call, so without it a cancel landing between two attempts
     * would be lost and the download would carry on after the user stopped it.
     */
    private class Session {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        @Volatile var cancelled = false
        /** A task is running for this id: between BackgroundInstaller's begin() and end(). */
        @Volatile var active = false
        /** The download phase is over and the installer has the file. Cancel is too late. */
        @Volatile var installing = false
    }

    private val sessions = ConcurrentHashMap<Int, Session>()

    /** Partial file names currently being written, so two downloads can't share one file. */
    private val activePartials: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** HTTP status that isn't an I/O failure — carries the code so we know whether to retry. */
    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /** An attempt that was alive but too slow, abandoned so another host can take over. */
    private class SlowHostException(val bytesPerSecond: Long) : IOException("Download too slow")

    /**
     * Marks the start of a fresh task for [id], clearing a cancel left over from a previous one.
     * Called once per task from [BackgroundInstaller.begin] — deliberately NOT from the download
     * methods themselves, because one task calls them repeatedly (a Play install downloads each
     * split separately under the same id) and clearing the flag between two of those calls would
     * silently drop a cancel that landed in that gap, leaving an unstoppable background download.
     *
     * Clears the flag in place rather than replacing the Session, so all of a task's calls stay
     * in one list and a single cancel reaches every one of them.
     */
    fun beginDownloads(id: Int) {
        sessions.computeIfAbsent(id) { Session() }.apply {
            cancelled = false
            installing = false
            active = true
        }
    }

    /**
     * Marks the task finished, from [BackgroundInstaller.end]. While any task is active
     * [cleanUp] refuses to delete, so this is also what lets the last one out sweep.
     */
    fun endDownloads(id: Int) {
        sessions[id]?.apply {
            active = false
            installing = false
            // Streaming downloads deliberately leave their Call registered while the installer
            // reads the body, and nothing ever removed them again, so the list grew for the
            // life of the process. The task is over here, so nothing can still need them.
            calls.clear()
        }
    }

    /**
     * The download is done and the installer now owns the file. Called at the point of no
     * return in every install path, so [isCancellable] can tell an honest "too late" from a
     * cancel that will still work — and so a cancel pressed here never sets the flag that
     * would go on to mute the install's own failure message.
     */
    fun beginInstall(id: Int) {
        sessions[id]?.installing = true
    }

    /** True while cancelling this task would actually stop something. */
    fun isCancellable(id: Int) = sessions[id]?.let { it.active && !it.installing } == true

    private fun registerCall(id: Int?, call: Call) {
        if (id == null) return
        val session = sessions.computeIfAbsent(id) { Session() }
        session.calls.add(call)
        // Applies a cancel that arrived while we were between attempts.
        if (session.cancelled) runCatching { call.cancel() }
    }

    private fun unregisterCall(id: Int?, call: Call) {
        id?.let { sessions[it]?.calls?.remove(call) }
    }

    /** True once [cancel] was called for this task and no new one has begun. */
    fun isCancelled(id: Int?) = id != null && sessions[id]?.cancelled == true

    /** Cancels all in-flight HTTP calls for the given install id. */
    fun cancel(id: Int) {
        val session = sessions.computeIfAbsent(id) { Session() }
        session.cancelled = true
        session.calls.toList().forEach { runCatching { it.cancel() } }
    }

    fun downloadFile(
        url: String,
        id: Int? = null,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        // A URL OkHttp cannot even parse will not parse on the next attempt either. This is not
        // hypothetical: when Google Play rate-limits delivery (HTTP 429) the library still hands
        // back file entries, just with an EMPTY url — and each of those used to burn the full
        // retry budget, ~25 s apiece, for several apps at once, before reporting nothing useful.
        if (url.toHttpUrlOrNull() == null) throw IOException("Download failed: no usable link")

        // Prune here, not only from cleanUp(): cleanUp() is skipped entirely when the user
        // turns "Clean Up After Install" off, and without this a run of failed downloads would
        // leave partials sitting on disk forever. Every download starts by taking out the trash.
        prunePartials()

        // The resumable name is derived from the URL, so two downloads of the same URL would
        // write into one file and corrupt it. That can happen: an app listed in both Updates
        // and Search can be started from either screen. Whoever claims the name first resumes;
        // a second concurrent download gets a private file and simply doesn't resume.
        val name = partialName(url)
        val resumable = activePartials.add(name)
        val partial = File(partialDir(), if (resumable) name else "${randomUUID()}.part")
        var lastError: Throwable? = null

        // Where to fetch from: normally just [url]; for AppGallery, its regional twins too. They
        // all write into the one partial, keyed by the ORIGINAL url (trap 1 in the resume notes),
        // and the Content-Range check in downloadAttempt still refuses a host that resumes from
        // anywhere but our offset — so moving mid-file cannot splice two files together.
        val candidates = candidatesFor(url)
        var host = 0
        val startedAt = System.currentTimeMillis()
        val startBytes = if (partial.exists()) partial.length() else 0L

        try {
            var attempt = 0
            var waitFirst = false
            while (true) {
                if (waitFirst && !backOff(id, attempt)) throw IOException("Canceled")
                waitFirst = false
                val current = candidates[host]
                // The watchdog runs only while there is somewhere else to go. On the last host
                // the download continues at whatever speed it gets: a slow network is slow on
                // every host, and giving up would be worse than waiting.
                val watchdog = host < candidates.lastIndex
                try {
                    if (downloadAttempt(current, id, partial, onProgress, watchdog)) {
                        logCompletion(current, partial.length() - startBytes, startedAt)
                        return finishPartial(partial)
                    }
                    lastError = IOException("Download incomplete")
                } catch (e: SlowHostException) {
                    // Not a failure, so no backoff and no retry spent: the bytes so far are in
                    // the partial, and the next host resumes from them.
                    Log.w(
                        "Downloader",
                        "Slow host ${hostOf(current)}: ${e.bytesPerSecond / 1024} KB/s at " +
                            "${partial.length() / 1048576} MB, moving to ${hostOf(candidates[host + 1])}"
                    )
                    // A Cancel that landed while this host was being dropped must not be carried
                    // off to the next one.
                    if (isCancelled(id)) throw IOException("Canceled")
                    host++
                    continue
                } catch (e: HttpStatusException) {
                    // A status that will never fix itself ends the download — unless another
                    // host is left to ask. An alternative host is a guess, so its refusal must
                    // not stop a download the original host was serving perfectly well.
                    if (e.code !in RETRYABLE_CODES && host == candidates.lastIndex) throw e
                    lastError = e
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException || t is InterruptedException) throw t
                    if (isCancelled(id)) throw t
                    lastError = t
                }
                Log.e("Downloader", "Download attempt ${attempt + 1} failed: $current", lastError)
                attempt++
                if (attempt > MAX_RETRIES) break
                // A host that fails outright — a stall that ran into the read timeout, a dropped
                // connection — is also a reason to try the next one, when there is one.
                if (host < candidates.lastIndex) host++
                waitFirst = true
            }
        } finally {
            if (resumable) {
                activePartials.remove(name)
            } else {
                // A private file no later download can ever match is dead weight — drop it now
                // instead of letting it age out. On success finishPartial already moved it away.
                partial.delete()
            }
            // An explicit cancel means "forget it", so don't hoard the bytes. Keeping them only
            // pays off if the very same URL comes back, and Play and RuStore mint a fresh token
            // on every request — their partials can never be resumed and would just sit in the
            // cache until they aged out (measured: 90 MB after a few cancelled Play attempts).
            // A network failure still keeps its partial; that is the case resume exists for.
            if (isCancelled(id)) partial.delete()
        }
        // The partial file is kept on purpose — the next attempt picks up where this left off.
        throw lastError ?: IOException("Download failed")
    }

    /**
     * One HTTP attempt, appending to [file]. Returns true once the file is complete.
     *
     * Resumes with a Range header when a partial is already on disk: a dropped connection
     * on a 100 MB APK used to mean starting again from zero.
     */
    private fun downloadAttempt(
        url: String,
        id: Int?,
        file: File,
        onProgress: ((Long, Long) -> Unit)?,
        watchdog: Boolean = false
    ): Boolean {
        val have = if (file.exists()) file.length() else 0L
        val request = Request.Builder().url(url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val call = clientFor(url).newCall(request)
        registerCall(id, call)
        try {
            call.execute().use { response ->
                // Our partial is stale or already past the end of the file — start over.
                if (response.code == 416) {
                    file.delete()
                    return false
                }
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                val body = response.body ?: throw IOException("Empty response body")
                // 206 means the server honoured the Range. A plain 200 means it ignored it
                // and is sending the whole file, so whatever we had must be overwritten.
                val resumed = response.code == 206 && have > 0
                // A 206 alone doesn't prove the server resumed from where we asked. If it
                // starts somewhere else — or won't say where — appending splices two different
                // parts of the file together, and the length check below can't see that: the
                // result is exactly as long as promised. Start clean instead.
                if (resumed && contentRangeStart(response) != have) {
                    file.delete()
                    return false
                }
                val start = if (resumed) have else 0L
                val total = totalSize(response, body.contentLength(), start)
                var written = start
                var lastReport = 0L
                // "Clear cache" in Settings deletes this directory outright, and it can be
                // tapped mid-download. Recreating it here lets the attempt restart and finish
                // instead of burning every retry on FileNotFoundException.
                file.parentFile?.mkdirs()
                FileOutputStream(file, resumed).use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (watchdog) {
                            windowBytes += read
                            val now = System.currentTimeMillis()
                            val span = now - windowStart
                            if (span >= SLOW_WINDOW_MS) {
                                val rate = windowBytes * 1000 / span
                                // Thrown from inside use{}, which closes — and so flushes — the
                                // file: everything received so far stays in the partial.
                                if (rate < SLOW_BYTES_PER_SECOND) throw SlowHostException(rate)
                                windowStart = now
                                windowBytes = 0L
                            }
                        }
                        if (total > 0 && onProgress != null) {
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                onProgress(written, total)
                            }
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                }
                if (total <= 0) {
                    // No length anywhere, so a clean EOF and a cut connection look identical.
                    // Never trust a RESUMED file here: its bytes would be spliced onto whatever
                    // we already had with nothing to check the result against — start it over.
                    if (resumed) {
                        file.delete()
                        return false
                    }
                    return written > 0
                }
                // More bytes than the server promised means what we had on disk was not an
                // earlier piece of this file — throw it away and fetch it whole.
                if (written > total) {
                    file.delete()
                    return false
                }
                if (written >= total) onProgress?.invoke(written, total)
                return written >= total
            }
        } finally {
            unregisterCall(id, call)
        }
    }

    /**
     * The hosts that can serve [url]: itself first, then — for AppGallery only — the same path on
     * every other regional CDN. Anything else has exactly one host, and behaves as it always did.
     */
    private fun candidatesFor(url: String): List<String> {
        val parsed = url.toHttpUrlOrNull() ?: return listOf(url)
        if (!APPGALLERY_HOST.matches(parsed.host)) return listOf(url)
        return listOf(url) + APPGALLERY_HOSTS
            .filter { it != parsed.host }
            .map { parsed.newBuilder().host(it).build().toString() }
    }

    private fun hostOf(url: String) = url.toHttpUrlOrNull()?.host ?: "?"

    /**
     * One line per finished download: where it came from and how fast. Written for every source,
     * so that "downloads are slow" in Copy App Logs names the host and the speed instead of
     * leaving it to guesswork — which is exactly what the first AppGallery report had to do.
     */
    private fun logCompletion(url: String, bytes: Long, startedAt: Long) {
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(0.001)
        val mb = bytes / 1048576.0
        Log.i(
            "Downloader",
            "Downloaded %.1f MB from %s in %.1f s (%.2f MB/s)".format(mb, hostOf(url), seconds, mb / seconds)
        )
    }

    /** Offset the server says it resumed from ("bytes 100-999/1000" -> 100), or null if unstated. */
    private fun contentRangeStart(response: Response): Long? = response.header("Content-Range")
        ?.substringAfter("bytes ", "")
        ?.substringBefore('-', "")
        ?.trim()
        ?.toLongOrNull()

    /** Real file size: Content-Range's total when present, else Content-Length plus what we had. */
    private fun totalSize(response: Response, contentLength: Long, start: Long): Long {
        response.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { return it }
        return if (contentLength > 0) contentLength + start else 0L
    }

    /** Waits between attempts in slices, so a user cancel is noticed without waiting it out. */
    private fun backOff(id: Int?, attempt: Int): Boolean {
        val total = (1000L * (1 shl (attempt - 1))).coerceAtMost(MAX_BACKOFF_MS)
        var waited = 0L
        while (waited < total) {
            if (isCancelled(id)) return false
            Thread.sleep(100)
            waited += 100
        }
        return !isCancelled(id)
    }

    /** Moves a completed partial out of the resume directory so cleanUp() can reclaim it. */
    private fun finishPartial(partial: File): File {
        if (!partial.exists() || partial.length() == 0L) {
            partial.delete()
            throw IOException("Download failed: empty or missing file")
        }
        val file = File(dir, randomUUID())
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
        return file
    }

    /**
     * Stable name for a URL's partial, so a later attempt finds the earlier one's bytes.
     *
     * Hashes the WHOLE URL, query included. Hashing only the path looks tempting — signed
     * links rotate their token while the file stays the same — but Google Play serves every
     * APK and every split from one path (/download/by-token/download) and puts the file's
     * identity entirely in the query, so that would make unrelated files share a partial and
     * splice into each other. The price is that Play and RuStore resume within one download
     * but not across separate attempts, since their URL is new every time.
     */
    private fun partialName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        return "$hex.part"
    }

    private fun partialDir() = File(dir, PARTIAL_DIR).apply { if (!exists()) mkdirs() }

    data class StreamWithSize(val stream: InputStream, val size: Long)

    fun downloadStreamWithSize(url: String, id: Int? = null): StreamWithSize? = runCatching {
        val call = clientFor(url).newCall(downloadRequest(url))
        registerCall(id, call)
        val response = try {
            call.execute()
        } catch (t: Throwable) {
            unregisterCall(id, call)
            throw t
        }
        // NB: Call is unregistered when the body stream is fully consumed downstream;
        // we don't unregister here because the stream is still being read.
        if (response.isSuccessful) {
            response.body?.let { body ->
                val size = body.contentLength().let { if (it > 0) it else 0L }
                return StreamWithSize(body.byteStream(), size)
            }
        } else {
            response.close()
            unregisterCall(id, call)
            Log.e("Downloader", "Download failed with error code: ${response.code}")
        }
        unregisterCall(id, call)
        return null
    }.getOrElse {
        Log.e("Downloader", "Error downloading", it)
        null
    }

    fun downloadStream(url: String, id: Int? = null): InputStream? = runCatching {
        val call = clientFor(url).newCall(downloadRequest(url))
        registerCall(id, call)
        val response = try {
            call.execute()
        } catch (t: Throwable) {
            unregisterCall(id, call)
            throw t
        }
        if (response.isSuccessful) {
            response.body?.let {
                return it.byteStream()
            }
        } else {
            response.close()
            unregisterCall(id, call)
            Log.e("Downloader", "Download failed with error code: ${response.code}")
        }
        unregisterCall(id, call)
        return null
    }.getOrElse {
        Log.e("Downloader", "Error downloading", it)
        null
    }

    fun cleanUp() = runCatching {
        // NEVER sweep while a task is running. A downloaded APK sits in this directory between
        // the download finishing and the installer reading it — waiting for the install mutex,
        // or being verified — and deleting it in that window fails the install with a
        // meaningless error. Rotating the screen did exactly that on every "Update all",
        // because MainActivity swept on each onCreate, and so did any other app's install
        // finishing. The last task out calls this from BackgroundInstaller.end(), which is
        // the only moment it can safely do its job.
        if (sessions.values.any { it.active }) {
            // Age-based and therefore safe: a partial being written right now has a fresh
            // timestamp, so it is never the one pruned.
            prunePartials()
            return@runCatching
        }
        // Delete finished downloads, but keep the partial directory: it holds resumable
        // files, and cleanUp() also runs on the failure path — wiping it there would
        // defeat resume at exactly the moment it is needed.
        dir.listFiles()?.forEach { if (it.name != PARTIAL_DIR) it.delete() }
        prunePartials()
        // Evict OkHttp cache to remove cached APK responses
        client.cache?.evictAll()
    }.getOrElse {
        Log.e("Downloader", "Error during cleanup", it)
    }

    /** Drops abandoned partials, so a 100 MB APK nobody retried can't sit there forever. */
    private fun prunePartials() {
        val cutoff = System.currentTimeMillis() - PARTIAL_TTL_MS
        partialDir().listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun clientFor(url: String) = when {
        url.contains("apkpure") -> apkPureClient
        url.contains("aurora") -> auroraClient
        else -> client
    }

    private fun downloadRequest(url: String) = Request.Builder().url(url).build()

}
