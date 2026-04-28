package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.combine
import com.apkupdater.util.isVersionDowngrade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger


private const val SOURCE_TIMEOUT_MS = 90_000L // 90 seconds per source

class UpdatesRepository(
    private val appsRepository: AppsRepository,
    private val apkMirrorRepository: ApkMirrorRepository,
    private val gitHubRepository: GitHubRepository,
    private val fdroidRepository: FdroidRepository,
    private val izzyRepository: FdroidRepository,
    private val aptoideRepository: AptoideRepository,
    private val apkPureRepository: ApkPureRepository,
    private val gitLabRepository: GitLabRepository,
    private val playRepository: PlayRepository,
    private val ruStoreRepository: RuStoreRepository,
    private val prefs: Prefs
) {

    fun updates(
        onSourceError: ((Int, Int) -> Unit)? = null,
        onSourceComplete: ((Int, Int, List<String>) -> Unit)? = null
    ) = flow<List<AppUpdate>> {
        appsRepository.getApps().collect { result ->
            result.onSuccess { apps ->
                val filtered = apps.filter { !it.ignored }
                val sourceNames = mutableListOf<String>()
                val sourceFlows = mutableListOf<Flow<List<AppUpdate>>>()
                if (prefs.useApkMirror.get()) { sourceNames.add("ApkMirror"); sourceFlows.add(apkMirrorRepository.updates(filtered)) }
                if (prefs.useGitHub.get()) { sourceNames.add("GitHub"); sourceFlows.add(gitHubRepository.updates(filtered)) }
                if (prefs.useFdroid.get()) { sourceNames.add("F-Droid"); sourceFlows.add(fdroidRepository.updates(filtered)) }
                if (prefs.useIzzy.get()) { sourceNames.add("Izzy"); sourceFlows.add(izzyRepository.updates(filtered)) }
                if (prefs.useAptoide.get()) { sourceNames.add("Aptoide"); sourceFlows.add(aptoideRepository.updates(filtered)) }
                if (prefs.useApkPure.get()) { sourceNames.add("APKPure"); sourceFlows.add(apkPureRepository.updates(filtered)) }
                if (prefs.useGitLab.get()) { sourceNames.add("GitLab"); sourceFlows.add(gitLabRepository.updates(filtered)) }
                if (prefs.usePlay.get()) { sourceNames.add("Play"); sourceFlows.add(playRepository.updates(filtered)) }
                if (prefs.useRuStore.get()) { sourceNames.add("RuStore"); sourceFlows.add(ruStoreRepository.updates(filtered)) }

                val totalSources = sourceFlows.size
                if (totalSources > 0) {
                    val errorCount = AtomicInteger(0)
                    val completedCount = AtomicInteger(0)
                    val remaining = sourceNames.toMutableList()
                    val lock = Any()
                    onSourceComplete?.invoke(0, totalSources, remaining.toList())
                    val wrappedSources = sourceFlows.mapIndexed { index, source ->
                        flow {
                            val result = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                val items = mutableListOf<AppUpdate>()
                                source.collect { items.addAll(it) }
                                items
                            }
                            if (result != null) {
                                emit(result)
                            } else {
                                Log.w("UpdatesRepository", "${sourceNames[index]} timed out after ${SOURCE_TIMEOUT_MS / 1000}s")
                                errorCount.incrementAndGet()
                                emit(emptyList())
                            }
                        }.catch { e ->
                            Log.e("UpdatesRepository", "Source error", e)
                            errorCount.incrementAndGet()
                            emit(emptyList())
                        }.onCompletion {
                            val completed: Int
                            val left: List<String>
                            synchronized(lock) {
                                remaining.remove(sourceNames[index])
                                completed = completedCount.incrementAndGet()
                                left = remaining.toList()
                            }
                            onSourceComplete?.invoke(completed, totalSources, left)
                        }
                    }
                    wrappedSources
                        .combine { updates ->
                            // No deduplication — show every source's update so the user
                            // can choose where to install from (e.g. avoid ApkMirror in
                            // favor of GitHub/F-Droid for direct install).
                            emit(updates.flatMap { it }.filter { !isVersionDowngrade(it.oldVersion, it.version) })
                        }
                        .collect()
                    val errors = errorCount.get()
                    if (errors > 0) onSourceError?.invoke(errors, totalSources)
                } else {
                    emit(emptyList())
                }
            }.onFailure {
                Log.e("UpdatesRepository", "Error getting apps", it)
            }
        }
    }.catch {
        Log.e("UpdatesRepository", "Error getting updates", it)
    }

}
