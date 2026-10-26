package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.apkpure.AppInfoForUpdate
import com.apkupdater.data.apkpure.AppUpdateResponse
import com.apkupdater.data.apkpure.DeviceHeader
import com.apkupdater.data.apkpure.GetAppUpdate
import com.apkupdater.data.apkpure.toAppUpdate
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.getApp
import com.apkupdater.data.ui.getSignature
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.ApkPureService
import com.apkupdater.util.AppJournal
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import retrofit2.HttpException
import java.util.Locale


class ApkPureRepository(
    gson: Gson,
    private val service: ApkPureService,
    private val prefs: Prefs
) {

    private companion object {
        /** What APKPure answers for any request its framework refuses — see [retryFrequencyLimit]. */
        const val REFUSED = 555
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L)
    }

    private val header = gson.toJson(DeviceHeader())

    /**
     * An attempt, not a fix. Asked for on 4PDA by CJ Flash, who gets APKPure updates in the
     * wrong language.
     *
     * We have never told APKPure anything about language: the device header we send carries
     * abis, android id, OS version, platform, screen width and height, and nothing else — the
     * whole thing was read off the APKPure app itself, and one of those seven fields is still
     * marked "figure out what this is". Their API is private and undocumented; the only Python
     * client on PyPI scrapes the website with BeautifulSoup and never touches this endpoint, so
     * there is nothing to copy from.
     *
     * So this is the standard header rather than a guessed field inside device_info: a server
     * that does not read it ignores it, whereas an invented JSON key could invalidate the whole
     * request. If APKPure honours it, the problem is solved; if not, nothing is worse. We cannot
     * tell which from here — only somebody currently getting the wrong language can.
     */
    private val acceptLanguage = Locale.getDefault().let { locale ->
        val language = locale.language
        if (language.isBlank()) "en" else "${locale.toLanguageTag()},$language;q=0.9,en;q=0.8"
    }

    /**
     * The parameter this API actually reads. Found the day after the header above shipped, in
     * three independent clients of the same endpoints: Obtainium sends `hl=en` to
     * tapi.pureapk.com with our very headers, and both apkeep (EFF) and a widely copied gist
     * send `hl=en-US` to api.pureapk.com. All three hard-code English; we send the device's
     * language, region form included, since the region form is what two of them use.
     */
    private val hl = Locale.getDefault().let { locale ->
        if (locale.language.isBlank()) "en" else locale.toLanguageTag()
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        // APKPure's server reads version_code as a 32-bit int, and ONE app above 2 147 483 647 —
        // a long versionCode, which Android allows since 9 — fails the whole request: HTTP 555,
        // "cannot unmarshal number 2147483648 into Go value of type int32" (measured 2026-10-26).
        // Dmitry's car head unit has such a system app, so APKPure failed every check the moment
        // he let system apps in. APKPure cannot describe those versions anyway; left out.
        val (fits, tooLong) = apps.partition { it.versionCode <= Int.MAX_VALUE }
        if (tooLong.isNotEmpty()) {
            AppJournal.add("ApkPureRepository", "APKPure: ${tooLong.size} app(s) left out, versionCode too long for its API: " +
                tooLong.take(3).joinToString { "${it.packageName} ${it.versionCode}" })
        }
        val info = fits.map { AppInfoForUpdate(it.packageName, it.versionCode) }
        val r = retryFrequencyLimit { service.getAppUpdate(header, acceptLanguage, hl, GetAppUpdate(info)) }
        val updates = r.app_update_response
            .filter { filterSignature(it.sign, apps.getSignature(it.package_name)) }
            .filter { filterAlpha(it) }
            .filter { filterBeta(it) }
            .map { it.toAppUpdate(apps.getApp(it.package_name)) }
        emit(updates)
    }.catch {
        Log.e("ApkPureRepository", it.message, it)
        // Rethrown, not swallowed into an empty list: UpdatesRepository then counts this source as
        // failed and keeps its earlier cards. An empty answer here read as "no updates" — with
        // no network at all, a check of this one source said so on the screen.
        throw it
    }

    suspend fun search(text: String) = flow {
        val response = retryFrequencyLimit { service.search(header, acceptLanguage, hl, text) }
        val info = response.data.data.mapNotNull { d ->
            d.data.firstOrNull()?.takeIf { !it.ad }?.app_info?.let {
                AppInfoForUpdate(it.package_name, 0L, false)
            }
        }
        val r = retryFrequencyLimit { service.getAppUpdate(header, acceptLanguage, hl, GetAppUpdate(info)) }
        val updates = r.app_update_response
            .filter { filterAlpha(it) }
            .filter { filterBeta(it) }
            .map { it.toAppUpdate(null) }
        emit(Result.success(updates))
    }.catch {
        Log.e("ApkPureRepository", it.message, it)
        emit(Result.failure(it))
    }

    /**
     * [call], asked again after a pause when APKPure refuses it for its frequency limit (build
     * 187). The answer is HTTP 555 with an empty body, and its `trpc-error-msg` header says "reach
     * interface frequecy limit". Measured from one address on 2026-10-23: about one long list in
     * seven or eight was refused that way, small ones never, and the same request a second or two
     * later passed — while the source had shown "Could not check: APKPure" for the whole check.
     *
     * 555 is not only that, though: it is what APKPure's framework answers for anything it
     * refuses, a request it cannot read included ("cannot unmarshal…"). Those fail the same way
     * every time, so they are journaled with their reason and not asked again (build 188).
     */
    private suspend fun <T> retryFrequencyLimit(call: suspend () -> T): T {
        RETRY_DELAYS_MS.forEach { pause ->
            try {
                return call()
            } catch (e: HttpException) {
                if (e.code() != REFUSED) throw e
                val reason = e.response()?.headers()?.get("trpc-error-msg").orEmpty()
                // Their spelling, "frequecy"; matched loosely in case they fix it.
                if (reason.isNotEmpty() && !reason.contains("frequ", true)) {
                    AppJournal.add("ApkPureRepository", "APKPure refused the request (HTTP 555): $reason")
                    throw e
                }
                AppJournal.add("ApkPureRepository", "APKPure: frequency limit (HTTP 555), asking again in ${pause / 1000}s")
                delay(pause)
            }
        }
        return call()
    }

    private fun filterAlpha(update: AppUpdateResponse) = when {
        prefs.ignoreAlpha.get() && update.version_name.contains("alpha", true) -> false
        else -> true
    }

    private fun filterBeta(update: AppUpdateResponse) = when {
        prefs.ignoreBeta.get() && update.version_name.contains("beta", true) -> false
        else -> true
    }

    private fun filterSignature(signatures: List<String>, signature: String) = when {
        signatures.isEmpty() -> true
        signatures.contains(signature) -> true
        else -> false
    }

}
