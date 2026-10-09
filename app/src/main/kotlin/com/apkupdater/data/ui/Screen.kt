package com.apkupdater.data.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import com.apkupdater.R

sealed class Screen(
	val route: String,
	@param:StringRes val resourceId: Int,
	val icon: ImageVector,
	val iconSelected: ImageVector
) {
	// A house, until build 147. It meant "home tab" back when Apps was the start destination —
	// it has not been since, and the tab lists installed apps, so the house was pointing at
	// nothing. Icons.Apps is the grid Android itself uses for an app drawer.
	data object Apps : Screen("apps", R.string.tab_apps, Icons.Outlined.Apps, Icons.Filled.Apps)
	// The house is back (3.10.0), and this time it points at something: what you can do — search,
	// check, the sources. See HomeScreen.
	data object Home : Screen("home", R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home)
	// No tab since 3.10.0 — search is Home's top bar. Kept as the key its result count is filed
	// under in Badger.
	data object Search : Screen("search", R.string.tab_search, Icons.Outlined.Search, Icons.Filled.Search)
	data object Updates : Screen("updates", R.string.tab_updates, Icons.Outlined.Sync, Icons.Filled.Sync)
	data object Settings : Screen("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings)
}
