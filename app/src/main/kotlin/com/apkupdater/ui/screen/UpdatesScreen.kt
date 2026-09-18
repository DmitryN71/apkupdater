package com.apkupdater.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkupdater.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.apkupdater.util.isAndroidTv
import com.apkupdater.util.launchIntentFor
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.Source
import com.apkupdater.data.ui.UpdatesUiState
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.EmptyGrid
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.RefreshIcon
import com.apkupdater.ui.component.RequestInitialTvFocus
import com.apkupdater.ui.component.StopCheckingIcon
import com.apkupdater.ui.component.TvEqualRows
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
	//
	// It also lets the big Check button hand the D-pad to the top-bar button before it disappears: the
	// prompt is replaced by the shimmer the moment the check starts, and a focused node that is
	// disposed drops focus to the bottom bar (build 137). The top-bar button stays put, turns
	// into the Stop ring, and is exactly where the user wants to be during a check.
	val refreshFocus = remember { FocusRequester() }
	UpdatesTopBar(viewModel, refreshFocus)
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

	val onCheck = {
		if (isTv) runCatching { refreshFocus.requestFocus() }
		viewModel.refresh()
		Unit
	}

	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		UpdatesScreenLoading()
	}.onIdle {
		UpdatesScreenIdle(onRefresh, onCheck, isTv)
	}.onError {
		UpdatesScreenError()
	}.onSuccess {
		UpdatesScreenSuccess(
			viewModel, it.updates, onRefresh, firstItemFocus, isTv, notificationPermission,
			only = it.only, onCheck = onCheck, refreshFocus = refreshFocus
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTopBar(viewModel: UpdatesViewModel, refreshFocus: FocusRequester) = TopAppBar(
	// One line, ellipsised: with the cache chip, Refresh and ⋮ all showing, a narrow phone has
	// little room left, and a two-line title in a one-line bar is cut off mid-word.
	title = { Text(stringResource(R.string.tab_updates), maxLines = 1, overflow = TextOverflow.Ellipsis) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	actions = {
		// Recount on entering the tab. The figure used to be refreshed only by an update
		// check, so downloads started from Search left it stale at zero — the chip stayed
		// hidden while there really were tens of megabytes sitting in the cache.
		androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshCacheSize() }
		val isTv = LocalContext.current.isAndroidTv()
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
						// Clearing zeroes the size and the chip leaves the composition while it holds
						// the D-pad, which drops focus to the bottom bar (the disposed-node trap of
						// build 137). Its right-hand neighbour is the natural place to land.
						if (isTv) runCatching { refreshFocus.requestFocus() }
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
			onClick = { if (checking) viewModel.cancelRefresh() else viewModel.refresh() },
			modifier = Modifier.focusRequester(refreshFocus)
		) {
			if (checking) {
				StopCheckingIcon(stringResource(R.string.stop_checking), checkProgress)
			} else {
				RefreshIcon(stringResource(R.string.refresh_updates))
			}
		}
		UpdatesMoreAction(viewModel, checking)
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Sync, null)
		}
	}
)

/**
 * The ⋮ menu: check one source by itself, and — when Play is switched on — swap its anonymous
 * account for a fresh one.
 *
 * During a check the source items are disabled, never the button. Disabling the button would remove a
 * D-pad stop the moment a check began, and after picking a source from this very menu the focus
 * is on this button — so it would drop to the bottom bar, the disposed-node trap of build 137.
 * Only one check runs at a time, so a source picked mid-check would silently do nothing; greyed
 * out, the item says so instead.
 */
@Composable
fun UpdatesMoreAction(viewModel: UpdatesViewModel, checking: Boolean) {
	var open by remember { mutableStateOf(false) }
	val switching by viewModel.switchingPlayAccount.collectAsStateWithLifecycle()
	Box {
		TvIconButton(onClick = { open = true }) {
			Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options_cd))
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			// Read each time the menu opens, so a source switched on or off in Settings a moment
			// ago is already right. Only the enabled ones: a source is off for a reason.
			val sources = viewModel.enabledSources()
			Text(
				stringResource(R.string.check_only),
				Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			sources.forEach { source ->
				DropdownMenuItem(
					text = { Text(source.name) },
					onClick = {
						open = false
						// Through the shimmer, like the Refresh button. Keeping the list live during
						// the check was tried first and was worse: the result re-sorts the list,
						// and TvEqualRows keys each row by its first card, so one card more or less
						// rebuilds every row below it — disposing whichever card held the D-pad and
						// dropping focus to the bottom bar. And taps on a live card during a check
						// queue behind the check's lock, so Skip or Hide seemed to do nothing.
						viewModel.refresh(only = source)
					},
					leadingIcon = {
						Icon(painterResource(source.resourceId), null, Modifier.size(20.dp))
					},
					enabled = !checking
				)
			}
			if (PlaySource in sources) {
				HorizontalDivider(Modifier.padding(vertical = 4.dp))
				DropdownMenuItem(
					text = { Text(stringResource(R.string.play_switch_account)) },
					onClick = {
						open = false
						viewModel.switchPlayAccount()
					},
					leadingIcon = {
						Icon(painterResource(R.drawable.ic_play), null, Modifier.size(20.dp))
					},
					// Allowed during a check. Sign-ins and session reads share one lock in
					// PlayRepository, so a running check finishes on the session it started with
					// and the next check gets the new account.
					enabled = !switching
				)
			}
		}
	}
}

/**
 * The Updates tab before anything has been checked — checking at launch is off.
 *
 * Pull-to-refresh works here too on a phone, for the same reason it does on an empty list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.UpdatesScreenIdle(onRefresh: () -> Unit, onCheck: () -> Unit, isTv: Boolean) {
	val pullState = rememberPullRefreshState(refreshing = false, onRefresh = onRefresh)
	val pullEnabled = !isTv
	Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState, enabled = pullEnabled)) {
		CheckPrompt(
			title = stringResource(R.string.check_prompt),
			hint = stringResource(R.string.check_prompt_hint),
			onCheck = onCheck
		)
		if (pullEnabled) PullRefreshIndicator(
			false, pullState,
			Modifier.align(Alignment.TopCenter),
			contentColor = MaterialTheme.colorScheme.primary
		)
	}
}

/**
 * A big round Check button in the middle of an empty screen, with a line saying what it is for.
 *
 * It duplicates the top-bar button on purpose. With checking at launch now off by default, the
 * Updates tab opens empty, and a small icon in a corner is not an answer to "why is there
 * nothing here?" — this is. On a TV it takes the D-pad as soon as it appears, so a single OK
 * starts the check.
 *
 * The focus colours are the app's usual TV inversion (see TvIconButton). Surface's own onClick
 * draws the press ripple and the focus layer clipped to the circle, where a clickable on an
 * outer modifier would draw them in a square around it.
 */
@Composable
fun CheckPrompt(title: String, hint: String, onCheck: () -> Unit) = Box(Modifier.fillMaxSize()) {
	// An empty scrollable underneath gives pull-to-refresh something to pull, as in EmptyGrid.
	LazyColumn(Modifier.fillMaxSize()) {}
	val focus = remember { FocusRequester() }
	RequestInitialTvFocus(focus)
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	Column(
		Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Surface(
			onClick = onCheck,
			modifier = Modifier.size(96.dp).focusRequester(focus),
			shape = CircleShape,
			color = if (focused) MaterialTheme.colorScheme.inverseSurface
				else MaterialTheme.colorScheme.primaryContainer,
			contentColor = if (focused) MaterialTheme.colorScheme.inverseOnSurface
				else MaterialTheme.colorScheme.onPrimaryContainer,
			interactionSource = interaction
		) {
			Box(contentAlignment = Alignment.Center) {
				Icon(
					painterResource(R.drawable.ic_refresh),
					contentDescription = title,
					modifier = Modifier.size(44.dp)
				)
			}
		}
		Spacer(Modifier.height(20.dp))
		Text(
			title,
			style = MaterialTheme.typography.titleMedium,
			textAlign = TextAlign.Center
		)
		Spacer(Modifier.height(6.dp))
		Text(
			hint,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center
		)
	}
}

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
	notificationPermission: ManagedActivityResultLauncher<String, Boolean>? = null,
	only: Source? = null,
	onCheck: () -> Unit = {},
	refreshFocus: FocusRequester? = null
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
	// Off on a television, where there is no finger to pull. Left on, the D-pad pulled it: a
	// focus move that has to bring a card into view scrolls the grid, foundation dispatches
	// that scroll as NestedScrollSource.UserInput — the same value the deprecated Drag names,
	// and the only one our nested-scroll connection lets through — and once the grid can go no
	// further the unconsumed remainder lands in onPull. Nothing ever releases it, because a
	// programmatic scroll has no fling and onRelease only runs from onPreFling, so the
	// indicator stayed parked between the cards and twitched with every focus move. Reported
	// by Dmitry from his own TV, in compact mode, where the whole list fits and so EVERY
	// focus move is such a remainder.
	val pullEnabled = !isTv
	if (updates.isEmpty()) {
		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState, enabled = pullEnabled)) {
			// "All up to date" only when every source was asked. After a check of one source it
			// would be a claim about all the sources that were not checked, so the screen names
			// the one that was and offers the full check right there.
			if (only != null) CheckPrompt(
				title = stringResource(R.string.source_no_updates, only.name),
				hint = stringResource(R.string.check_all_prompt),
				onCheck = onCheck
			) else EmptyGrid()
			if (pullEnabled) PullRefreshIndicator(
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

		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState, enabled = pullEnabled)) {
			TvEqualRows(updates, itemKey = { it.id }, contentPadding = gridPadding) { update, cardModifier ->
					TvUpdateItem(
						update,
						modifier = cardModifier,
						{ viewModel.install(update, handler, notificationPermission) },
						{ viewModel.ignoreVersion(update.id) },
						onOpen = { packageName ->
							context.launchIntentFor(packageName)?.let {
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
			if (showFab) {
				Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
					TooltipBox(
						positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
						tooltip = { PlainTooltip { Text(stringResource(R.string.install_all)) } },
						state = rememberTooltipState()
					) {
						FloatingActionButton(
							onClick = {
								// The button hides itself once an install is running, and a focused node
								// that leaves the composition drops the D-pad to the bottom bar. The
								// top-bar button is always there.
								if (isTv) refreshFocus?.let { runCatching { it.requestFocus() } }
								viewModel.installAll(handler, notificationPermission)
							},
							containerColor = MaterialTheme.colorScheme.primaryContainer,
							contentColor = MaterialTheme.colorScheme.onPrimaryContainer
						) {
							// ic_install (a phone taking an arrow), not ic_update_all. Those two
							// drawables were the SAME glyph — an arrow into a tray, Material's
							// download mark — written as two different paths, so "Update all"
							// wore the icon the Download button on every card already wears.
							// Reported on 4PDA by kuwahara, who read the button as a second
							// Download and could not find the update-all action at all.
							Icon(painterResource(R.drawable.ic_install), contentDescription = stringResource(R.string.install_all))
						}
					}
				}
			}
			if (pullEnabled) PullRefreshIndicator(
				false, pullState,
				Modifier.align(Alignment.TopCenter),
				contentColor = MaterialTheme.colorScheme.primary
			)
		}
	}
}
