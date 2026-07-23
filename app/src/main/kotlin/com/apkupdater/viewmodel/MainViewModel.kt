package com.apkupdater.viewmodel

import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.apkupdater.data.ui.Screen
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.UpdatesNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch


class MainViewModel(
	private val prefs: Prefs
) : ViewModel() {

	val screens = listOf(Screen.Apps, Screen.Search, Screen.Updates, Screen.Settings)

	val isRefreshing = MutableStateFlow(false)

	private var didStartupRefresh = false

	/**
	 * One-shot automatic check on app start. MainScreen drives it from a LaunchedEffect(Unit),
	 * which re-fires every time the composition is recreated — including on a screen rotation,
	 * where the Activity is destroyed and rebuilt. That restarted the whole update check and
	 * rebuilt the list, resetting the cards of downloads that were still running. This ViewModel
	 * survives configuration changes, so the flag limits the auto-check to genuinely new starts.
	 * Pull-to-refresh calls [refresh] directly and is unaffected.
	 */
	fun refreshOnStart(
		appsViewModel: AppsViewModel,
		updatesViewModel: UpdatesViewModel
	) {
		if (didStartupRefresh) return
		didStartupRefresh = true
		refresh(appsViewModel, updatesViewModel)
	}

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
		// Install session results are handled by InstallReceiver (a broadcast
		// receiver), so the only intent processed here is the notification tap.
		when {
			intent.action == UpdatesNotification.UpdateAction -> processUpdateIntent(navController, updatesViewModel)
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

	private fun processUpdateIntent(
		navController: NavController,
		updatesViewModel: UpdatesViewModel
	) {
		navigateTo(navController, Screen.Updates.route)
		updatesViewModel.refresh()
	}

}
