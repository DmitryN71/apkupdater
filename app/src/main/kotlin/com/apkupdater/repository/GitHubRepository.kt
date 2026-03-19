package com.apkupdater.repository

import android.net.Uri
import android.os.Build
import android.util.Log
import com.apkupdater.BuildConfig
import com.apkupdater.data.github.GitHubApp
import com.apkupdater.data.github.GitHubApps
import com.apkupdater.data.snack.TextSnack
import com.apkupdater.data.github.GitHubRelease
import com.apkupdater.data.github.GitHubReleaseAsset
import com.apkupdater.data.github.GitProvider
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.GitHubSource
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.getApp
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.GitHubService
import com.apkupdater.util.SnackBar
import com.apkupdater.util.combine
import com.apkupdater.util.filterVersionTag
import retrofit2.HttpException
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.util.Scanner


class GitHubRepository(
    private val service: GitHubService,
    private val prefs: Prefs,
    private val snackBar: SnackBar
) {

    private var rateLimitWarned = false

    private fun loadAllApps(): List<GitHubApp> {
        val custom = prefs.customGitRepos.get()
            .filter { it.platform == GitProvider.GITHUB }
            .map { GitHubApp(it.installedPackageName.ifEmpty { it.packageName }, it.user, it.repo) }
        // Custom repos override hardcoded entries with same user/repo to prevent duplicates
        val customKeys = custom.map { "${it.user}/${it.repo}".lowercase() }.toSet()
        val filtered = GitHubApps.filter { "${it.user}/${it.repo}".lowercase() !in customKeys }
        return filtered + custom
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val checks = mutableListOf(selfCheck())
        val allApps = loadAllApps()

        allApps.forEach { app ->
            if (app.packageName != BuildConfig.APPLICATION_ID) {
                val installedApp = apps.find { it.packageName == app.packageName }
                if (installedApp != null) {
                    checks.add(checkApp(apps, app.user, app.repo, app.packageName, installedApp.version, app.extra))
                } else if (app.packageName.contains("/")) {
                    // Custom repo — try fuzzy name match against installed apps
                    val fuzzyMatch = apps.find { installed ->
                        installed.name.equals(app.repo, ignoreCase = true) ||
                        installed.name.replace(" ", "").equals(app.repo, ignoreCase = true) ||
                        app.repo.contains(installed.name, ignoreCase = true) ||
                        installed.name.contains(app.repo, ignoreCase = true)
                    }
                    if (fuzzyMatch != null) {
                        checks.add(checkApp(apps, app.user, app.repo, fuzzyMatch.packageName, fuzzyMatch.version, null))
                    }
                    // If no match found, skip — user needs to link the repo to an installed app in Settings
                }
            }
        }

        checks.combine { all ->
            emit(all.flatMap { it })
        }.collect()
    }.catch {
        handleRateLimit(it)
        emit(emptyList())
        Log.e("GitHubRepository", "Error fetching releases.", it)
    }

    suspend fun search(text: String) = flow {
        val checks = mutableListOf<Flow<List<AppUpdate>>>()
        val allApps = loadAllApps()

        allApps.forEach { app ->
            if (app.repo.contains(text, true) || app.user.contains(text, true) || app.packageName.contains(text, true)) {
                checks.add(checkApp(null, app.user, app.repo, app.packageName, "?", null))
            }
        }

        if (checks.isEmpty()) {
            emit(Result.success(emptyList()))
        } else {
            checks.combine { all ->
                val r = all.flatMap { it }
                emit(Result.success(r))
            }.collect()
        }
    }.catch {
        handleRateLimit(it)
        emit(Result.failure(it))
        Log.e("GitHubRepository", "Error searching.", it)
    }

    private fun selfCheck() = flow {
        val releases = service.getReleases().filter { filterPreRelease(it) }
        val versions = getVersions(releases[0].name)

        if (versions.second > BuildConfig.VERSION_CODE.toLong()) {
            emit(listOf(AppUpdate(
                name = "APKUpdater",
                packageName = BuildConfig.APPLICATION_ID,
                version = versions.first,
                oldVersion = BuildConfig.VERSION_NAME,
                versionCode = versions.second,
                oldVersionCode = BuildConfig.VERSION_CODE.toLong(),
                source = GitHubSource,
                link = Link.Url(releases[0].assets[0].browser_download_url),
                whatsNew = releases[0].body.orEmpty(),
                sourceUrl = "https://github.com/rumboalla/apkupdater/releases/tag/${releases[0].tag_name}"
            )))
        } else {
            // We need to emit empty so it can be combined later
            emit(listOf())
        }
    }.catch {
        emit(emptyList())
        Log.e("GitHubRepository", "Error checking self-update.", it)
    }

    private fun checkApp(
        apps: List<AppInstalled>?,
        user: String,
        repo: String,
        packageName: String,
        currentVersion: String,
        extra: Regex?
    ) = flow {
        val r = service.getReleases(user, repo)
        val releases = if (packageName == "com.apkupdater.ci") {
            // TODO: Find a better way to do this
            r.filter { it.name.contains("CI-Release-3.x")}
        } else {
            r.filter { filterPreRelease(it) }.filter { findApkAsset(it.assets).isNotEmpty() }
        }

        if (releases.isNotEmpty() && Version(filterVersionTag(releases[0].tag_name)) > Version(currentVersion)) {
            val app = apps?.getApp(packageName)
            emit(listOf(AppUpdate(
                name = repo,
                packageName = packageName,
                version = releases[0].tag_name,
                oldVersion = app?.version ?: "?",
                versionCode = 0L,
                oldVersionCode = app?.versionCode ?: 0L,
                source = GitHubSource,
                link = findApkAssetArch(releases[0].assets, extra).let { Link.Url(it.browser_download_url, it.size) },
                whatsNew = releases[0].body.orEmpty(),
                iconUri = if (app == null) Uri.parse(releases[0].author.avatar_url) else Uri.EMPTY,
                sourceUrl = "https://github.com/$user/$repo/releases/tag/${releases[0].tag_name}"
            )))
        } else {
            emit(emptyList())
        }
    }.catch {
        handleRateLimit(it)
        emit(emptyList())
        Log.e("GitHubRepository", "Error fetching releases for $packageName.", it)
    }

    private fun getVersions(name: String) = runCatching {
        val scanner = Scanner(name)
        val version = scanner.next()
        val versionCode = scanner.next().trim('(', ')').toLong()
        Pair(version, versionCode)
    }.getOrDefault(Pair(name, 0L))

    private fun filterPreRelease(release: GitHubRelease) = when {
        prefs.ignorePreRelease.get() && release.prerelease -> false
        else -> true
    }

    private fun findApkAsset(assets: List<GitHubReleaseAsset>) = assets
        .filter { it.browser_download_url.endsWith(".apk", true) }
        .maxByOrNull { it.size }
        ?.browser_download_url
        .orEmpty()

    private fun findApkAssetArch(
        assets: List<GitHubReleaseAsset>,
        extra: Regex?
    ): GitHubReleaseAsset {
        val apks = assets
            .filter { it.browser_download_url.endsWith(".apk", true) }
            .filter { filterExtra(it, extra) }

        when {
            apks.isEmpty() -> return GitHubReleaseAsset(0L, "")
            apks.size == 1 -> return apks.first()
            else -> {
                // Try to match exact arch
                Build.SUPPORTED_ABIS.forEach { arch ->
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains(arch, true)) {
                            return apk
                        }
                    }
                }
                // Try to match arm64
                if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("arm64", true)) {
                            return apk
                        }
                    }
                }
                // Try to match x64
                if (Build.SUPPORTED_ABIS.contains("x86_64")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("x64", true)) {
                            return apk
                        }
                    }
                }
                // Try to match arm
                if (Build.SUPPORTED_ABIS.contains("armeabi-v7a")) {
                    apks.forEach { apk ->
                        if (apk.browser_download_url.contains("arm", true)) {
                            return apk
                        }
                    }
                }
                // If no match, return biggest apk in the hope it's universal
                return apks.maxByOrNull { it.size } ?: GitHubReleaseAsset(0L, "")
            }
        }
    }

    private fun filterExtra(asset: GitHubReleaseAsset, extra: Regex?) = when(extra) {
        null -> true
        else -> asset.browser_download_url.matches(extra)
    }

    private fun handleRateLimit(t: Throwable) {
        if (!rateLimitWarned && t is HttpException && t.code() == 403) {
            val remaining = t.response()?.headers()?.get("X-RateLimit-Remaining")
            if (remaining == "0") {
                rateLimitWarned = true
                snackBar.snackBar(message = TextSnack("GitHub API rate limit reached. Try again later."))
            }
        }
    }

}
