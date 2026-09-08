package com.apkupdater.data.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import com.apkupdater.R

sealed class Screen(
	val route: String,
	@StringRes val resourceId: Int,
	val icon: ImageVector,
	val iconSelected: ImageVector
) {
	// A house, until build 147. It meant "home tab" back when Apps was the start destination —
	// it has not been since, and the tab lists installed apps, so the house was pointing at
	// nothing. Icons.Apps is the grid Android itself uses for an app drawer.
	data object Apps : Screen("apps", R.string.tab_apps, Icons.Outlined.Apps, Icons.Filled.Apps)
	data object Search : Screen("search", R.string.tab_search, Icons.Outlined.Search, Icons.Filled.Search)
	data object Updates : Screen("updates", R.string.tab_updates, Icons.Outlined.Sync, Icons.Filled.Sync)
	data object Settings : Screen("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings)
}
