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
import com.google.gson.Gson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.util.Locale


class ApkPureRepository(
    gson: Gson,
    private val service: ApkPureService,
    private val prefs: Prefs
) {

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

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val info = apps.map { AppInfoForUpdate(it.packageName, it.versionCode) }
        val r = service.getAppUpdate(header, acceptLanguage, GetAppUpdate(info))
        val updates = r.app_update_response
            .filter { filterSignature(it.sign, apps.getSignature(it.package_name)) }
            .filter { filterAlpha(it) }
            .filter { filterBeta(it) }
            .map { it.toAppUpdate(apps.getApp(it.package_name)) }
        emit(updates)
    }.catch {
        Log.e("ApkPureRepository", it.message, it)
        emit(emptyList())
    }

    suspend fun search(text: String) = flow {
        val response = service.search(header, acceptLanguage, text)
        val info = response.data.data.mapNotNull { d ->
            d.data.firstOrNull()?.takeIf { !it.ad }?.app_info?.let {
                AppInfoForUpdate(it.package_name, 0L, false)
            }
        }
        val r = service.getAppUpdate(header, acceptLanguage, GetAppUpdate(info))
        val updates = r.app_update_response
            .filter { filterAlpha(it) }
            .filter { filterBeta(it) }
            .map { it.toAppUpdate(null) }
        emit(Result.success(updates))
    }.catch {
        Log.e("ApkPureRepository", it.message, it)
        emit(Result.failure(it))
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
