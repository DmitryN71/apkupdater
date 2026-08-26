package com.apkupdater.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.apkupdater.util.isAndroidTv
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.EmptyGrid
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.RefreshIcon
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvUpdateItem
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.util.formatBytes
import com.apkupdater.viewmodel.UpdatesViewModel


@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel, isRefreshing: Boolean = false, onRefresh: () -> Unit = {}) {
	val progress = viewModel.refreshProgress.collectAsStateWithLifecycle().value
	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		UpdatesScreenLoading(viewModel, progress, isRefreshing, onRefresh)
	}.onError {
		UpdatesScreenError()
	}.onSuccess {
		UpdatesScreenSuccess(viewModel, it.updates, progress, isRefreshing, onRefresh)
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
			Row(
				Modifier
					.padding(end = 4.dp)
					.clip(RoundedCornerShape(50))
					.background(MaterialTheme.colorScheme.secondaryContainer)
					.clickable { viewModel.clearCache() }
					.padding(horizontal = 12.dp, vertical = 6.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Icon(
					painterResource(R.drawable.ic_cleanup),
					stringResource(R.string.clear_cache_cd),
					Modifier.size(16.dp),
					tint = MaterialTheme.colorScheme.onSecondaryContainer
				)
				Spacer(Modifier.width(4.dp))
				Text(
					formatBytes(cache),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSecondaryContainer
				)
			}
		}
		IconButton(onClick = { viewModel.refresh() }) {
			RefreshIcon(stringResource(R.string.refresh_updates))
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
fun UpdatesScreenLoading(
	viewModel: UpdatesViewModel,
	progressSubtitle: String? = null,
	isRefreshing: Boolean = false,
	onRefresh: () -> Unit = {}
) = Column {
	UpdatesTopBar(viewModel)
	ProgressBanner(progressSubtitle)
	val pullState = rememberPullRefreshState(isRefreshing, onRefresh)
	Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState)) {
		LoadingGrid()
		PullRefreshIndicator(
			isRefreshing, pullState,
			Modifier.align(Alignment.TopCenter),
			contentColor = MaterialTheme.colorScheme.primary
		)
	}
}

@Composable
fun UpdatesScreenError() = DefaultErrorScreen()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreenSuccess(
	viewModel: UpdatesViewModel,
	updates: List<AppUpdate>,
	progressSubtitle: String? = null,
	isRefreshing: Boolean = false,
	onRefresh: () -> Unit = {}
) = Column {
	val handler = LocalUriHandler.current
	val context = LocalContext.current

	UpdatesTopBar(viewModel)
	ProgressBanner(progressSubtitle)

	val pullState = rememberPullRefreshState(isRefreshing, onRefresh)
	if (updates.isEmpty()) {
		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState)) {
			EmptyGrid()
			PullRefreshIndicator(
				isRefreshing, pullState,
				Modifier.align(Alignment.TopCenter),
				contentColor = MaterialTheme.colorScheme.primary
			)
		}
	} else {
		// On TV nothing in the grid claimed focus, so it settled on the bottom navigation bar:
		// pressing DOWN did nothing (it is already the bottom) and pressing UP ran a geometric
		// search that landed on the LAST visible card and yanked the whole list with it. Put
		// focus on the first card's Update button instead — the button, not the action row,
		// because a row focusGroup is entered at its leftmost child, which is Skip.
		val isTv = context.isAndroidTv()
		val firstItemFocus = remember { FocusRequester() }
		val firstId = updates.firstOrNull()?.id
		// Keyed on "the list became non-empty", NOT on which app is first: a refresh that
		// reorders the list must not yank focus away from wherever the user put it. And always
		// wait first — the grid subcomposes its items during layout, which happens after this
		// effect starts, so an immediate request would either throw or, worse, succeed against
		// the previous frame's item and then lose focus when that item is recycled.
		LaunchedEffect(isTv, updates.isNotEmpty()) {
			if (isTv && updates.isNotEmpty()) {
				repeat(3) {
					delay(150)
					if (runCatching { firstItemFocus.requestFocus() }.isSuccess) return@LaunchedEffect
				}
			}
		}
		val pendingUpdates = updates.filter { !it.isInstalled }
		val showFab = pendingUpdates.size > 1 && !pendingUpdates.any { it.isInstalling }
		val gridPadding = if (showFab) PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp)
			else PaddingValues(horizontal = 8.dp, vertical = 8.dp)

		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState)) {
			TvInstalledGrid(contentPadding = gridPadding) {
				items(updates, key = { it.id }) { update ->
					TvUpdateItem(
						update,
						{ viewModel.install(update, handler) },
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
							onClick = { viewModel.installAll(handler) },
							containerColor = MaterialTheme.colorScheme.primaryContainer,
							contentColor = MaterialTheme.colorScheme.onPrimaryContainer
						) {
							Icon(painterResource(R.drawable.ic_update_all), contentDescription = stringResource(R.string.install_all))
						}
					}
				}
			}
			PullRefreshIndicator(
				isRefreshing, pullState,
				Modifier.align(Alignment.TopCenter),
				contentColor = MaterialTheme.colorScheme.primary
			)
		}
	}
}
