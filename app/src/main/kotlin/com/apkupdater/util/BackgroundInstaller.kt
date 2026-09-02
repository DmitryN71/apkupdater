package com.apkupdater.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.apkupdater.prefs.Prefs
import com.apkupdater.worker.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Owns download/install work for the whole process lifetime.
 *
 * Work launched in [scope] is not tied to any screen: it keeps running when the
 * user leaves the app or the Activity is destroyed. While at least one task is
 * active, [DownloadService] runs as a foreground service so the system keeps
 * the process alive (and shows the mandatory progress notification).
 *
 * Tracks per-task download progress (fed from [InstallLog.progress]) so the
 * service can render a real progress bar, and exposes [cancelAll] for the
 * notification's Cancel action.
 */
class BackgroundInstaller(
    private val context: Context,
    installLog: InstallLog,
    private val downloader: Downloader,
    private val prefs: Prefs
) {

    data class Task(val id: Int, val name: String, val progress: Long = 0L, val total: Long = 0L)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<Map<Int, Task>>(emptyMap())
    val tasks: StateFlow<Map<Int, Task>> = _tasks

    /**
     * How many tasks are running for each id.
     *
     * Two really can share one: Download resolves its link before the card flips to
     * installing, so a tap during that window starts an install under the same id, and an app
     * present in both the Updates and the Search list can be started from either. Without a
     * count the first end() tore everything down — it removed the single map entry, cleared
     * the download session active flag, and the sweep that follows deleted the OTHER task
     * APK out from under the installer. The service stopped too, leaving the survivor with no
     * foreground protection.
     */
    private val refs = HashMap<Int, Int>()

    init {
        // Feed download progress into the matching task (ignore ids we don't track).
        installLog.progress().onEach { p ->
            _tasks.update { current ->
                val task = current[p.id] ?: return@update current
                current + (p.id to task.copy(
                    progress = p.progress ?: task.progress,
                    total = p.total ?: task.total
                ))
            }
        }.launchIn(scope)
    }

    /** Call when a download/install task starts. Pair with [end] in a finally block. */
    @Synchronized
    fun begin(id: Int, name: String) {
        val nested = (refs[id] ?: 0) > 0
        refs[id] = (refs[id] ?: 0) + 1
        // Clears a cancel left behind by a previous task with this id. It belongs here, once
        // per task, and not inside Downloader's own methods: a single task calls those several
        // times (one per Play split), and clearing between two of them would drop a cancel.
        // Skipped when an id is already running, or the second task would reset the first one
        // flags and re-arm a Cancel that can no longer stop anything.
        if (!nested) downloader.beginDownloads(id)
        if (_tasks.value.isEmpty()) {
            // Best effort: on Android 12+ this can be rejected if the app is in the
            // background — the work still runs, just without foreground protection.
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }.onFailure { Log.e("BackgroundInstaller", "Could not start download service", it) }
        }
        _tasks.update { it + (id to Task(id, name)) }
    }

    /** Call when a download/install task finishes (success, failure or cancel). */
    fun end(id: Int) {
        // The sweep runs OUTSIDE the monitor on purpose: it is file I/O plus an OkHttp cache
        // eviction, and holding the monitor across it would stall the next begin() — and
        // through OkHttp own cache lock it could stall the main thread too.
        if (release(id) && prefs.cleanUpAfterInstall.get()) downloader.cleanUp()
    }

    /** Returns true when that was the last task running. */
    @Synchronized
    private fun release(id: Int): Boolean {
        // Do NOT stopService() here. On a fast/instant task (e.g. a root install that su-fails
        // immediately on a broken GSI) the set can empty before DownloadService.onCreate() has
        // run startForeground(); tearing the service down before it promotes makes the system
        // throw ForegroundServiceDidNotStartInTimeException — an uncatchable crash. The service
        // self-stops from its own tasks collector, which runs only AFTER startForeground() has
        // satisfied the foreground-service contract.
        val remaining = (refs[id] ?: 1) - 1
        if (remaining > 0) {
            refs[id] = remaining
            return false
        }
        refs.remove(id)
        _tasks.update { it - id }
        downloader.endDownloads(id)
        // The caller sweeps the download cache when this returns true. That is the ONLY place
        // it can happen: while anything is active Downloader.cleanUp() deliberately refuses to
        // delete, because the files it would remove are the ones about to be installed.
        // Replaces the per-ViewModel activeInstalls counter, which drifted — it skipped its
        // own decrement whenever the preference was off, and three failure branches never
        // decremented at all, so one cancelled download disabled cleanup for good.
        return _tasks.value.isEmpty()
    }

    /** Aborts all in-flight downloads (triggered by the notification's Cancel action). */
    fun cancelAll() {
        _tasks.value.keys.toList().forEach { downloader.cancel(it) }
    }

}
