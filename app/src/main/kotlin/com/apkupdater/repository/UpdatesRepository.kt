package com.apkupdater.repository

import android.util.Log
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger


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

    fun updates(onSourceError: ((Int, Int) -> Unit)? = null) = flow<List<AppUpdate>> {
        appsRepository.getApps().collect { result ->
            result.onSuccess { apps ->
                val filtered = apps.filter { !it.ignored }
                val sources = mutableListOf<Flow<List<AppUpdate>>>()
                if (prefs.useApkMirror.get()) sources.add(apkMirrorRepository.updates(filtered))
                if (prefs.useGitHub.get()) sources.add(gitHubRepository.updates(filtered))
                if (prefs.useFdroid.get()) sources.add(fdroidRepository.updates(filtered))
                if (prefs.useIzzy.get()) sources.add(izzyRepository.updates(filtered))
                if (prefs.useAptoide.get()) sources.add(aptoideRepository.updates(filtered))
                if (prefs.useApkPure.get()) sources.add(apkPureRepository.updates(filtered))
                if (prefs.useGitLab.get()) sources.add(gitLabRepository.updates(filtered))
                if (prefs.usePlay.get()) sources.add(playRepository.updates(filtered))
                if (prefs.useRuStore.get()) sources.add(ruStoreRepository.updates(filtered))

                val totalSources = sources.size
                if (totalSources > 0) {
                    val errorCount = AtomicInteger(0)
                    val wrappedSources = sources.map { source ->
                        source.catch { e ->
                            Log.e("UpdatesRepository", "Source error", e)
                            errorCount.incrementAndGet()
                            emit(emptyList())
                        }
                    }
                    wrappedSources
                        .combine { updates -> emit(updates.flatMap { it }) }
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
