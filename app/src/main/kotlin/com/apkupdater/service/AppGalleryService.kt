package com.apkupdater.service

import com.apkupdater.data.appgallery.AppGalleryDetailResponse
import com.apkupdater.data.appgallery.AppGalleryHandshake
import com.apkupdater.data.appgallery.AppGallerySearchResponse
import com.apkupdater.data.appgallery.AppGalleryUpdatesResponse
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url


/**
 * The AppGallery client API: one path, `/hwmarket/api/clientApi`, three methods we use, and a
 * host that depends on the zone the handshake reports — hence `@Url` on every call rather than a
 * fixed base.
 *
 * The parameter map is sent SORTED by key. Whether the server insists is not proven; the real
 * client does it, our probes did it, and it costs nothing to keep. Callers pass a LinkedHashMap
 * already in order, because Retrofit writes the fields in iteration order.
 */
interface AppGalleryService {

	@FormUrlEncoded
	@POST
	suspend fun handshake(
		@Url url: String,
		@FieldMap fields: Map<String, String>
	): AppGalleryHandshake

	/** `client.updateCheck` — every installed package in one request. */
	@FormUrlEncoded
	@POST
	suspend fun updateCheck(
		@Url url: String,
		@FieldMap fields: Map<String, String>
	): AppGalleryUpdatesResponse

	/** `client.getTabDetail` with `searchApp|<query>` — the store's own text search. */
	@FormUrlEncoded
	@POST
	suspend fun search(
		@Url url: String,
		@FieldMap fields: Map<String, String>
	): AppGallerySearchResponse

	/** `client.appDetailById` — the download url, one app at a time. */
	@FormUrlEncoded
	@POST
	suspend fun detail(
		@Url url: String,
		@FieldMap fields: Map<String, String>
	): AppGalleryDetailResponse

}
