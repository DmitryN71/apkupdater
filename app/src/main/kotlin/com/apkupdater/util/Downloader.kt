package com.apkupdater.util

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream


class Downloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val auroraClient: OkHttpClient,
    private val dir: File
) {

    fun download(url: String): File {
        val file = File(dir, randomUUID())
        client.newCall(downloadRequest(url)).execute().use {
            if (it.isSuccessful) {
                it.body?.byteStream()?.copyTo(file.outputStream())
            }
        }
        return file
    }

    fun downloadFile(url: String, onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null): File {
        val c = when {
            url.contains("apkpure") -> apkPureClient
            url.contains("aurora") -> auroraClient
            else -> client
        }
        val file = File(dir, randomUUID())
        val response = c.newCall(downloadRequest(url)).execute()
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
        return file
    }

    data class StreamWithSize(val stream: InputStream, val size: Long)

    fun downloadStreamWithSize(url: String): StreamWithSize? = runCatching {
        val c = when {
            url.contains("apkpure") -> apkPureClient
            url.contains("aurora") -> auroraClient
            else -> client
        }
        val response = c.newCall(downloadRequest(url)).execute()
        if (response.isSuccessful) {
            response.body?.let { body ->
                val size = body.contentLength().let { if (it > 0) it else 0L }
                return StreamWithSize(body.byteStream(), size)
            }
        } else {
            response.close()
            Log.e("Downloader", "Download failed with error code: ${response.code}")
        }
        return null
    }.getOrElse {
        Log.e("Downloader", "Error downloading", it)
        null
    }

    fun downloadStream(url: String): InputStream? = runCatching {
        val c = when {
            url.contains("apkpure") -> apkPureClient
            url.contains("aurora") -> auroraClient
            else -> client
        }
        val response = c.newCall(downloadRequest(url)).execute()
        if (response.isSuccessful) {
            response.body?.let {
                return it.byteStream()
            }
        } else {
            response.close()
            Log.e("Downloader", "Download failed with error code: ${response.code}")
        }
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
