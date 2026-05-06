package com.apkupdater.util

import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


class Downloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val auroraClient: OkHttpClient,
    private val dir: File
) {

    // Active OkHttp Calls keyed by install id, so we can cancel mid-download
    private val activeCalls = ConcurrentHashMap<Int, MutableList<Call>>()

    private fun registerCall(id: Int?, call: Call) {
        if (id != null) {
            activeCalls.computeIfAbsent(id) { Collections.synchronizedList(mutableListOf()) }.add(call)
        }
    }

    private fun unregisterCall(id: Int?, call: Call) {
        id?.let { activeCalls[it]?.remove(call) }
    }

    /** Cancels all in-flight HTTP calls for the given install id. */
    fun cancel(id: Int) {
        activeCalls.remove(id)?.forEach { runCatching { it.cancel() } }
    }

    fun download(url: String): File {
        val file = File(dir, randomUUID())
        client.newCall(downloadRequest(url)).execute().use { response ->
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return file
    }

    fun downloadFile(
        url: String,
        id: Int? = null,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        val c = when {
            url.contains("apkpure") -> apkPureClient
            url.contains("aurora") -> auroraClient
            else -> client
        }
        val file = File(dir, randomUUID())
        val call = c.newCall(downloadRequest(url))
        registerCall(id, call)
        try {
            val response = call.execute()
            if (response.isSuccessful) {
                response.body?.let { body ->
                    val totalSize = body.contentLength().let { if (it > 0) it else 0L }
                    file.outputStream().use { output ->
                        val stream = body.byteStream()
                        var bytesCopied = 0L
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytes = stream.read(buffer)
                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            if (totalSize > 0) onProgress?.invoke(bytesCopied, totalSize)
                            bytes = stream.read(buffer)
                        }
                    }
                }
            } else {
                Log.e("Downloader", "Download failed with error code: ${response.code}")
            }
            response.close()
        } finally {
            unregisterCall(id, call)
        }
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            throw java.io.IOException("Download failed: empty or missing file")
        }
        return file
    }

    data class StreamWithSize(val stream: InputStream, val size: Long)

    fun downloadStreamWithSize(url: String, id: Int? = null): StreamWithSize? = runCatching {
        val c = when {
            url.contains("apkpure") -> apkPureClient
            url.contains("aurora") -> auroraClient
            else -> client
        }
        val call = c.newCall(downloadRequest(url))
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
        val c = when {
            url.contains("apkpure") -> apkPureClient
            url.contains("aurora") -> auroraClient
            else -> client
        }
        val call = c.newCall(downloadRequest(url))
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
        // Delete downloaded APK/APKS files
        dir.listFiles()?.forEach { it.delete() }
        // Evict OkHttp cache to remove cached APK responses
        client.cache?.evictAll()
    }.getOrElse {
        Log.e("Downloader", "Error during cleanup", it)
    }

    private fun downloadRequest(url: String) = Request.Builder().url(url).build()

}
