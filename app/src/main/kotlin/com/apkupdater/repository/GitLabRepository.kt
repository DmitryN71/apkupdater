package com.apkupdater.repository

import android.net.Uri
import android.os.Build
import android.util.Log
import com.apkupdater.data.github.GitProvider
import com.apkupdater.data.gitlab.GitLabApp
import com.apkupdater.data.gitlab.GitLabApps
import com.apkupdater.data.gitlab.GitLabRelease
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.GitLabSource
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.getApp
import com.apkupdater.prefs.Prefs
import com.apkupdater.service.GitLabService
import com.apkupdater.util.combine
import com.apkupdater.util.filterVersionTag
import com.apkupdater.util.formatIsoDate
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow


class GitLabRepository(
    private val service: GitLabService,
    private val prefs: Prefs
) {

    private fun loadAllApps(): List<GitLabApp> {
        val custom = prefs.customGitRepos.get()
            .filter { it.platform == GitProvider.GITLAB }
            .map { GitLabApp(it.installedPackageName.ifEmpty { it.packageName }, it.user, it.repo) }
        // Custom repos override hardcoded entries with same user/repo to prevent duplicates
        val customKeys = custom.map { "${it.user}/${it.repo}".lowercase() }.toSet()
        val filtered = GitLabApps.filter { "${it.user}/${it.repo}".lowercase() !in customKeys }
        return filtered + custom
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val checks = mutableListOf<Flow<List<AppUpdate>>>()
        loadAllApps().forEach { app ->
            val installedApp = apps.find { it.packageName == app.packageName }
            if (installedApp != null) {
                checks.add(checkApp(apps, app.user, app.repo, app.packageName, installedApp.version, null))
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
        if (checks.isEmpty()) {
            emit(emptyList())
        } else {
            checks.combine { all -> emit(all.flatMap { it }) }.collect()
        }
    }

    private suspend fun checkApp(
        apps: List<AppInstalled>?,
        user: String,
        repo: String,
        packageName: String,
        currentVersion: String,
        extra: Regex?
    ) = flow {
        val releases = service.getReleases(user, repo)
            .filter { Version(filterVersionTag(it.tag_name)) > Version(currentVersion) }

        if (releases.isNotEmpty()) {
            val app = apps?.getApp(packageName)
            emit(listOf(
                AppUpdate(
                name = repo,
                packageName = packageName,
                version = releases[0].tag_name,
                oldVersion = app?.version ?: "?",
                versionCode = 0L,
                oldVersionCode = app?.versionCode ?: 0L,
                source = GitLabSource,
                link = Link.Url(resolveApkUrl(releases[0], user, repo)),
                whatsNew = releases[0].description.orEmpty(),
                iconUri = if (app == null) Uri.parse(releases[0].author.avatar_url) else Uri.EMPTY,
                sourceUrl = "https://gitlab.com/$user/$repo/-/releases/${releases[0].tag_name}",
                updateDate = formatIsoDate(releases[0].released_at ?: "")
            )))
        } else {
            emit(emptyList())
        }
    }.catch {
        emit(emptyList())
        Log.e("GitLabRepository", "Error fetching releases for $packageName.", it)
    }

    suspend fun search(text: String) = flow {
        val checks = mutableListOf<Flow<List<AppUpdate>>>()

        loadAllApps().forEach { app ->
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
        emit(Result.failure(it))
        Log.e("GitLabRepository", "Error searching.", it)
    }

    /**
     * Finds the APK for a release, falling back to the release description.
     *
     * Not every project attaches the APK as a release asset: Aurora Store, for example, only
     * has the auto-generated source archives there and links the APKs from the description as
     * markdown with a project-relative `/uploads/<hash>/<file>.apk` path. Without this fallback
     * the update was still listed but carried an empty link, so Update did nothing and Download
     * failed with "no scheme was found for ".
     */
    private suspend fun resolveApkUrl(
        release: GitLabRelease,
        user: String,
        repo: String
    ): String {
        getApkUrl(release).let { if (it.isNotEmpty()) return it }

        val fromDescription = APK_LINK.findAll(release.description.orEmpty())
            .map { it.groupValues[1] }
            .toList()
        val chosen = pickApk(fromDescription)
        if (chosen.isEmpty()) return ""
        if (chosen.startsWith("http", true)) return chosen
        if (!chosen.startsWith("/uploads/")) return ""

        // GitLab refuses anonymous requests to /<user>/<repo>/uploads/... (404) and to
        // /<user>/<repo>/-/uploads/... (403 → sign-in). Only the numeric-project form serves
        // the file, hence the extra lookup — done just for this fallback, not on every check.
        val id = runCatching { service.getProject(user, repo).id }.getOrNull()
        if (id == null || id == 0L) return ""
        return "https://gitlab.com/-/project/$id$chosen"
    }

    private fun getApkUrl(release: GitLabRelease): String {
        val allApks = mutableListOf<String>()
        release.assets.sources.filter { it.url.endsWith(".apk", true) }.forEach { allApks.add(it.url) }
        release.assets.links.filter { it.url.endsWith(".apk", true) }.forEach { allApks.add(it.url) }
        return pickApk(allApks)
    }

    private fun pickApk(allApks: List<String>): String {
        if (allApks.isEmpty()) return ""
        if (allApks.size == 1) return allApks.first()

        // Try to match device architecture
        Build.SUPPORTED_ABIS.forEach { arch ->
            allApks.forEach { url -> if (url.contains(arch, true)) return url }
        }
        if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            allApks.forEach { url -> if (url.contains("arm64", true)) return url }
        }
        if (Build.SUPPORTED_ABIS.contains("x86_64")) {
            allApks.forEach { url -> if (url.contains("x64", true)) return url }
        }
        if (Build.SUPPORTED_ABIS.contains("armeabi-v7a")) {
            allApks.forEach { url -> if (url.contains("arm", true)) return url }
        }

        // Prefer "universal" if available, otherwise return first
        return allApks.find { it.contains("universal", true) } ?: allApks.first()
    }

    companion object {
        /** Markdown link target ending in .apk, e.g. `[name](/uploads/<hash>/app.apk)`. */
        private val APK_LINK = Regex("""\(([^)\s]+\.apk)\)""", RegexOption.IGNORE_CASE)
    }

}
