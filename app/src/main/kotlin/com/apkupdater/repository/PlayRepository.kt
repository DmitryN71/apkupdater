package com.apkupdater.repository

import android.net.Uri
import android.util.Log
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.getVersion
import com.apkupdater.data.ui.getVersionCode
import com.apkupdater.data.ui.isPackageName
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.AppJournal
import com.apkupdater.util.play.PlayClientProvider
import com.aurora.gplayapi.PlayException
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.AppFile
import com.aurora.gplayapi.model.Restriction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow


/** How a manual reset of the anonymous Play check-in session ended. */
sealed interface PlayAccountSwitch {
    data object Done : PlayAccountSwitch
    data class Failed(val reason: String) : PlayAccountSwitch
}

class PlayRepository(
    private val clientProvider: PlayClientProvider,
    private val prefs: Prefs
) {
    companion object {
        private const val TAG = "PlayRepository"
        private const val MAX_SEARCH_RESULTS = 10
        private const val MAX_DELIVERY_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MS = 1_500L
    }

    /**
     * Resets the anonymous Play device check-in session and warms up a fresh one immediately
     * so the user gets confirmation (or an error) right away.
     */
    suspend fun switchAccount(): PlayAccountSwitch {
        Log.i(TAG, "Resetting anonymous Google Play session at user's request.")
        return try {
            clientProvider.resetSession()
            clientProvider.playClient.apps.details("com.google.android.gm")
            Log.i(TAG, "Created a fresh anonymous Google Play session.")
            PlayAccountSwitch.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Manual session reset failed", e)
            PlayAccountSwitch.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun <T> withAnonymousRecovery(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: PlayException.Unauthorized) {
            Log.w(TAG, "Anonymous Play session rejected (${e.code}), minting a fresh session and retrying.", e)
            clientProvider.resetSession()
            block()
        }
    }

    suspend fun search(text: String) = flow {
        val query = text.trim()
        if (query.isEmpty()) {
            emit(Result.success(emptyList()))
            return@flow
        }

        if (!query.contains(" ") && query.contains(".")) {
            val app = try {
                withAnonymousRecovery { clientProvider.playClient.apps.details(query) }
            } catch (_: PlayException.NotFound) {
                null
            }
            if (app == null || app.packageName.isBlank() || app.restriction != Restriction.AVAILABLE) {
                emit(Result.success(emptyList()))
            } else {
                emit(Result.success(listOf(app.toAppUpdate(::getInstallFiles))))
            }
        } else {
            val bundle = clientProvider.webPlayClient.search.results(query)
            val candidatePackages = bundle.clusters
                .asSequence()
                .flatMap { it.apps.asSequence() }
                .map { it.packageName }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_SEARCH_RESULTS)
                .toList()

            if (candidatePackages.isEmpty()) {
                emit(Result.success(emptyList()))
                return@flow
            }

            val hydratedByPkg = withAnonymousRecovery {
                clientProvider.playClient.apps.details(candidatePackages)
            }.associateBy { it.packageName }

            val updates = candidatePackages
                .mapNotNull { hydratedByPkg[it] }
                .filter { it.restriction == Restriction.AVAILABLE && it.versionCode > 0L }
                .filter { filterAlphaBeta(it) }
                .map { it.toAppUpdate(::getInstallFiles) }

            emit(Result.success(updates))
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(Result.failure(error))
        Log.e(TAG, "Error searching for $text.", error)
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val (valid, invalid) = apps.filter { !it.ignored }.partition { isPackageName(it.packageName) }
        if (invalid.isNotEmpty()) {
            AppJournal.add(
                TAG,
                "Play: ${invalid.size} non-package id(s) left out: " + invalid.take(5).joinToString { it.packageName }
            )
        }
        val packageNames = valid.map { it.packageName }.distinct()
        if (packageNames.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val details = withAnonymousRecovery {
            clientProvider.playClient.apps.details(packageNames)
        }
        val (unavailable, offered) = details.partition {
            it.versionCode <= 0L || it.restriction != Restriction.AVAILABLE
        }
        val named = unavailable.map { it.packageName }.filter { it.isNotBlank() }
        if (named.isNotEmpty()) {
            Log.w(TAG, "No version offered for: ${named.joinToString()}")
        }
        val updates = offered
            .filter { it.versionCode > valid.getVersionCode(it.packageName) }
            .filter { filterAlphaBeta(it) }
            .map {
                it.toAppUpdate(
                    ::getInstallFiles,
                    valid.getVersion(it.packageName),
                    valid.getVersionCode(it.packageName)
                )
            }
        emit(updates)
    }.catch {
        if (it is CancellationException) throw it
        Log.e(TAG, "Error looking for updates.", it)
        throw it
    }

    private fun filterAlphaBeta(app: App): Boolean {
        if (prefs.ignoreBeta.get() && (app.earlyAccess || app.testingProgram?.isSubscribed == true)) return false
        if (prefs.ignoreAlpha.get() && app.versionName.contains("alpha", ignoreCase = true)) return false
        if (prefs.ignoreBeta.get() && app.versionName.contains("beta", ignoreCase = true)) return false
        return true
    }

    private suspend fun getInstallFiles(app: App): List<AppFile> {
        val offerType = app.offerType.takeIf { it > 0 } ?: 1
        return resolvePlayFilesWithRetry(
            maxAttempts = MAX_DELIVERY_ATTEMPTS,
            baseDelayMs = BASE_RETRY_DELAY_MS,
            delayFn = { ms -> delay(ms) },
            onInvalidateSession = { clientProvider.resetSession() },
            fetch = {
                clientProvider.playClient.delivery
                    .resolve(
                        packageName = app.packageName,
                        versionCode = app.versionCode,
                        offerType = offerType,
                        patchFormats = emptyList()
                    )
                    .filter { it.type == AppFile.Type.BASE || it.type == AppFile.Type.SPLIT }
            }
        )
    }
}

internal suspend fun resolvePlayFilesWithRetry(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 1_500L,
    delayFn: suspend (Long) -> Unit = { delay(it) },
    onInvalidateSession: () -> Unit = {},
    fetch: suspend () -> List<AppFile>
): List<AppFile> {
    var lastError: Throwable? = null
    for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
        try {
            val files = fetch()
            if (hasValidPlayDownloadUrls(files)) {
                return files
            }
            lastError = PlayException.NoFilesAvailable(-1)
            if (attempt < maxAttempts) {
                onInvalidateSession()
                delayFn(baseDelayMs * attempt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlayException.Unauthorized) {
            lastError = e
            if (attempt < maxAttempts) {
                onInvalidateSession()
            } else {
                throw e
            }
        } catch (e: PlayException.RateLimited) {
            lastError = e
            if (attempt < maxAttempts) {
                if (attempt == maxAttempts - 1) {
                    onInvalidateSession()
                }
                delayFn(baseDelayMs * attempt)
            } else {
                throw e
            }
        } catch (e: PlayException.ServerError) {
            lastError = e
            if (attempt < maxAttempts) {
                delayFn(baseDelayMs * attempt)
            } else {
                throw e
            }
        } catch (e: PlayException.AppNotSupported) {
            throw e
        } catch (e: PlayException.AppNotPurchased) {
            throw e
        } catch (e: PlayException.AppRemoved) {
            throw e
        } catch (e: PlayException.NotFound) {
            throw e
        } catch (e: PlayException.NoFilesAvailable) {
            throw e
        }
    }
    throw lastError ?: PlayException.NoFilesAvailable(-1)
}

internal fun hasValidPlayDownloadUrls(files: List<AppFile>): Boolean =
    files.isNotEmpty() && files.all { it.url.trim().startsWith("https://", ignoreCase = true) }

fun App.toAppUpdate(
    getInstallFiles: suspend (App) -> List<AppFile>,
    oldVersion: String = "",
    oldVersionCode: Long = 0L
) = AppUpdate(
    displayName,
    packageName,
    versionName,
    oldVersion,
    versionCode,
    oldVersionCode,
    PlaySource,
    Uri.parse(iconArtwork.url),
    Link.Play { getInstallFiles(this) },
    whatsNew = changes,
    sourceUrl = "https://play.google.com/store/apps/details?id=$packageName",
    updateDate = updatedOn
)
