package com.apkupdater.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.material3.pullrefresh.PullRefreshIndicator
import androidx.compose.material3.pullrefresh.pullRefresh
import androidx.compose.material3.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.foundation.lazy.grid.items
import com.apkupdater.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.apkupdater.util.isAndroidTv
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.UpdatesUiState
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.EmptyGrid
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.RefreshIcon
import com.apkupdater.ui.component.StopCheckingIcon
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvUpdateItem
import com.apkupdater.ui.component.TvIconButton
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.util.formatBytes
import com.apkupdater.viewmodel.UpdatesViewModel


@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel, onRefresh: () -> Unit = {}) = Column {
	// The top bar is built ONCE here, outside the state branches. It used to be repeated inside
	// UpdatesScreenLoading and UpdatesScreenSuccess — two different call sites, so every switch
	// between "checking" and "done" DISPOSED the Refresh button the user had just pressed. With
	// its node gone the focus system fell back to the first item of the bottom bar and sat there
	// for the whole check, which is exactly what was reported from a TV. Hoisting it keeps the
	// button alive, so focus stays where the user left it.
	UpdatesTopBar(viewModel)
	ProgressBanner(viewModel.refreshProgress.collectAsStateWithLifecycle().value)

	// Placed once per visit to the tab, not once per refresh. UpdatesScreen survives the state
	// changes now, so LaunchedEffect(Unit) fires only when the tab is opened: it waits for the
	// list to have something in it, then puts focus on the first card. Re-firing after every
	// check would yank focus off the Refresh button the moment the check finished — the other
	// half of what was reported from the TV.
	val firstItemFocus = remember { FocusRequester() }
	val isTv = LocalContext.current.isAndroidTv()
	// Asked at the first Update tap, which is the only moment it makes sense: until now the
	// scheduled-check switch was the ONLY thing that ever requested it, so for most users every
	// notification this app posts was dropped by the system without a word — the confirmation,
	// the success and now the failure alike. The result is ignored on purpose; if the user says
	// no, the in-app messages still work exactly as before.
	val notificationPermission = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission()
	) {}
	LaunchedEffect(Unit) {
		if (!isTv) return@LaunchedEffect
		viewModel.state().first { it is UpdatesUiState.Success && it.updates.isNotEmpty() }
		repeat(3) {
			delay(150)
			if (runCatching { firstItemFocus.requestFocus() }.isSuccess) return@LaunchedEffect
		}
	}

	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		UpdatesScreenLoading()
	}.onError {
		UpdatesScreenError()
	}.onSuccess {
		UpdatesScreenSuccess(
			viewModel, it.updates, onRefresh, firstItemFocus, isTv, notificationPermission
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTopBar(viewModel: UpdatesViewModel) = TopAppBar(
	title = { Text(stringResource(R.string.tab_updates)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	actions = {
		// Recount on entering the tab. The figure used to be refreshed only by an update
		// check, so downloads started from Search left it stale at zero — the chip stayed
		// hidden while there really were tens of megabytes sitting in the cache.
		androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshCacheSize() }
		val cache = viewModel.cacheSize.collectAsStateWithLifecycle().value
		if (cache > 0L) {
			// Also a D-pad stop, and the one that had no focus indication at all.
			val chipInteraction = remember { MutableInteractionSource() }
			val chipFocused by chipInteraction.collectIsFocusedAsState()
			val chipContent = if (chipFocused) MaterialTheme.colorScheme.inverseOnSurface
				else MaterialTheme.colorScheme.onSecondaryContainer
			Row(
				Modifier
					.padding(end = 4.dp)
					.clip(RoundedCornerShape(50))
					.background(
						if (chipFocused) MaterialTheme.colorScheme.inverseSurface
						else MaterialTheme.colorScheme.secondaryContainer
					)
					.clickable(interactionSource = chipInteraction, indication = null) {
						viewModel.clearCache()
					}
					.padding(horizontal = 12.dp, vertical = 6.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Icon(
					painterResource(R.drawable.ic_cleanup),
					stringResource(R.string.clear_cache_cd),
					Modifier.size(16.dp),
					tint = chipContent
				)
				Spacer(Modifier.width(4.dp))
				Text(
					formatBytes(cache),
					style = MaterialTheme.typography.labelMedium,
					color = chipContent
				)
			}
		}
		// The button IS the progress indicator now. It used to sit idle in the corner while a
		// separate spinner turned in the middle of the screen, and there was no way at all to
		// stop a check — which matters, because one slow source holds up the whole list long
		// after the others have answered.
		val checking = viewModel.isChecking.collectAsStateWithLifecycle().value
		val checkProgress = viewModel.checkProgress.collectAsStateWithLifecycle().value
		TvIconButton(
			onClick = { if (checking) viewModel.cancelRefresh() else viewModel.refresh() }
		) {
			if (checking) {
				StopCheckingIcon(stringResource(R.string.stop_checking), checkProgress)
			} else {
				RefreshIcon(stringResource(R.string.refresh_updates))
			}
		}
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Sync, null)
		}
	}
)

@Composable
fun ProgressBanner(text: String?) {
	if (text != null) {
		Text(
			text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
		)
	}
}

@Composable
fun ColumnScope.UpdatesScreenLoading() {
	// No pull-to-refresh at all on this branch, indicator or gesture.
	//
	// The indicator used to reflect a running check, so it sat spinning in the middle of the
	// screen for the whole check — a second indicator on top of the shimmer, while the Refresh
	// button that could have been showing it sat idle in the corner. The button spins now.
	// Keeping the gesture without its indicator was worse than either: a check is already
	// running, so a pull starts nothing the user can see, and each one queued another whole
	// check behind the mutex. The Success branches below keep both, where a pull is the only
	// way to start a check and its indicator is direct feedback for the drag.
	Box(Modifier.weight(1f).fillMaxWidth()) {
		LoadingGrid()
	}
}

@Composable
fun UpdatesScreenError() = DefaultErrorScreen()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.UpdatesScreenSuccess(
	viewModel: UpdatesViewModel,
	updates: List<AppUpdate>,
	onRefresh: () -> Unit = {},
	firstItemFocus: FocusRequester? = null,
	isTv: Boolean = false,
	notificationPermission: ManagedActivityResultLauncher<String, Boolean>? = null
) {
	val handler = LocalUriHandler.current
	val context = LocalContext.current
	// Gesture feedback ONLY: this never reflects that a check is running.
	//
	// Driven by it, this indicator hung in the middle of the screen for the entire check: the
	// very duplicate that was taken off the shimmer branch, just reached by pulling instead of
	// tapping. Pulling keeps working and the indicator still follows the finger; what shows
	// that a check is RUNNING is the button in the corner, and only that.
	val pullState = rememberPullRefreshState(refreshing = false, onRefresh = onRefresh)
	if (updates.isEmpty()) {
		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState)) {
			EmptyGrid()
			PullRefreshIndicator(
				false, pullState,
				Modifier.align(Alignment.TopCenter),
				contentColor = MaterialTheme.colorScheme.primary
			)
		}
	} else {
		val firstId = updates.firstOrNull()?.id
		// Same exclusion as installAll: an ApkMirror update cannot be batch-installed, so it
		// must not be what makes the button appear.
		val pendingUpdates = updates.filter { !it.isInstalled && it.source != ApkMirrorSource }
		val showFab = pendingUpdates.size > 1 && !pendingUpdates.any { it.isInstalling }
		val gridPadding = if (showFab) PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp)
			else PaddingValues(horizontal = 8.dp, vertical = 8.dp)

		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState)) {
			TvInstalledGrid(contentPadding = gridPadding) {
				items(updates, key = { it.id }) { update ->
					TvUpdateItem(
						update,
						{ viewModel.install(update, handler, notificationPermission) },
						{ viewModel.ignoreVersion(update.id) },
						onOpen = { packageName ->
							context.packageManager.getLaunchIntentForPackage(packageName)?.let {
								context.startActivity(it)
							}
						},
						onHide = { viewModel.hideUpdate(it) },
						onSourceClick = if (update.sourceUrl.isNotEmpty()) {{ handler.openUri(update.sourceUrl) }} else null,
						onDownload = { viewModel.downloadToFolder(it) },
						onCancel = { viewModel.userCancelInstall(it) },
						firstItemFocus = if (isTv && update.id == firstId) firstItemFocus else null
					)
				}
			}
			if (showFab) {
				Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
					TooltipBox(
						positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
						tooltip = { PlainTooltip { Text(stringResource(R.string.install_all)) } },
						state = rememberTooltipState()
					) {
						FloatingActionButton(
							onClick = { viewModel.installAll(handler, notificationPermission) },
							containerColor = MaterialTheme.colorScheme.primaryContainer,
							contentColor = MaterialTheme.colorScheme.onPrimaryContainer
						) {
							Icon(painterResource(R.drawable.ic_update_all), contentDescription = stringResource(R.string.install_all))
						}
					}
				}
			}
			PullRefreshIndicator(
				false, pullState,
				Modifier.align(Alignment.TopCenter),
				contentColor = MaterialTheme.colorScheme.primary
			)
		}
	}
}
