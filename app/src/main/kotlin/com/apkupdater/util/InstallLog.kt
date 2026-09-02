package com.apkupdater.util

import android.content.Intent
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.data.ui.AppInstallStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap


class InstallLog {

    private val status = MutableSharedFlow<AppInstallStatus>(100)
    private val progress = MutableSharedFlow<AppInstallProgress>(100)
    private var currentInstallLog: Int = 0

    /**
     * Install confirmations that arrived while the app was not on screen.
     *
     * The system asks for confirmation through an Intent we cannot launch from the background,
     * so it is offered as a notification instead — which is silently dropped when the user has
     * never granted POST_NOTIFICATIONS. We only ask for that permission when the scheduled
     * check is switched on, so for most users the confirmation was simply lost: the card sat
     * on "Cancel 100%" for good, and the commit lock it held was never released, hanging every
     * later install in the process. Kept here so MainActivity can show it on resume, which is
     * the first moment it can be shown at all.
     */
    private val pendingConfirm = ConcurrentHashMap<Int, Intent>()

    fun rememberConfirm(id: Int, intent: Intent) { pendingConfirm[id] = intent }

    /** Dropped once the install reaches a real outcome, however it got there. */
    fun forgetConfirm(id: Int) { pendingConfirm.remove(id) }

    fun takePendingConfirms(): List<Pair<Int, Intent>> {
        val all = pendingConfirm.entries.map { it.key to it.value }
        all.forEach { pendingConfirm.remove(it.first) }
        return all
    }

    fun status() = status.asSharedFlow()
    fun progress() = progress.asSharedFlow()

    fun cancelCurrentInstall() = status.tryEmit(AppInstallStatus(false, currentInstallLog, false))
    fun emitStatus(newStatus: AppInstallStatus) = status.tryEmit(newStatus)
    fun emitProgress(newProgress: AppInstallProgress) = progress.tryEmit(newProgress)

}
