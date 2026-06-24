package com.apkupdater.viewmodel

import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.apkupdater.R
import com.apkupdater.data.ui.AppInstallStatus
import com.apkupdater.data.ui.Screen
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.Stringer
import com.apkupdater.util.UpdatesNotification
import com.apkupdater.util.installErrorResId
import com.apkupdater.util.getAppId
import com.apkupdater.util.getIntentExtra
import com.apkupdater.util.orFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch


class MainViewModel(
	private val prefs: Prefs,
	private val installLog: InstallLog,
	private val stringer: Stringer
) : ViewModel() {

	val screens = listOf(Screen.Apps, Screen.Search, Screen.Updates, Screen.Settings)

	val isRefreshing = MutableStateFlow(false)

	private var currentInstallId = 0

	fun refresh(
		appsViewModel: AppsViewModel,
		updatesViewModel: UpdatesViewModel
	) = viewModelScope.launch {
		isRefreshing.value = true
		appsViewModel.refresh(false)
		updatesViewModel.refresh(false).invokeOnCompletion {
			isRefreshing.value = false
		}
	}

	fun processIntent(
		intent: Intent,
		launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
		updatesViewModel: UpdatesViewModel,
		navController: NavController
	) {
		when {
			intent.action == UpdatesNotification.UpdateAction -> processUpdateIntent(navController, updatesViewModel)
			intent.action?.contains(SessionInstaller.INSTALL_ACTION).orFalse() -> processInstallIntent(intent, launcher)
			else -> {}
		}
	}

	fun navigateTo(navController: NavController, route: String) = navController.navigate(route) {
		popUpTo(navController.graph.findStartDestination().id) { saveState = true }
		launchSingleTop = true
		restoreState = true
		prefs.lastTab.put(route)
	}

	fun getLastRoute() = prefs.lastTab.get()

	private fun processInstallIntent(
		intent: Intent,
		launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
	) = viewModelScope.launch(Dispatchers.IO) {
		when (intent.extras?.getInt(PackageInstaller.EXTRA_STATUS)) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				currentInstallId = intent.getAppId() ?: 0
				// Launch intent to confirm install
				intent.getIntentExtra()?.let {
					it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
					launcher.launch(it)
				}
			}
			PackageInstaller.STATUS_SUCCESS -> {
				intent.getAppId()?.let {
					installLog.emitStatus(AppInstallStatus(true, it))
				}
			}
			else -> {
				// We assume error and cancel the install
				val status = intent.extras?.getInt(PackageInstaller.EXTRA_STATUS) ?: -1
				val rawMessage = intent.extras?.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
				val reason = installErrorReason(status, rawMessage)
				intent.getAppId()?.let {
					installLog.emitStatus(AppInstallStatus(false, it, reason = reason))
				}
				Log.e("MainViewModel", "Failed to install app: $rawMessage $intent")
			}
		}
	}

	private fun installErrorReason(status: Int, rawMessage: String?): String {
		// The raw message carries the most specific INSTALL_FAILED_* code when present
		// (e.g. signature mismatch arrives as a generic STATUS_FAILURE with the detail
		// only in the message), so scan it first, then fall back to the numeric status.
		val fromRaw = rawMessage?.let { installErrorResId(it) }
		val resId = when {
			fromRaw != null && fromRaw != R.string.install_error_unknown -> fromRaw
			status == PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_error_aborted
			status == PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_error_blocked
			status == PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.install_error_signature
			status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.install_error_incompatible
			status == PackageInstaller.STATUS_FAILURE_INVALID -> R.string.install_error_invalid
			status == PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_error_storage
			else -> R.string.install_error_unknown
		}
		return stringer.get(resId)
	}

	private fun processUpdateIntent(
		navController: NavController,
		updatesViewModel: UpdatesViewModel
	) {
		navigateTo(navController, Screen.Updates.route)
		updatesViewModel.refresh()
	}

}
