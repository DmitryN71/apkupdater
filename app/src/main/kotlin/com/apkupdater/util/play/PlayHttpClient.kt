package com.apkupdater.util.play

import android.util.Log
import com.aurora.gplayapi.data.models.PlayResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.Credentials
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit


class PlayHttpClient(
    cache: Cache
) : IProxyHttpClient {

    companion object {
        private const val POST = "POST"
        private const val GET = "GET"
    }

    private val _responseCode = MutableStateFlow(100)
    override val responseCode: StateFlow<Int> get() = _responseCode.asStateFlow()
    private val okHttpClientBuilder = OkHttpClient().newBuilder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(cache)
    private var okHttpClient = okHttpClientBuilder.build()

    override fun setProxy(proxyInfo: ProxyInfo): PlayHttpClient {
        val proxy = Proxy(
            if (proxyInfo.protocol == "SOCKS") Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(proxyInfo.host, proxyInfo.port)
        )

        val proxyUser = proxyInfo.proxyUser
        val proxyPassword = proxyInfo.proxyPassword

        if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
            okHttpClientBuilder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    return@proxyAuthenticator null
                }

                val credential = Credentials.basic(proxyUser, proxyPassword)
                response.request
                    .newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }

        okHttpClient = okHttpClientBuilder.proxy(proxy).build()
        return this
    }

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, requestBody: RequestBody): PlayResponse {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .method(POST, requestBody)
            .build()
        return processRequest(request)
    }

    @Throws(IOException::class)
    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .method(POST, "".toRequestBody(null))
            .build()
        return processRequest(request)
    }

    override fun postAuth(url: String, body: ByteArray): PlayResponse {
        val requestBody = body.toRequestBody("application/json".toMediaType(), 0, body.size)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "com.aurora.store-4.4.2-56")
            .method(POST, requestBody)
            .build()
        return processRequest(request)
    }

    @Throws(IOException::class)
    override fun post(url: String, headers: Map<String, String>, body: ByteArray): PlayResponse {
        val requestBody = body.toRequestBody(
            "application/x-protobuf".toMediaType(),
            0,
            body.size
        )
        return post(url, headers, requestBody)
    }

    @Throws(IOException::class)
    override fun get(url: String, headers: Map<String, String>): PlayResponse {
        return get(url, headers, mapOf())
    }

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .method(GET, null)
            .build()
        return processRequest(request)
    }

    override fun getAuth(url: String): PlayResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "com.aurora.store-4.4.2-56")
            .method(GET, null)
            .build()
        return processRequest(request)
    }

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse {
        val request = Request.Builder()
            .url(url + paramString)
            .headers(headers.toHeaders())
            .method(GET, null)
            .build()
        return processRequest(request)
    }

    /**
     * Raw bytes of the last Play search response.
     *
     * gplayapi 3.6.1+ collapses a search into ONE result cluster (it keys every cluster of a
     * search by the same id and puts them in a map), so the library's own return value drops
     * most of what Play sent. The bytes are kept here so PlayRepository can walk the response
     * itself with the library's public parsing methods. Taken, not read: clearing on read means
     * a response left behind by a search the user has already replaced cannot be consumed twice.
     */
    @Volatile
    private var searchBytes: ByteArray? = null

    fun takeSearchResponse(): ByteArray? {
        val bytes = searchBytes
        searchBytes = null
        return bytes
    }

    private fun processRequest(request: Request): PlayResponse {
        // Reset response code as flow doesn't sends the same value twice
        _responseCode.value = 0

        val call = okHttpClient.newCall(request)
        return buildPlayResponse(call.execute())
    }

    private fun buildUrl(url: String, params: Map<String, String>): HttpUrl {
        val urlBuilder = url.toHttpUrl().newBuilder()
        params.forEach {
            urlBuilder.addQueryParameter(it.key, it.value)
        }
        return urlBuilder.build()
    }

    /**
     * Seconds Google asked us to wait, from the last 429 it sent, or 0 if it sent no
     * Retry-After. We could not previously see this at all: the library's response model
     * carries only the status and the body, so the one piece of information that would tell us
     * how long a delivery throttle actually lasts was dropped on the floor. Read it out of a
     * user's log next time delivery is refused.
     */
    @Volatile
    var lastRetryAfterSeconds: Long = 0L
        private set

    private fun buildPlayResponse(response: Response): PlayResponse {
        // Built in one go: PlayResponse is an immutable data class since gplayapi 3.6. Mirrors
        // the library's own DefaultHttpClient. The content type is not decoration — on a
        // failed request bytesOrThrow() uses it to decide whether the body is a protobuf
        // ServerResponse carrying Google's own error message, or plain text to show as-is.
        // OkHttp 5's body is never null; an absent body reads as empty.
        val bytes = response.body.bytes()
        if (response.code == 429) {
            lastRetryAfterSeconds = response.header("Retry-After")?.toLongOrNull() ?: 0L
            Log.w(
                "PlayHttpClient",
                "Play throttled us: HTTP 429, Retry-After=${response.header("Retry-After") ?: "absent"} ${response.request.url}"
            )
        }
        _responseCode.value = response.code
        if (response.isSuccessful && response.request.url.encodedPath.endsWith("/fdfe/search")) {
            searchBytes = bytes
        }
        Log.i("PlayHttpClient", "OKHTTP [${response.code}] ${response.request.url}")
        return PlayResponse(
            isSuccessful = response.isSuccessful,
            code = response.code,
            responseBytes = bytes,
            errorString = if (!response.isSuccessful) response.message else String(),
            type = response.header("Content-Type", "application/octet-stream")
        )
    }
}
