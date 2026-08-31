package com.apkupdater.ui.screen

import android.app.Activity.RESULT_CANCELED
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.apkupdater.data.snack.SnackType
import com.apkupdater.data.snack.TextSnack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.apkupdater.util.isAndroidTv
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apkupdater.data.ui.Screen
import com.apkupdater.ui.component.BadgeText
import com.apkupdater.ui.theme.AppTheme
import com.apkupdater.util.Badger
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Themer
import com.apkupdater.viewmodel.AppsViewModel
import com.apkupdater.viewmodel.MainViewModel
import com.apkupdater.viewmodel.SearchViewModel
import com.apkupdater.viewmodel.SettingsViewModel
import com.apkupdater.viewmodel.UpdatesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.androidx.compose.get
import org.koin.androidx.compose.koinViewModel
import kotlin.coroutines.CoroutineContext


@Composable
fun MainScreen(mainViewModel: MainViewModel = koinViewModel()) {
	// ViewModels
	val appsViewModel: AppsViewModel = koinViewModel()
	val updatesViewModel: UpdatesViewModel = koinViewModel()
	val searchViewModel: SearchViewModel = koinViewModel()
	val settingsViewModel: SettingsViewModel = koinViewModel()

	// Navigation
	val navController = rememberNavController()

	// Refresh
	val isRefreshing = mainViewModel.isRefreshing.collectAsStateWithLifecycle()
	LaunchedEffect(Unit) {
		mainViewModel.refreshOnStart(appsViewModel, updatesViewModel)
	}

	// Used to launch the install intent and get dismissal result
	val installLog = get<InstallLog>()
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (it.resultCode == RESULT_CANCELED) {
			installLog.cancelCurrentInstall()
		}
	}

	// Check intent when cold starting from notification
	checkNotificationIntent(mainViewModel, updatesViewModel, navController, launcher)

	// Check notification intent when hot starting
	intentListener(mainViewModel, updatesViewModel, navController, launcher)

	// Theme
	val theme = get<Themer>().flow().collectAsStateWithLifecycle().value

	// SnackBar
	val snackBarHostState = handleSnackBar()

	AppTheme(theme) {
		Box {
			Scaffold(
				bottomBar = { BottomBar(mainViewModel, navController) }
			) { padding ->
				NavHost(
					navController, padding, mainViewModel, appsViewModel,
					updatesViewModel, searchViewModel, settingsViewModel,
					isRefreshing = isRefreshing.value,
					onRefresh = { mainViewModel.refresh(appsViewModel, updatesViewModel) }
				)
			}
			SnackbarHost(
				hostState = snackBarHostState,
				// Keep the snackbar above the system navigation bar (edge-to-edge on API 35+).
				modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
			) { data ->
				val snack = data.visuals as? TextSnack
				val containerColor = when (snack?.type) {
					SnackType.ERROR -> MaterialTheme.colorScheme.errorContainer
					else -> MaterialTheme.colorScheme.inverseSurface
				}
				val contentColor = when (snack?.type) {
					SnackType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
					else -> MaterialTheme.colorScheme.inverseOnSurface
				}
				val icon = when (snack?.type) {
					SnackType.SUCCESS -> Icons.Outlined.CheckCircle
					SnackType.ERROR -> Icons.Outlined.Warning
					else -> null
				}
				val iconTint = when (snack?.type) {
					SnackType.SUCCESS -> Color(0xFF66BB6A)
					else -> contentColor
				}
				Snackbar(
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
					shape = RoundedCornerShape(16.dp),
					containerColor = containerColor,
					contentColor = contentColor,
					dismissActionContentColor = contentColor
				) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						if (icon != null) {
							Icon(icon, null, Modifier.size(20.dp), tint = iconTint)
							Spacer(Modifier.width(10.dp))
						}
						Text(data.visuals.message)
					}
				}
			}
		}
	}
}

@Composable
fun handleSnackBar(): SnackbarHostState {
	val snackBarHostState = remember { SnackbarHostState() }
	get<SnackBar>().flow().CollectAsEffect(Dispatchers.IO) {
		snackBarHostState.showSnackbar(it)
	}
	return snackBarHostState
}

@Composable
fun <T> Flow<T>.CollectAsEffect(
	context: CoroutineContext = Dispatchers.IO,
	block: suspend (T) -> Unit
) = LaunchedEffect(Unit) {
	onEach(block).flowOn(context).launchIn(this)
}

@Composable
fun intentListener(
	mainViewModel: MainViewModel,
	updatesViewModel: UpdatesViewModel,
	navController: NavController,
	launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
) = runCatching {
	val activity = LocalContext.current as ComponentActivity
	DisposableEffect(Unit) {
		val listener = Consumer<Intent> {
			mainViewModel.processIntent(it, launcher, updatesViewModel, navController)
		}
		activity.addOnNewIntentListener(listener)
		onDispose { activity.removeOnNewIntentListener(listener) }
	}
}.getOrElse {
	Log.e("MainScreen", "Error listening to intent.", it)
}

@Composable
fun checkNotificationIntent(
	mainViewModel: MainViewModel,
	updatesViewModel: UpdatesViewModel,
	navController: NavController,
	launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
) = runCatching {
	val activity = LocalContext.current as ComponentActivity
	mainViewModel.processIntent(activity.intent, launcher, updatesViewModel, navController)
}.getOrElse {
	Log.e("MainScreen", "Error checking notification intent.", it)
}

@Composable
fun BottomBar(mainViewModel: MainViewModel, navController: NavController) = BottomAppBar {
	val badges = get<Badger>().flow().collectAsStateWithLifecycle().value
	mainViewModel.screens.forEach { screen ->
		val state = navController.currentBackStackEntryAsState().value
		val selected = state?.destination?.route  == screen.route
		BottomBarItem(mainViewModel, navController, screen, selected, badges[screen.route].orEmpty())
	}
}

@Composable
fun RowScope.BottomBarItem(
	mainViewModel: MainViewModel,
    navController: NavController,
    screen: Screen,
    selected: Boolean,
    badge: String
) {
	// Material's own focus indication on a navigation item is a faint state layer. Reported from
	// 4PDA: "на них при переходе пультом фокус теряется из виду, блеклый цвет с расстояния плохо
	// видно", and worse on a light background. Google's TV focus guide lists four indications —
	// scale, outline, glow, colour — and colour is the one that fits here: scale would push the
	// item past the bar's edge and an outline traces the invisible touch target, both already
	// tried and rejected in earlier builds. So the focused item fills solid primary and its icon
	// and label invert, exactly like the card action buttons and the settings rows.
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	// The inset is TV-only on purpose. It keeps the filled block off the bar's edges, but it
	// also takes 12dp of height away from the item, and on a phone — where touch never focuses
	// anything, so the fill would never be seen anyway — that could squeeze the label for no
	// benefit at all. Phones keep exactly the layout they had.
	val inset = if (LocalContext.current.isAndroidTv())
		Modifier.padding(horizontal = 4.dp, vertical = 6.dp) else Modifier
	NavigationBarItem(
	modifier = inset.background(
		if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
		RoundedCornerShape(16.dp)
	),
	interactionSource = interaction,
	colors = if (focused) NavigationBarItemDefaults.colors(
		selectedIconColor = MaterialTheme.colorScheme.onPrimary,
		selectedTextColor = MaterialTheme.colorScheme.onPrimary,
		unselectedIconColor = MaterialTheme.colorScheme.onPrimary,
		unselectedTextColor = MaterialTheme.colorScheme.onPrimary,
		// Otherwise the selected item's own pill sits inside the filled block.
		indicatorColor = Color.Transparent
	) else NavigationBarItemDefaults.colors(),
	icon = {
		BadgedBox({ BadgeText(badge) }) {
			Icon(if (selected) screen.iconSelected else screen.icon, contentDescription = null)
		}
   	},
	label = {
		Text(
			stringResource(screen.resourceId),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	},
	selected = selected,
	onClick = { mainViewModel.navigateTo(navController, screen.route) }
	)
}

@Composable
fun NavHost(
	navController: NavHostController,
	padding: PaddingValues,
	mainViewModel: MainViewModel,
	appsViewModel: AppsViewModel,
	updatesViewModel: UpdatesViewModel,
	searchViewModel: SearchViewModel,
	settingsViewModel: SettingsViewModel,
	isRefreshing: Boolean = false,
	onRefresh: () -> Unit = {}
) = NavHost(
	navController = navController,
	// Always open on Updates. Remembering the last tab meant backing out of the app while on
	// Search brought it back on Search — an updater should show updates when you open it. The
	// tab still survives rotation, because rememberNavController saves the back stack itself.
	startDestination = Screen.Updates.route,
	modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
) {
	composable(Screen.Apps.route) { AppsScreen(appsViewModel) }
	composable(Screen.Search.route) { SearchScreen(searchViewModel) }
	composable(Screen.Updates.route) { UpdatesScreen(updatesViewModel, isRefreshing, onRefresh) }
	composable(Screen.Settings.route) { SettingsScreen(settingsViewModel) }
}
