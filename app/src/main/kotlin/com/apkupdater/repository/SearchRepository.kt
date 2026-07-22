package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.ui.AppUpdate
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

    fun search(text: String) = flow {
        val sources = mutableListOf<Flow<Result<List<AppUpdate>>>>()
        if (prefs.useApkMirror.get()) sources.add(apkMirrorRepository.search(text))
        if (prefs.useFdroid.get()) sources.add(fdroidRepository.search(text))
        if (prefs.useIzzy.get()) sources.add(izzyRepository.search(text))
        if (prefs.useAptoide.get()) sources.add(aptoideRepository.search(text))
        if (prefs.useGitHub.get()) sources.add(gitHubRepository.search(text))
        if (prefs.useApkPure.get()) sources.add(apkPureRepository.search(text))
        if (prefs.useGitLab.get()) sources.add(gitLabRepository.search(text))
        if (prefs.usePlay.get()) sources.add(playRepository.search(text))
        if (prefs.useRuStore.get()) sources.add(ruStoreRepository.search(text))

        if (sources.isNotEmpty()) {
            sources.combine { updates ->
                val result = updates.filter { it.isSuccess }.mapNotNull { it.getOrNull() }
                emit(Result.success(result.flatten().rankByRelevance(text)))
            }.collect()
        } else {
            emit(Result.success(emptyList()))
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
