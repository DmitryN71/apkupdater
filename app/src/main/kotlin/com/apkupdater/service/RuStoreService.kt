package com.apkupdater.service

import com.apkupdater.data.rustore.RuStoreAppResponse
import com.apkupdater.data.rustore.RuStoreBatchRequest
import com.apkupdater.data.rustore.RuStoreBatchResponse
import com.apkupdater.data.rustore.RuStoreDownloadRequest
import com.apkupdater.data.rustore.RuStoreDownloadResponse
import com.apkupdater.data.rustore.RuStoreSearchResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query


interface RuStoreService {

	@GET("applicationData/overallInfo/{packageName}")
	suspend fun getAppInfo(@Path("packageName") packageName: String): RuStoreAppResponse

	@POST("applicationData/newApps")
	suspend fun getBatchUpdates(@Body request: RuStoreBatchRequest): RuStoreBatchResponse

	@POST("applicationData/v2/download-link")
	suspend fun getDownloadLink(@Body request: RuStoreDownloadRequest): RuStoreDownloadResponse

	@GET("applicationData/apps")
	suspend fun searchApps(
		@Query("query") query: String,
		@Query("pageNumber") page: Int = 0,
		@Query("pageSize") size: Int = 20
	): RuStoreSearchResponse

}
