package com.apkupdater.util

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.AppGalleryService
import kotlin.random.Random


/**
 * The AppGallery handshake and the parameters every call repeats.
 *
 * `client.front2` is the only way to a `sign`, which every other method demands, and it also
 * reports the `serviceZone` that decides which host to talk to. It costs ~150 KB and a second,
 * so the result is cached for a day, exactly as the AppGallery client itself does.
 *
 * No account and no Huawei ID is involved: the store answers an anonymous device.
 */
class AppGallerySession(
	private val service: AppGalleryService,
	private val prefs: Prefs,
	private val context: Context
) {

	data class Session(val host: String, val sign: String)

	@Volatile
	private var cached: Session? = null

	@Volatile
	private var cachedAt = 0L

	/** Cached session, made on first use and re-made once a day. Null when the handshake failed. */
	suspend fun current(): Session? {
		cached?.takeIf { System.currentTimeMillis() - cachedAt < MAX_AGE_MS }?.let { return it }
		return refresh()
	}

	/**
	 * Forces a new handshake — for the first use of the day, or after the sign is refused.
	 *
	 * The region is chosen by the DEVICE's country first, and by its internet address only when
	 * the country is unknown. AppGallery itself goes by the address, and behind a VPN that is the
	 * wrong answer twice over, measured on 2026-09-29 from a machine AppGallery saw as Germany:
	 * the European host found no Sberbank app at all for «сбербанк» or «Сбер», where the Russian
	 * host found СберБанк Онлайн and three more; and the European zone hands out links to the
	 * European CDN, which is the likely reason for the slow downloads reported the same morning.
	 * The Russian host answered that German address without complaint, with the Russian
	 * catalogue — so asking the right host directly works.
	 */
	suspend fun refresh(): Session? = runCatching {
		byCountry() ?: byAddress()
	}.getOrElse {
		Log.e(TAG, "AppGallery handshake failed", it)
		null
	}?.also {
		cached = it
		cachedAt = System.currentTimeMillis()
	}

	/** A handshake on the host of the device's own country, or null to fall back to the address. */
	private suspend fun byCountry(): Session? {
		val country = deviceCountry() ?: return null
		val host = hostForZone(country)
		val sign = runCatching {
			service.handshake(apiUrl(host), handshakeParams(needServiceZone = 1, host = host)).sign
		}.getOrElse {
			Log.w(TAG, "AppGallery handshake on $host for country $country failed", it)
			""
		}
		if (sign.isEmpty()) return null
		Log.i(TAG, "AppGallery session for country $country on $host (${localeFor(host)}).")
		return Session(host, sign)
	}

	/**
	 * The region AppGallery assigns by address. Only when the device's country is unknown, or its
	 * host would not answer.
	 */
	private suspend fun byAddress(): Session? {
		// The probe goes to the EU host because some host must be asked first; its answer names
		// the one this address actually belongs to. The Russian host presents an ordinary
		// GlobalSign certificate — no Russian root needed here, unlike RuStore's CDN.
		val probe = service.handshake(apiUrl(HOST_EU), handshakeParams(needServiceZone = 1, host = HOST_EU))
		val host = hostForZone(probe.serviceZone)
		// The probe's sign carries the probe's locale, and the sign decides the search language
		// (see localeFor) — so a Russian zone gets a handshake of its own, in Russian.
		val sign = if (host == HOST_RU || probe.sign.isEmpty()) {
			service.handshake(apiUrl(host), handshakeParams(needServiceZone = 0, host = host)).sign
		} else {
			probe.sign
		}
		if (sign.isEmpty()) {
			Log.w(TAG, "AppGallery handshake returned no sign (zone=${probe.serviceZone}).")
			return null
		}
		Log.i(TAG, "AppGallery session for address zone ${probe.serviceZone} on $host (${localeFor(host)}).")
		return Session(host, sign)
	}

	/**
	 * Where the device belongs, as a two-letter country code, from the SIM card, the mobile
	 * network and the system language's country. None of these moves with a VPN, which is the
	 * point.
	 *
	 * Russia wins if ANY of the three says so, rather than whichever comes first. Build 164 took
	 * the first, and Dmitry's phone still searched the European catalogue — no ЛЭТУАЛЬ for
	 * «лэтуаль», which the Russian host puts first — most likely because one signal (a travel
	 * eSIM, an English system language) named another country ahead of the Russian network. The
	 * Russian catalogue is what this source is used for: the banking and state apps that left
	 * Google Play. All three signals are logged, so the next report shows what the phone said.
	 */
	private fun deviceCountry(): String? {
		val telephony = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
		val sim = runCatching { telephony?.simCountryIso }.getOrNull().orEmpty().uppercase()
		val network = runCatching { telephony?.networkCountryIso }.getOrNull().orEmpty().uppercase()
		val locale = java.util.Locale.getDefault().country.orEmpty().uppercase()
		val signals = listOf(sim, network, locale).filter { it.length == 2 }
		val chosen = if ("RU" in signals) "RU" else signals.firstOrNull()
		Log.i(TAG, "AppGallery country: sim=$sim network=$network locale=$locale -> ${chosen ?: "unknown"}")
		return chosen
	}

	fun apiUrl(host: String) = "https://$host$API_PATH"

	/**
	 * The locale a session on [host] is opened with: Russian on the Russian host, whatever the
	 * phone's language.
	 *
	 * The store searches the names written in the language of the HANDSHAKE — the sign remembers
	 * it, and the `locale` sent with the search itself is ignored. Measured on 2026-09-29 on the
	 * Russian host, for Dmitry's phone (English system language): handshake en_US + search ru_RU
	 * answered «лэтуаль» with a cat game, Allegro and Gold Apple; handshake ru_RU put ЛЭТУАЛЬ first
	 * whatever the search said, and СберБанк Онлайн for «сбербанк» and even for "sberbank", which
	 * an en_US session misses. Build 166 set it on the search only, and changed nothing.
	 */
	fun localeFor(host: String): String = if (host == HOST_RU) "ru_RU" else locale()

	/**
	 * The parameters every call carries, sorted — see AppGalleryService on why.
	 *
	 * [deviceId] is kept between runs (see Prefs.appGalleryDeviceId), for the same reason
	 * RuStore's is since build 158: a store that hands a given id a fixed share of a staged
	 * rollout makes a fresh id per check look like updates appearing and vanishing.
	 */
	fun params(sign: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
		val all = mutableMapOf(
			"ver" to "1.1",
			"locale" to locale(),
			"serviceType" to "0",
			"ts" to System.currentTimeMillis().toString(),
			"net" to "1",
			"brand" to Build.BRAND.orEmpty().ifEmpty { "google" },
			"manufacturer" to Build.MANUFACTURER.orEmpty().ifEmpty { "Google" },
			"subBrand" to "0",
			"deviceId" to deviceId(),
			"deviceIdType" to "9"
		)
		sign?.let { all["sign"] = it }
		all.putAll(extra)
		return all.toSortedMap()
	}

	private fun handshakeParams(needServiceZone: Int, host: String) = params(
		extra = mapOf(
			"locale" to localeFor(host),
			"method" to "client.front2",
			"version" to CLIENT_VERSION,
			"versionCode" to CLIENT_VERSION_CODE,
			"packageName" to "com.huawei.appmarket",
			"zone" to "1",
			"phoneType" to Build.MODEL.orEmpty().ifEmpty { "Pixel 8 Pro" },
			"firmwareVersion" to Build.VERSION.RELEASE.orEmpty().ifEmpty { "16" },
			"isFirstLaunch" to "1",
			"oobe" to "0",
			"needServiceZone" to needServiceZone.toString()
		)
	)

	private fun deviceId(): String = prefs.appGalleryDeviceId.get().ifEmpty {
		(0 until 32).joinToString("") { "%02x".format(Random.nextInt(256)) }
			.also { prefs.appGalleryDeviceId.put(it) }
	}

	private fun locale(): String {
		val l = java.util.Locale.getDefault()
		return if (l.country.isNullOrEmpty()) l.language else "${l.language}_${l.country}"
	}

	private fun hostForZone(zone: String) = when {
		zone.equals("CN", true) -> HOST_CN
		zone.equals("RU", true) -> HOST_RU
		zone.uppercase() in DR2_ZONES -> HOST_ASIA
		else -> HOST_EU
	}

	companion object {
		private const val TAG = "AppGallerySession"
		const val API_PATH = "/hwmarket/api/clientApi"
		/** The AppGallery client we introduce ourselves as; its UA is set on the OkHttp client. */
		const val CLIENT_VERSION = "16.5.1"
		const val CLIENT_VERSION_CODE = "160501301"
		const val USER_AGENT = "HiSpace##16.5.1.301##google##Pixel 8 Pro"
		private const val HOST_CN = "store-drcn.hispace.dbankcloud.com"
		private const val HOST_RU = "store-drru.hispace.dbankcloud.ru"
		private const val HOST_ASIA = "store-dra.hispace.dbankcloud.com"
		private const val HOST_EU = "store-dre.hispace.dbankcloud.com"
		private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
		/** Asia, Africa and Latin America share one host; CN and RU have their own. */
		private val DR2_ZONES = (
			"AE AF AG AI AM AO AQ AR AS AW AZ BB BD BF BH BI BJ BL BM BN BO BR BS BT BV BW BY BZ " +
				"CC CD CF CG CI CK CL CM CO CR CU CV CX DJ DM DO DZ EC EG EH ER ET FJ FK FM GA GD " +
				"GE GF GH GM GN GP GQ GS GT GU GW GY HK HM HN HT ID IN IO IQ JM JO JP KE KG KH KI " +
				"KM KN KP KR KW KY KZ LA LB LC LK LR LS LY MA MG MH ML MM MN MO MP MQ MR MS MU MV " +
				"MW MX MY MZ NA NC NE NF NG NI NP NR NU OM PA PE PF PG PH PK PN PR PS PW PY QA RE " +
				"RW SA SB SC SD SG SH SL SN SO SR SS ST SV SY SZ TC TD TF TG TH TJ TK TL TM TN TO " +
				"TT TV TW TZ UG UY UZ VE VG VI VN VU WF WS YE YT ZA ZM ZW"
			).split(" ").toSet()
	}

}
