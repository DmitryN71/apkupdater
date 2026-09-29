package com.apkupdater.repository

import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.util.Log
import com.apkupdater.data.apkmirror.AppExistsRequest
import com.apkupdater.data.apkmirror.AppExistsResponseApk
import com.apkupdater.data.apkmirror.AppExistsResponseData
import com.apkupdater.data.apkmirror.toAppUpdate
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.getApp
import com.apkupdater.data.ui.getPackageNames
import com.apkupdater.data.ui.getSignature
import com.apkupdater.data.ui.getVersionCode
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.ApkMirrorService
import com.apkupdater.util.combine
import com.apkupdater.util.isAndroidTv
import com.apkupdater.util.orFalse
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup


class ApkMirrorRepository(
    private val service: ApkMirrorService,
    private val prefs: Prefs,
    packageManager: PackageManager
) {

    private val arch = when {
        Build.SUPPORTED_ABIS.contains("x86") -> "x86"
        Build.SUPPORTED_ABIS.contains("x86_64") -> "x86"
        Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
        Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm"
        else -> "arm"
    }

    private val isAndroidTV = packageManager.isAndroidTv()
    private val api = Build.VERSION.SDK_INT

    /**
     * The density bucket APKMirror would file this screen under (120…640): the smallest bucket
     * at or above the real density, so a 600 dpi QHD+ phone counts as 640. Only an approximation
     * of Android's own choice (a 360 dpi screen really uses 320 art), which is enough for its one
     * job: breaking a tie between variants of the same build.
     */
    private val densityBucket = Resources.getSystem().displayMetrics.densityDpi.let { dpi ->
        DPI_BUCKETS.firstOrNull { dpi <= it } ?: DPI_BUCKETS.last()
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val tally = FailureTally()
        val chunks = apps.chunked(100)
        chunks
            .map { appExists(it.getPackageNames(), tally) }
            .combine { all -> emit(parseUpdates(all.flatMap { it }, apps)) }
            .collect()
        tally.throwIfAllFailed(chunks.size, "APKMirror")
    }

    suspend fun search(text: String) = flow {
        val baseUrl = "https://www.apkmirror.com"
        val searchQuery = "/?post_type=app_release&searchtype=app&s="
        val doc = Jsoup.connect("$baseUrl$searchQuery$text").get()
        val row = doc.select("div.appRow")
        val a = row.select("a.byDeveloper")
        val h5 = row.select("h5.appRowTitle").take(a.size)
        val img = row.select("img")
        // No results is an answer, not a failure. The page always carries other rows (the
        // sidebar's latest uploads), but a search that matches nothing has no developer links
        // at all — and dropping the first of an empty list threw, so an ordinary miss such as
        // «лэтуаль» was reported as "APKMirror did not answer". Measured on 2026-09-29: 10
        // developer links for "telegram", none for «лэтуаль».
        if (a.isEmpty()) {
            emit(Result.success(emptyList()))
            return@flow
        }
        a.removeAt(0)
        if (img.isNotEmpty()) img.removeAt(0)
        val result = (0 until a.size).map {
            val releaseUrl = "$baseUrl${h5[it].selectFirst("a")?.attr("href")}"
            AppUpdate(
                name = h5[it].attr("title"),
                link = Link.Url(releaseUrl),
                iconUri = Uri.parse("$baseUrl${img[it].attr("src")}".replace("=32", "=128")),
                version = "?",
                oldVersion = "?",
                versionCode = 0L,
                oldVersionCode = 0L,
                source = ApkMirrorSource,
                // NB this is the DEVELOPER, not a package name — a search row on ApkMirror
                // carries no package id at all.
                packageName = a[it].text(),
                sourceUrl = releaseUrl,
                // Hence the default id ("ApkMirror.<developer>.0.?") is IDENTICAL for every row
                // by the same developer. That is what made the results grid throw
                // "Key ... was already used" and kill the app while scrolling a long search.
                // The release URL is the only thing unique per row.
                id = "ApkMirror.$releaseUrl".hashCode()
            )
        }
        emit(Result.success(result))
    }.catch {
        emit(Result.failure(it))
        Log.e("ApkMirrorRepository", "Error searching.", it)
    }

    private fun appExists(apps: List<String>, tally: FailureTally? = null) = flow {
        emit(service.appExists(AppExistsRequest(apps, buildIgnoreList())).data)
    }.catch {
        tally?.record(it)
        emit(emptyList())
        Log.e("ApkMirrorRepository", "Error getting updates.", it)
    }

    private fun parseUpdates(updates: List<AppExistsResponseData>, apps: List<AppInstalled>)
    = updates
        .filter { it.exists == true }
        .mapNotNull { data ->
            data.apks
                .asSequence()
                .filter { filterSignature(it, apps.getSignature(data.pname))}
                .filter { filterArch(it) }
                .filter { it.versionCode > apps.getVersionCode(data.pname) }
                .filter { filterMinApi(it) }
                .filter { filterAndroidTv(it) }
                .filter { filterWearOS(it) }
                // The newest build always wins; the screen only chooses between variants of that
                // same build. Build 169 put screen fit FIRST, and that picked older builds: Play
                // services 26.36.33 lists its Android 12+ build (…029) only as "320-480dpi", so a
                // 640 dpi phone was offered the Android 9+ build (…013) instead, and then …029 as a
                // same-version "update" right after. A "120-480dpi" page on a 640 dpi phone is
                // usually just the newest build having no 640 variant; Android uses its 480 art.
                .maxWithOrNull(compareBy<AppExistsResponseApk>({ it.versionCode }, { screenScore(it) }))
                ?.toAppUpdate(apps.getApp(data.pname)!!, data.release)
        }

    /**
     * Among variants of one versionCode: nodpi first — one plain APK for every screen, which every
     * install path can take, root included — then a build for exactly this density, then a range
     * that includes it. Exact-density variants on APKMirror are often .apkm bundles (Play services
     * lists its "480dpi" variants as bundles and its nodpi ones as APKs), and root cannot install
     * a bundle at all.
     */
    private fun screenScore(apk: AppExistsResponseApk): Int {
        val dpis = apk.dpis.orEmpty()
        val mine = densityBucket.toString()
        return when {
            "nodpi" in dpis -> 3
            dpis == listOf(mine) -> 2
            mine in dpis -> 1
            else -> 0
        }
    }

    private fun filterSignature(apk: AppExistsResponseApk, signature: String?) = when {
        apk.signaturesSha1.isNullOrEmpty() -> true
        apk.signaturesSha1.contains(signature) -> true
        else -> false
    }

    private fun filterArch(app: AppExistsResponseApk) = when {
        app.arches.isEmpty() -> true
        app.arches.contains("universal") || app.arches.contains("noarch") -> true
        app.arches.find { a -> Build.SUPPORTED_ABIS.contains(a) } != null -> true
        app.arches.find { a -> a.contains(arch) } != null -> true
        else -> false
    }

    private fun filterAndroidTv(apk: AppExistsResponseApk): Boolean {
        if (!isAndroidTV) {
            // Filter out standalone AndroidTV apps if we are not an AndroidTV device
            if(apk.capabilities?.contains("leanback_standalone").orFalse()) {
                return false
            }
        } else {
            // Filter out apps that don't have leanback if we are an AndroidTV device
            return (apk.capabilities?.contains("leanback_standalone").orFalse()
                    || apk.capabilities?.contains("leanback").orFalse())
        }
        return true
    }

    private fun filterWearOS(apk: AppExistsResponseApk): Boolean {
        // For the moment filter out all standalone Wear OS apps
        if (apk.capabilities?.contains("wear_standalone").orFalse()) {
            return false
        }
        return true
    }

    private fun filterMinApi(apk: AppExistsResponseApk) = runCatching {
        when {
            apk.minapi.toInt() > api -> false
            else -> true
        }
    }.getOrDefault(true)

    private fun buildIgnoreList() = mutableListOf<String>().apply {
        if (prefs.ignoreAlpha.get()) add("alpha")
        if (prefs.ignoreBeta.get()) add("beta")
    }

    companion object {
        /** The densities APKMirror lists variants by; 213 is tvdpi. */
        private val DPI_BUCKETS = listOf(120, 160, 213, 240, 320, 480, 640)
    }

}
