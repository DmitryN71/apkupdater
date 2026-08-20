package com.apkupdater.util

import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random


/**
 * A signed RuStore session, which is what unlocks its current catalogue.
 *
 * RuStore serves anonymous callers a stale snapshot: verified against the live API, an unsigned
 * request returned МАКС 26.27.1 (12 Aug 2026) while the store itself already offered 26.28.0
 * (18 Aug) — a whole version behind. Requests carrying a session signature get the same data the
 * official client sees. Since August 2026 the API also answers HTTP 419 to unsigned callers that
 * claim a current client version, so without this the source can end up showing nothing at all.
 *
 * The handshake: ask [NONCE_URL] for a nonce, then sign `nonce || certSha256` with HMAC-SHA256.
 * The key and certificate hash are constants of the RuStore client's protocol.
 *
 * Device values are the real ones from [Build] rather than a spoofed handset — RuStore uses them
 * to pick which APK variant to serve, so lying here would earn us the wrong downloads.
 */
class RuStoreSession(private val client: OkHttpClient) {

	data class Session(val deviceId: String, val signature: String)

	@Volatile
	private var cached: Session? = null

	/** Cached session, created on first use. Null when the handshake failed. */
	fun current(): Session? {
		cached?.let { return it }
		return synchronized(this) { cached ?: create().also { cached = it } }
	}

	/** Forces a new session — call after the API rejects the current one with 419. */
	fun refresh(): Session? = synchronized(this) { create().also { cached = it } }

	/**
	 * Headers the RuStore client sends. Without a session we fall back to [FALLBACK_VER_CODE]:
	 * the version check is a ceiling, so a lower value is still served (stale data, but working)
	 * instead of the 419 that [CLIENT_VER_CODE] would earn us while unsigned.
	 */
	fun headers(session: Session?): Map<String, String> = buildMap {
		put("firmwareVer", Build.VERSION.RELEASE.orEmpty())
		put("androidSdkVer", Build.VERSION.SDK_INT.toString())
		put("deviceManufacturerName", Build.MANUFACTURER.orEmpty())
		put("deviceModelName", Build.MODEL.orEmpty())
		put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
		put("firmwareLang", Locale.getDefault().language)
		put("deviceType", "mobile")
		put("User-Agent", userAgent())
		if (session != null) {
			put("deviceId", session.deviceId)
			put("X-Client-Signature", session.signature)
			put("ruStoreVerCode", CLIENT_VER_CODE)
		} else {
			put("ruStoreVerCode", FALLBACK_VER_CODE)
		}
	}

	private fun create(): Session? = runCatching {
		val deviceId = deviceId()
		val nonce = fetchNonce(deviceId) ?: return null
		Session(deviceId, sign(nonce))
	}.getOrElse {
		Log.e("RuStoreSession", "Could not create a signed session.", it)
		null
	}

	private fun fetchNonce(deviceId: String): String? {
		val builder = Request.Builder()
			.url(NONCE_URL)
			.post(ByteArray(0).toRequestBody())
		headers(null).forEach { (name, value) -> builder.header(name, value) }
		builder.header("deviceId", deviceId)
		client.newCall(builder.build()).execute().use { response ->
			if (!response.isSuccessful) {
				Log.e("RuStoreSession", "Nonce request failed: ${response.code}")
				return null
			}
			val body = response.body?.string().orEmpty()
			return JsonParser.parseString(body).asJsonObject.get("nonce")?.asString
		}
	}

	/** signature = base64(HMAC-SHA256(key, decode(nonce) || certSha256)) */
	private fun sign(nonce: String): String {
		val key = Base64.decode(HMAC_KEY, Base64.DEFAULT)
		val cert = Base64.decode(CERT_SHA256, Base64.DEFAULT)
		val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
		val signed = mac.doFinal(Base64.decode(nonce, Base64.DEFAULT) + cert)
		return Base64.encodeToString(signed, Base64.NO_WRAP)
	}

	/**
	 * `<random 8 bytes as hex>-<suffix>`, where the suffix mixes the Java string hashes of the
	 * device identity exactly as the client does (Int arithmetic wraps the same way in Kotlin,
	 * so no explicit 32-bit truncation is needed). The random half stands in for ANDROID_ID:
	 * a session needs no stable identifier, and omitting one keeps devices unlinkable.
	 */
	private fun deviceId(): String {
		val manufacturer = javaHash(Build.MANUFACTURER.orEmpty())
		val model = javaHash(Build.MODEL.orEmpty())
		val hardware = javaHash(Build.HARDWARE.orEmpty())
		val suffix = ((manufacturer * 31 + model) * 31 + hardware) * 31 + hardware
		val random = (0 until 8).joinToString("") { "%02x".format(Random.nextInt(256)) }
		return "$random-$suffix"
	}

	private fun javaHash(value: String): Int = value.fold(0) { hash, char -> 31 * hash + char.code }

	private fun userAgent(): String {
		val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
		return "RuStore/$CLIENT_VERSION_NAME (Android ${Build.VERSION.RELEASE}; " +
			"SDK ${Build.VERSION.SDK_INT}; $abi; ${Build.MANUFACTURER} ${Build.MODEL}; " +
			"${Locale.getDefault().language})"
	}

	companion object {
		private const val NONCE_URL = "https://api.rustore.ru/v1/secure/nonce"
		private const val CLIENT_VERSION_NAME = "1.105.0.2"

		/** Version the signed session presents itself as — matches [CLIENT_VERSION_NAME]. */
		const val CLIENT_VER_CODE = "1105002"

		/**
		 * Unsigned fallback. RuStore validates this header as a CEILING, not a minimum:
		 * everything below 1105002 is accepted while 1105002 and above is refused with 419.
		 * So never raise this number when requests start failing — probe downwards instead.
		 */
		const val FALLBACK_VER_CODE = "1105001"

		// Constants of the RuStore client's signing protocol.
		private const val HMAC_KEY = "K+eeiCbnVFnZ71KEVal0g5siHaX6v6drh8upeLgEPoU="
		private const val CERT_SHA256 = "Zh8ggo73gN4LebxZ8mowhkMWNV8w5Pkc+hSiB5GDmRQ="
	}
}
