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
import com.apkupdater.R
import com.apkupdater.service.GitHubService
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.apkupdater.util.combine
import com.apkupdater.util.filterVersionTag
import com.apkupdater.util.formatIsoDate
import retrofit2.HttpException
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.util.Scanner
import java.util.concurrent.atomic.AtomicBoolean


class GitHubRepository(
    private val service: GitHubService,
    private val prefs: Prefs,
    private val snackBar: SnackBar,
    private val stringer: Stringer
) {

    companion object {
        /** Where this app's own releases live; the service's defaults say the same. */
        private const val SELF_USER = "DmitryN71"
        private const val SELF_REPO = "apkupdater"
    }

    // Prevents spamming the same GitHub error snackbar within a single refresh.
    // Reset at the start of every updates()/search() so a fresh refresh warns again.
    // Atomic: the per-repository checks run in parallel on IO threads, and with a plain field
    // several of them passed the test before any one of them set it — so running out of the
    // hourly limit queued a row of identical snackbars.
    private val errorWarned = AtomicBoolean(false)

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
        errorWarned.set(false)
        val tally = FailureTally()
        val checks = mutableListOf(selfCheck(tally))
        val allApps = loadAllApps()

        allApps.forEach { app ->
            if (app.packageName != BuildConfig.APPLICATION_ID) {
                val installedApp = apps.find { it.packageName == app.packageName }
                if (installedApp != null) {
                    checks.add(checkApp(apps, app.user, app.repo, app.packageName, installedApp.version, app.extra, tally))
                } else if (app.packageName.contains("/")) {
                    // Custom repo — try fuzzy name match against installed apps
                    // The substring tests need a name of some length: every string contains "",
                    // so an app with an empty label matched every unlinked repository, and a
                    // two-letter one matched far too many.
                    val fuzzyMatch = apps.find { installed ->
                        val name = installed.name.trim()
                        name.isNotEmpty() && (
                            name.equals(app.repo, ignoreCase = true) ||
                            name.replace(" ", "").equals(app.repo, ignoreCase = true) ||
                            (name.length >= 3 && (
                                app.repo.contains(name, ignoreCase = true) ||
                                name.contains(app.repo, ignoreCase = true)
                            ))
                        )
                    }
                    if (fuzzyMatch != null) {
                        checks.add(checkApp(apps, app.user, app.repo, fuzzyMatch.packageName, fuzzyMatch.version, null, tally))
                    }
                    // If no match found, skip — user needs to link the repo to an installed app in Settings
                }
            }
        }

        checks.combine { all ->
            emit(all.flatMap { it })
        }.collect()
        tally.throwIfAllFailed(checks.size, "GitHub")
    }.catch {
        handleGitHubError(it)
        Log.e("GitHubRepository", "Error fetching releases.", it)
        // Rethrown, not swallowed into an empty list: UpdatesRepository then counts this source as
        // failed and keeps its earlier cards. An empty answer here read as "no updates" — with
        // no network at all, a check of this one source said so on the screen.
        throw it
    }

    suspend fun search(text: String) = flow {
        errorWarned.set(false)
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
        handleGitHubError(it)
        emit(Result.failure(it))
        Log.e("GitHubRepository", "Error searching.", it)
    }

    private fun selfCheck(tally: FailureTally? = null) = flow {
        // The same two hazards as any other repository — see releaseCandidates — and the stakes
        // are higher here than anywhere else: a release the list happens to omit means nobody
        // hears about this app's own updates, and nobody could report that to us either. One
        // extra call per check, out of the sixty an hour an unauthenticated address gets, is
        // cheap insurance for that.
        val listed = service.getReleases().filter { filterPreRelease(it) }
        val latest = latestRelease(SELF_USER, SELF_REPO)
        val releases = (listed + listOfNotNull(latest))
            .distinctBy { it.tag_name }
            // assets[0] below would throw on a release published before its APK was uploaded.
            .filter { it.assets.isNotEmpty() }
        // Keyed on the build number inside the release title — "3.8.0 (154)" — which is the
        // very number the comparison below makes, rather than on the order GitHub returned.
        val newest = releases.maxByOrNull { getVersions(it.name).second }
        if (newest == null) {
            emit(emptyList())
            return@flow
        }
        val versions = getVersions(newest.name)

        if (versions.second > BuildConfig.VERSION_CODE.toLong()) {
            emit(listOf(AppUpdate(
                name = "APKUpdater",
                packageName = BuildConfig.APPLICATION_ID,
                version = versions.first,
                oldVersion = BuildConfig.VERSION_NAME,
                versionCode = versions.second,
                oldVersionCode = BuildConfig.VERSION_CODE.toLong(),
                source = GitHubSource,
                link = Link.Url(newest.assets[0].browser_download_url, newest.assets[0].size),
                whatsNew = newest.body.orEmpty(),
                // This fork's own releases, not the parent project's — the link pointed at
                // rumboalla, where the tag being named does not exist.
                sourceUrl = "https://github.com/$SELF_USER/$SELF_REPO/releases/tag/${newest.tag_name}"
            )))
        } else {
            // We need to emit empty so it can be combined later
            emit(listOf())
        }
    }.catch {
        tally?.record(it)
        emit(emptyList())
        Log.e("GitHubRepository", "Error checking self-update.", it)
    }

    private fun checkApp(
        apps: List<AppInstalled>?,
        user: String,
        repo: String,
        packageName: String,
        currentVersion: String,
        extra: Regex?,
        tally: FailureTally? = null
    ) = flow {
        val releases = releaseCandidates(user, repo, packageName, extra)
        // The HIGHEST version among the candidates, never the first one GitHub happened to
        // return. The list endpoint is ordered by created_at, and GitHub's own documentation
        // says that is "the date of the commit used for the release, and not the date when the
        // release was drafted or published" — so a release cut from an older commit, or drafted
        // weeks before it was published, sorts into the middle of the list. Taking [0] was
        // trusting an order that was never promised.
        val newest = releases.maxByOrNull { Version(filterVersionTag(it.tag_name)) }

        // One line per repository, so that `Copy App Logs` answers "why does it still show the
        // old version?" outright. Without it, 154 could only be diagnosed by reading the source.
        Log.i(
            "GitHubRepository",
            "$user/$repo: ${releases.size} candidate(s), newest=${newest?.tag_name ?: "none"}, " +
                "installed=$currentVersion, preReleases=${!prefs.ignorePreRelease.get()}"
        )

        // Both sides through filterVersionTag. The installed versionName went in raw, and the
        // version library reads a string that does not start with a digit as no version at all,
        // so an app whose versionName is "v1.4.2" lost to the tag "v1.4.2" and was offered its
        // own version for ever.
        if (newest != null &&
            Version(filterVersionTag(newest.tag_name)) > Version(filterVersionTag(currentVersion))
        ) {
            val app = apps?.getApp(packageName)
            emit(listOf(AppUpdate(
                name = repo,
                packageName = packageName,
                version = newest.tag_name,
                oldVersion = app?.version ?: "?",
                versionCode = 0L,
                oldVersionCode = app?.versionCode ?: 0L,
                source = GitHubSource,
                link = findApkAssetArch(newest.assets, extra).let { Link.Url(it.browser_download_url, it.size) },
                whatsNew = newest.body.orEmpty(),
                iconUri = if (app == null) Uri.parse(newest.author.avatar_url) else Uri.EMPTY,
                sourceUrl = "https://github.com/$user/$repo/releases/tag/${newest.tag_name}",
                updateDate = formatIsoDate(newest.published_at ?: ""),
                // The one source that can say so outright. Reaching the list at all means the
                // user turned ignorePreRelease off, so the card says what they let through.
                isPreRelease = newest.prerelease
            )))
        } else {
            emit(emptyList())
        }
    }.catch {
        handleGitHubError(it)
        // A 404 is GitHub answering that this repository is gone — an answer, not a failure to
        // reach GitHub. Anything else counts towards "GitHub could not be reached at all".
        if (!(it is HttpException && it.code() == 404)) tally?.record(it)
        emit(emptyList())
        Log.e("GitHubRepository", "Error fetching releases for $packageName.", it)
    }

    private fun getVersions(name: String) = runCatching {
        val scanner = Scanner(name)
        val version = scanner.next()
        val versionCode = scanner.next().trim('(', ')').toLong()
        Pair(version, versionCode)
    }.getOrDefault(Pair(name, 0L))

    /**
     * The releases worth considering for one repository. Two endpoints, because neither one is
     * complete on its own:
     *
     * - `/releases` can OMIT a published release. `anilbeesetti/nextplayer` v0.18.0 was published
     *   on 2026-09-14 with five APKs and was still absent from all 43 entries that list returned
     *   a day later, while `/releases/latest` answered v0.18.0 at once. Verified against the live
     *   API on 2026-09-15.
     * - `/releases/latest` is defined as the newest non-draft, non-prerelease release, so it can
     *   never show a pre-release and it answers 404 for a repository that has nothing else.
     *
     * Cost matters here: an address without a personal token gets 60 GitHub requests an hour,
     * and this runs once per catalogued app the user has installed.
     *
     * - Pre-releases ignored (the default): ONE call, `/releases/latest`, which is exactly the
     *   question being asked. The list is fetched only in the rare case where that release
     *   carries no APK, e.g. the author published the notes before uploading the files.
     * - Pre-releases wanted: TWO calls, merged. This is precisely what build 154 got wrong — the
     *   whole `/releases/latest` lookup sat behind `if (ignorePreRelease)`, so anyone who had
     *   turned pre-releases ON stayed on the old list-only path and went on missing v0.18.0.
     *   Such a user needs both: the list is the only place pre-releases appear, and
     *   `/releases/latest` is the only place a stable release the list omits appears. A token in
     *   Settings raises the budget to 5 000 an hour for anyone who feels the extra call.
     */
    private suspend fun releaseCandidates(
        user: String,
        repo: String,
        packageName: String,
        extra: Regex?
    ): List<GitHubRelease> {
        if (packageName == "com.apkupdater.ci") {
            // TODO: Find a better way to do this
            return service.getReleases(user, repo).filter { it.name.contains("CI-Release-3.x") }
        }

        val latest = latestRelease(user, repo)
        if (prefs.ignorePreRelease.get() && latest != null && hasApkFor(latest.assets, extra)) {
            return listOf(latest)
        }

        // `latest` is by definition never a pre-release, so filterPreRelease keeps it either way.
        return (service.getReleases(user, repo) + listOfNotNull(latest))
            .distinctBy { it.tag_name }
            .filter { filterPreRelease(it) }
            .filter { hasApkFor(it.assets, extra) }
    }

    /**
     * `/releases/latest`, with its failures told apart instead of swallowed alike.
     *
     * 404 is an ordinary answer — this repository has no release that qualifies — and must not
     * raise the snackbar that a 401 or a rate limit does. Everything else is logged, so that
     * `Copy App Logs` can settle the next "it does not see the new version" report instead of
     * leaving us to guess the way build 154 did.
     */
    private suspend fun latestRelease(user: String, repo: String): GitHubRelease? = try {
        service.getLatestRelease(user, repo)
    } catch (c: CancellationException) {
        // Never swallow this one: it is how the stop-check button ends a running refresh.
        throw c
    } catch (t: Throwable) {
        if (t is HttpException && t.code() == 404) {
            Log.i("GitHubRepository", "No published release for $user/$repo.")
        } else {
            Log.w("GitHubRepository", "Latest-release lookup failed for $user/$repo.", t)
            handleGitHubError(t)
        }
        null
    }

    private fun filterPreRelease(release: GitHubRelease) = when {
        prefs.ignorePreRelease.get() && release.prerelease -> false
        else -> true
    }

    /**
     * Whether this release has the APK [findApkAssetArch] would hand out — the same test, not a
     * looser one. It used to accept any .apk while the card then picked only among those matching
     * the catalogue's [extra] pattern, so a newest release without the wanted variant (say, no
     * "freenet" build) became a card with an empty download link, and an older release that did
     * have it was never considered.
     */
    private fun hasApkFor(assets: List<GitHubReleaseAsset>, extra: Regex?) =
        findApkAssetArch(assets, extra).browser_download_url.isNotEmpty()

    private fun findApkAssetArch(
        assets: List<GitHubReleaseAsset>,
        extra: Regex?
    ): GitHubReleaseAsset {
        val allApks = assets
            .filter { it.browser_download_url.endsWith(".apk", true) }
            .filter { filterExtra(it, extra) }

        // Prefer non-fdroid variants — F-Droid builds use different signing keys
        // which would cause "install copy" instead of "update" on regular-signed installs.
        // Check filename only (not full URL) to avoid false matches on repo paths like fdroid/fdroidclient.
        val nonFdroid = allApks.filter {
            !it.browser_download_url.substringAfterLast('/').contains("fdroid", true)
        }
        val apks = if (nonFdroid.isNotEmpty()) nonFdroid else allApks

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

    private fun handleGitHubError(t: Throwable) {
        if (t !is HttpException) return
        val message = when (t.code()) {
            // Expired/revoked/invalid Personal Access Token → "Bad credentials"
            401 -> stringer.get(R.string.github_token_invalid)
            // Rate limit (authenticated or anonymous) or forbidden token scope
            403, 429 -> stringer.get(R.string.github_rate_limit)
            else -> return
        }
        if (!errorWarned.compareAndSet(false, true)) return
        snackBar.snackBar(message = TextSnack(message))
    }

}
