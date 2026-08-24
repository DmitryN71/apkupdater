package com.apkupdater.service

import com.apkupdater.data.rustore.RuStoreAppResponse
import com.apkupdater.data.rustore.RuStoreBatchRequest
import com.apkupdater.data.rustore.RuStoreBatchResponse
import com.apkupdater.data.rustore.RuStoreDownloadRequest
import com.apkupdater.data.rustore.RuStoreDownloadResponse
import com.apkupdater.data.rustore.RuStoreSearchResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query


/**
 * Every call takes an explicit `deviceType`, because RuStore treats it as a filter rather than a
 * hint: a phone app is invisible to a `tv` request and a TV app is invisible to a `mobile` one
 * (verified — Виму HD answers 404 as `mobile` and 200 as `tv`, and vice versa for МАКС). Sending
 * one fixed value would therefore hide half the catalogue, so callers ask for both and merge.
 */
interface RuStoreService {

	@GET("applicationData/overallInfo/{packageName}")
	suspend fun getAppInfo(
		@Path("packageName") packageName: String,
		@Header("deviceType") deviceType: String
	): RuStoreAppResponse

	@POST("applicationData/newApps")
	suspend fun getBatchUpdates(
		@Body request: RuStoreBatchRequest,
		@Header("deviceType") deviceType: String
	): RuStoreBatchResponse

	@POST("v3/showcase/apps/download-link")
	suspend fun getDownloadLink(
		@Body request: RuStoreDownloadRequest,
		@Header("deviceType") deviceType: String
	): RuStoreDownloadResponse

	@GET("applicationData/apps")
	suspend fun searchApps(
		@Query("query") query: String,
		@Header("deviceType") deviceType: String,
		@Query("pageNumber") page: Int = 0,
		@Query("pageSize") size: Int = 20
	): RuStoreSearchResponse

}
