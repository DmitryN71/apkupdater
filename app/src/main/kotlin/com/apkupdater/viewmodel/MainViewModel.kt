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
import kotlinx.coroutines.launch


class MainViewModel(
	private val prefs: Prefs
) : ViewModel() {

	// Home first, because it is the start destination — unless the app checks at launch, when it
	// opens on Updates, right beside it. Back from another tab lands on the start, and a bar
	// whose start sat far off made that jump look like a glitch. Updates right beside it: a
	// check started on Home goes on there. Search is no tab since 3.10.0 — it is Home's top bar.
	val screens = listOf(Screen.Home, Screen.Updates, Screen.Apps, Screen.Settings)

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
		// The installed-apps list loads either way; only the update check is optional. Skipping
		// this whole call would have left the Apps tab empty as well. UpdatesViewModel reads the
		// same switch to decide whether it starts as a check or as the Check button.
		if (prefs.checkOnLaunch.get()) refresh(appsViewModel, updatesViewModel)
		else appsViewModel.refresh(false)
	}

	fun refresh(
		appsViewModel: AppsViewModel,
		updatesViewModel: UpdatesViewModel
	) = viewModelScope.launch {
		// Nothing tracks "a refresh is running" here any more: UpdatesViewModel.isChecking is
		// the one source of truth for that, and it is what the Refresh button reads.
		appsViewModel.refresh(false)
		updatesViewModel.refresh(false)
	}

	private var didProcessLaunchIntent = false

	/**
	 * The cold-start path. An Activity keeps the Intent it was launched with, so every
	 * recreation — a rotation, most obviously — handed the same UpdateAction back and restarted
	 * the whole update check. This ViewModel outlives the Activity, so the flag holds.
	 * [processIntent] itself stays unguarded: onNewIntent means the user tapped again and does
	 * deserve a fresh check.
	 */
	fun processLaunchIntent(
		intent: Intent,
		launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
		updatesViewModel: UpdatesViewModel,
		navController: NavController
	) {
		if (didProcessLaunchIntent) return
		// Marked AFTER the work, not before: the caller swallows exceptions, so setting it
		// first would consume the launch intent for good if anything in there threw.
		processIntent(intent, launcher, updatesViewModel, navController)
		didProcessLaunchIntent = true
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
	}

	private fun processUpdateIntent(
		navController: NavController,
		updatesViewModel: UpdatesViewModel
	) {
		navigateTo(navController, Screen.Updates.route)
		updatesViewModel.refresh()
	}

}
