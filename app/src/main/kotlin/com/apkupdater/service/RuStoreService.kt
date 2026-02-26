package com.apkupdater.service

import com.apkupdater.data.rustore.RuStoreAppResponse
import com.apkupdater.data.rustore.RuStoreDownloadRequest
import com.apkupdater.data.rustore.RuStoreDownloadResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path


interface RuStoreService {

	@GET("applicationData/overallInfo/{packageName}")
	suspend fun getAppInfo(@Path("packageName") packageName: String): RuStoreAppResponse

	@POST("applicationData/download-link")
	suspend fun getDownloadLink(@Body request: RuStoreDownloadRequest): RuStoreDownloadResponse

}
