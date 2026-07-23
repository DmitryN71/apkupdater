package com.apkupdater.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.foundation.lazy.grid.items
import com.apkupdater.R
import com.apkupdater.data.ui.AppsUiState
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvInstalledItem
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.util.isAndroidTv
import com.apkupdater.viewmodel.AppsViewModel
import org.koin.androidx.compose.koinViewModel


@Composable
fun AppsScreen(
	viewModel: AppsViewModel = koinViewModel()
) {
	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		AppsScreenLoading(viewModel, it)
	}.onError {
		AppsScreenError()
	}.onSuccess {
		AppsScreenSuccess(viewModel, it)
	}
}

@Composable
fun AppsScreenSuccess(viewModel: AppsViewModel, state: AppsUiState.Success) = Column {
	AppsTopBar()
	AppsFilterBar(viewModel, state.excludeSystem, state.excludeAppStore, state.excludeDisabled)

	val query by viewModel.query.collectAsStateWithLifecycle()
	val onlyIgnored by viewModel.onlyIgnored.collectAsStateWithLifecycle()
	// Memoised: the list can be several hundred apps and recomposes on every keystroke.
	val apps = remember(state.apps, query, onlyIgnored) {
		val text = query.trim()
		state.apps
			.filter { !onlyIgnored || it.ignored }
			.filter { text.isEmpty() || it.name.contains(text, true) || it.packageName.contains(text, true) }
	}

	Box(Modifier.weight(1f).fillMaxWidth()) {
		TvInstalledGrid {
			items(apps, key = { it.packageName }) {
				TvInstalledItem(it) { app -> viewModel.ignore(app) }
			}
		}
	}
}

@Composable
fun AppsScreenLoading(viewModel: AppsViewModel, state: AppsUiState.Loading) = Column {
	AppsTopBar()
	AppsFilterBar(viewModel, state.excludeSystem, state.excludeAppStore, state.excludeDisabled)
	Box(Modifier.weight(1f).fillMaxWidth()) { LoadingGrid() }
}

/**
 * Filter chips + search over the installed apps.
 *
 * The chips read positively ("show these"), while the stored preferences are negative
 * ("exclude these") — the flags are inverted right here so the UI stays intuitive without
 * migrating anyone's settings. "Only ignored" is the odd one out: it narrows the list instead
 * of widening it, which is why its label says so explicitly.
 */
@Composable
fun AppsFilterBar(
	viewModel: AppsViewModel,
	excludeSystem: Boolean,
	excludeAppStore: Boolean,
	excludeDisabled: Boolean
) {
	val query by viewModel.query.collectAsStateWithLifecycle()
	val onlyIgnored by viewModel.onlyIgnored.collectAsStateWithLifecycle()
	val isTv = LocalContext.current.isAndroidTv()
	var editing by remember { mutableStateOf(false) }

	Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
		Row(
			Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			AppFilterChip(!excludeSystem, stringResource(R.string.filter_system)) { viewModel.onSystemClick() }
			AppFilterChip(!excludeAppStore, stringResource(R.string.filter_store)) { viewModel.onAppStoreClick() }
			AppFilterChip(!excludeDisabled, stringResource(R.string.filter_disabled)) { viewModel.onDisabledClick() }
			AppFilterChip(onlyIgnored, stringResource(R.string.filter_only_ignored)) { viewModel.onOnlyIgnoredClick() }
		}
		OutlinedTextField(
			value = query,
			onValueChange = { viewModel.onQueryChange(it) },
			placeholder = { Text(stringResource(R.string.filter_search_hint)) },
			singleLine = true,
			// On TV a focused text field would swallow the D-pad, so it stays read-only until
			// it is explicitly clicked — same pattern as the token fields in Settings.
			readOnly = isTv && !editing,
			modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
				.then(if (isTv) Modifier.clickable { editing = true } else Modifier)
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFilterChip(
	selected: Boolean,
	label: String,
	onClick: () -> Unit
) = FilterChip(
	selected = selected,
	onClick = onClick,
	label = { Text(label) },
	leadingIcon = if (selected) {
		{ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
	} else null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsTopBar() = TopAppBar(
	title = { Text(stringResource(R.string.tab_apps)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Home, null)
		}
	}
)

@Composable
fun AppsScreenError() = DefaultErrorScreen()
