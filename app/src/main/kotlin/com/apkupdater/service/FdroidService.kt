package com.apkupdater.service

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Streaming
import retrofit2.http.Url

interface FdroidService {

    /**
     * `max-age=0`: always ask whether the index changed (build 188). The HTTP cache then sends the
     * ETag, and an unchanged index comes back as a 304 in half a second and is read from disk
     * instead of the 16.5 MB F-Droid serves — measured at 66 s from one address. F-Droid
     * publishes about once a day. Not `no-cache`: in OkHttp that skips the cache altogether.
     */
    @Streaming
    @Headers("Cache-Control: max-age=0")
    @GET
    suspend fun getJar(@Url url: String): Response<ResponseBody>

}
