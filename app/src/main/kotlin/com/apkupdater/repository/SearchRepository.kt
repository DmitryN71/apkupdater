package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.github.GitProvider
import com.apkupdater.data.github.parseRepoQuery
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.ApkPureSource
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.AptoideSource
import com.apkupdater.data.ui.FdroidSource
import com.apkupdater.data.ui.GitHubSource
import com.apkupdater.data.ui.GitLabSource
import com.apkupdater.data.ui.IzzySource
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.RuStoreSource
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class SearchRepository(
    private val apkMirrorRepository: ApkMirrorRepository,
    private val fdroidRepository: FdroidRepository,
    private val izzyRepository: FdroidRepository,
    private val aptoideRepository: AptoideRepository,
    private val gitHubRepository: GitHubRepository,
    private val apkPureRepository: ApkPureRepository,
    private val gitLabRepository: GitLabRepository,
    private val playRepository: PlayRepository,
    private val ruStoreRepository: RuStoreRepository,
    private val prefs: Prefs
) {

    /**
     * What one search found, and which sources failed to answer it. The names matter: a source
     * that could not be reached used to be dropped without a trace, so a search that hit
     * GitHub's hourly limit looked exactly like a search for something that does not exist.
     */
    data class Results(val apps: List<AppUpdate>, val failed: List<String>)

    fun search(text: String) = flow {
        val sources = mutableListOf<Pair<String, Flow<Result<List<AppUpdate>>>>>()
        // A link or owner/repo is a question about one repository, asked of the git hosts only
        // and whether or not those sources are switched on — the user named the place to look.
        // Sent to the stores as well it only made noise: Play took the URL for a package name.
        val repoQuery = parseRepoQuery(text)
        if (repoQuery.isNotEmpty()) {
            repoQuery.forEach { repo ->
                when (repo.platform) {
                    GitProvider.GITHUB -> sources.add(GitHubSource.name to gitHubRepository.lookup(repo.user, repo.repo))
                    GitProvider.GITLAB -> sources.add(GitLabSource.name to gitLabRepository.lookup(repo.user, repo.repo))
                }
            }
        } else {
            if (prefs.useApkMirror.get()) sources.add(ApkMirrorSource.name to apkMirrorRepository.search(text))
            if (prefs.useFdroid.get()) sources.add(FdroidSource.name to fdroidRepository.search(text))
            if (prefs.useIzzy.get()) sources.add(IzzySource.name to izzyRepository.search(text))
            if (prefs.useAptoide.get()) sources.add(AptoideSource.name to aptoideRepository.search(text))
            if (prefs.useGitHub.get()) sources.add(GitHubSource.name to gitHubRepository.search(text))
            if (prefs.useApkPure.get()) sources.add(ApkPureSource.name to apkPureRepository.search(text))
            if (prefs.useGitLab.get()) sources.add(GitLabSource.name to gitLabRepository.search(text))
            if (prefs.usePlay.get()) sources.add(PlaySource.name to playRepository.search(text))
            if (prefs.useRuStore.get()) sources.add(RuStoreSource.name to ruStoreRepository.search(text))
        }

        if (sources.isNotEmpty()) {
            sources.map { it.second }.combine { updates ->
                val result = updates.filter { it.isSuccess }.mapNotNull { it.getOrNull() }
                // A bare owner/repo asks GitHub and GitLab both; the one without such a
                // project answers 404, which is an empty success, never a failure.
                val failed = updates.indices
                    .filter { updates[it].isFailure }
                    .map { sources[it].first }
                    .distinct()
                // A direct lookup is an exact answer by definition, and the query — a URL —
                // would match no app name, so ranking would throw it away.
                val found = result.flatten().let { if (repoQuery.isEmpty()) it.rankByRelevance(text) else it }
                // Deduplicate before the UI ever sees the list. AppUpdate.id is a hash of
                // source+package+versionCode+version, so one source listing the same build twice
                // (ApkMirror rows per architecture, Aptoide variants) produces two items sharing
                // an id — and a LazyGrid keyed by id throws "Key ... was already used" the moment
                // BOTH become visible, which is why it crashed while scrolling rather than on
                // arrival. The Updates list already did this; Search never did.
                emit(Result.success(Results(found.distinctBy { it.id }, failed)))
            }.collect()
        } else {
            emit(Result.success(Results(emptyList(), emptyList())))
        }
    }.catch {
        emit(Result.failure(it))
        Log.e("SearchRepository", "Error searching.", it)
    }

    /**
     * Sources search very differently: F-Droid/Izzy/GitHub/GitLab filter locally with a strict
     * `contains`, while ApkMirror/Aptoide/APKPure/Play/RuStore return whatever their own fuzzy
     * server-side search decides — which is how a query like "XXX" came back with "XYYX22".
     *
     * So drop hits that match no part of the query at all, and order what is left by how well it
     * matches. Previously everything was merged and sorted alphabetically, which buried an exact
     * match in the middle of the list.
     */
    private fun List<AppUpdate>.rankByRelevance(query: String): List<AppUpdate> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return sortedBy { it.name.lowercase() }
        val words = q.split(" ").filter { it.isNotBlank() }

        // Lower is better; NO_MATCH is dropped entirely.
        fun rank(app: AppUpdate): Int {
            val name = app.name.lowercase()
            val pkg = app.packageName.lowercase()
            return when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.contains(q) -> 2
                pkg.contains(q) -> 3
                // Multi-word queries in any order, e.g. "vanced youtube" -> "YouTube Vanced".
                words.all { name.contains(it) || pkg.contains(it) } -> 4
                else -> NO_MATCH
            }
        }

        return map { it to rank(it) }
            .filter { it.second != NO_MATCH }
            .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
            .map { it.first }
    }

    companion object {
        private const val NO_MATCH = Int.MAX_VALUE
    }

}
