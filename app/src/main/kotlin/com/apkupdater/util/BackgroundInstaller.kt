package com.apkupdater.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
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
    private val downloader: Downloader
) {

    data class Task(val id: Int, val name: String, val progress: Long = 0L, val total: Long = 0L)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<Map<Int, Task>>(emptyMap())
    val tasks: StateFlow<Map<Int, Task>> = _tasks

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
    @Synchronized
    fun end(id: Int) {
        // Do NOT stopService() here. On a fast/instant task (e.g. a root install that su-fails
        // immediately on a broken GSI) the set can empty before DownloadService.onCreate() has
        // run startForeground(); tearing the service down before it promotes makes the system
        // throw ForegroundServiceDidNotStartInTimeException — an uncatchable crash. The service
        // self-stops from its own tasks collector, which runs only AFTER startForeground() has
        // satisfied the foreground-service contract.
        _tasks.update { it - id }
    }

    /** Aborts all in-flight downloads (triggered by the notification's Cancel action). */
    fun cancelAll() {
        _tasks.value.keys.toList().forEach { downloader.cancel(it) }
    }

}
