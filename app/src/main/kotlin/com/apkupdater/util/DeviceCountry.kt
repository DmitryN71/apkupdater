package com.apkupdater.util

import android.content.Context
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale


/**
 * Where the device belongs, by three signals: the SIM card's country, the mobile network's and
 * the system language's. Two-letter codes, upper-case, empty when unknown. None of the three
 * moves with a VPN, which is the point — the address does, and Russian users mostly have one.
 */
data class CountrySignals(val sim: String, val network: String, val locale: String) {

	/** The signals that name a country at all. */
	val known: List<String> get() = listOf(sim, network, locale).filter { it.length == 2 }

	/**
	 * Russia if ANY signal says so, rather than whichever comes first: a travel eSIM or an
	 * English system language must not outvote a Russian network. The rule AppGallery's region
	 * has used since build 165; RuStore's default follows it since build 185.
	 */
	val inRussia: Boolean get() = "RU" in known
}

fun Context.countrySignals(): CountrySignals {
	// ContextCompat: the Class overload of getSystemService is API 23, and minSdk is 21.
	val telephony = runCatching { ContextCompat.getSystemService(this, TelephonyManager::class.java) }.getOrNull()
	val sim = runCatching { telephony?.simCountryIso }.getOrNull().orEmpty().uppercase()
	val network = runCatching { telephony?.networkCountryIso }.getOrNull().orEmpty().uppercase()
	val locale = Locale.getDefault().country.orEmpty().uppercase()
	return CountrySignals(sim, network, locale)
}
