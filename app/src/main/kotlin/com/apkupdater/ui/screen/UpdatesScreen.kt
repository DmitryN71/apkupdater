package com.apkupdater.ui.screen

import androidx.compose.foundation.BorderStroke
import com.apkupdater.ui.component.TvFocus
import com.apkupdater.ui.component.tvFocusFrame
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.apkupdater.ui.component.ActionTile
import com.apkupdater.ui.component.SettingsContentRow
import com.apkupdater.ui.component.SettingsGroup
import com.apkupdater.ui.component.IconChip
import com.apkupdater.ui.component.SourceActionChip
import com.apkupdater.ui.theme.Design
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.RuStoreSource
import com.apkupdater.data.ui.Source
import com.apkupdater.data.ui.UpdatesUiState
import com.apkupdater.ui.component.DefaultErrorScreen
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
fun UpdatesScreen(
	viewModel: UpdatesViewModel,
	onRefresh: () -> Unit = {},
	onOpenSourcesSettings: () -> Unit = {}
) = Column {
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
	UpdatesTopBar(viewModel, refreshFocus, onOpenSourcesSettings)
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
	// The start screen's source tiles. Same hand-over of the D-pad as onCheck: the tile pressed
	// is about to be replaced by the shimmer, and a disposed focused node drops focus to the
	// bottom bar.
	val onCheckOnly = { source: Source ->
		if (isTv) runCatching { refreshFocus.requestFocus() }
		viewModel.refresh(only = source)
		Unit
	}

	val showSkipped = viewModel.showSkipped.collectAsStateWithLifecycle().value
	// Read here, and handed down, so that moving a source redraws the tiles at once. Reloaded on
	// each visit, for an order that came in with a config import.
	LaunchedEffect(Unit) { viewModel.reloadSourceOrder() }
	val sourceOrder = viewModel.sourceOrder.collectAsStateWithLifecycle().value
	// Back leaves the order editor before it leaves the app — while the editor is really on
	// screen: with one source left there are no tiles to order, and StartScreen shows none.
	val editingOrder = viewModel.editingOrder.collectAsStateWithLifecycle().value
	BackHandler(enabled = editingOrder && viewModel.enabledSources(sourceOrder).size > 1) {
		viewModel.setEditingOrder(false)
	}
	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		UpdatesScreenLoading()
	}.onIdle {
		// Read each time, like the ⋮ menu does: a source switched on or off in Settings a
		// moment ago is already right when the tab is opened again.
		UpdatesScreenIdle(viewModel, onRefresh, onCheck, onCheckOnly, viewModel.enabledSources(sourceOrder), isTv)
	}.onError {
		UpdatesScreenError()
	}.onSuccess {
		UpdatesScreenSuccess(
			viewModel, it.updates, onRefresh, firstItemFocus, isTv, notificationPermission,
			only = it.only, onCheck = onCheck, onCheckOnly = onCheckOnly, refreshFocus = refreshFocus,
			skipped = if (showSkipped) it.skipped else emptyList(),
			sources = viewModel.enabledSources(sourceOrder)
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTopBar(
	viewModel: UpdatesViewModel,
	refreshFocus: FocusRequester,
	onOpenSourcesSettings: () -> Unit = {}
) = TopAppBar(
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
			val chipContent = if (chipFocused) MaterialTheme.colorScheme.onSurface
				else MaterialTheme.colorScheme.onSecondaryContainer
			Row(
				Modifier
					.padding(end = 4.dp)
					.clip(RoundedCornerShape(50))
					.background(MaterialTheme.colorScheme.secondaryContainer)
					.tvFocusFrame(chipFocused, RoundedCornerShape(50))
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
		UpdatesMoreAction(viewModel, checking, onOpenSourcesSettings)
	},
	navigationIcon = {
		// Home, where the app's own icon sits, whenever there is a list to leave: one tap back
		// to the start screen (UpdatesViewModel.goHome). Not while a check runs or a card is
		// downloading or installing — goHome refuses then, and a button that does nothing is
		// worse than none.
		val state = viewModel.state().collectAsStateWithLifecycle().value
		val checking = viewModel.isChecking.collectAsStateWithLifecycle().value
		val showSkipped = viewModel.showSkipped.collectAsStateWithLifecycle().value
		val canGoHome = !checking && state is UpdatesUiState.Success &&
			(state.updates.isNotEmpty() || (showSkipped && state.skipped.isNotEmpty())) &&
			state.updates.none { it.isInstalling }
		if (canGoHome) {
			val isTv = LocalContext.current.isAndroidTv()
			TvIconButton(onClick = {
				// This button leaves the composition as the start screen comes in, and a focused
				// node that goes drops the D-pad to the bottom bar (build 137). Its neighbour
				// across the bar takes it first; the big Check button then claims it, as it does
				// whenever the start screen appears.
				if (isTv) runCatching { refreshFocus.requestFocus() }
				viewModel.goHome()
			}) {
				Icon(Icons.Outlined.Home, stringResource(R.string.go_home_cd))
			}
		} else {
			Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
				Icon(Icons.Filled.Sync, null)
			}
		}
	}
)

/**
 * The ⋮ menu. With a list on screen: check one source by itself, Play's and RuStore's own actions,
 * and the skipped versions. On the start screen, whose tiles already offer all that (build 180):
 * the order of the tiles, a way to Settings › Sources, and the skipped versions.
 *
 * During a check the source items are disabled, never the button. Disabling the button would remove a
 * D-pad stop the moment a check began, and after picking a source from this very menu the focus
 * is on this button — so it would drop to the bottom bar, the disposed-node trap of build 137.
 * Only one check runs at a time, so a source picked mid-check would silently do nothing; greyed
 * out, the item says so instead.
 */
@Composable
fun UpdatesMoreAction(
	viewModel: UpdatesViewModel,
	checking: Boolean,
	onOpenSourcesSettings: () -> Unit = {}
) {
	var open by remember { mutableStateOf(false) }
	val switching by viewModel.switchingPlayAccount.collectAsStateWithLifecycle()
	val state = viewModel.state().collectAsStateWithLifecycle().value
	val skippedCount = state.skipped().size
	val showSkipped by viewModel.showSkipped.collectAsStateWithLifecycle()
	val editingOrder by viewModel.editingOrder.collectAsStateWithLifecycle()
	val sourceOrder by viewModel.sourceOrder.collectAsStateWithLifecycle()
	// The start screen is up, and its tiles already offer every source and Play's and RuStore's
	// actions — so the menu does not repeat them there (build 180). With a list on screen the
	// tiles are gone and the menu is the only way to them, so there it stays as it was.
	val onStartScreen = state is UpdatesUiState.Idle || (state is UpdatesUiState.Success &&
		state.updates.isEmpty() && !(showSkipped && state.skipped.isNotEmpty()))
	Box {
		TvIconButton(onClick = { open = true }) {
			Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options_cd))
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			// Read each time the menu opens, so a source switched on or off in Settings a moment
			// ago is already right. Only the enabled ones: a source is off for a reason.
			val sources = viewModel.enabledSources(sourceOrder)
			if (onStartScreen) {
				if (sources.size > 1 && !editingOrder) {
					DropdownMenuItem(
						text = { Text(stringResource(R.string.source_order)) },
						onClick = {
							open = false
							viewModel.setEditingOrder(true)
						},
						leadingIcon = { Icon(Icons.Outlined.SwapVert, null, Modifier.size(20.dp)) }
					)
				}
				DropdownMenuItem(
					text = { Text(stringResource(R.string.sources_settings)) },
					onClick = {
						open = false
						onOpenSourcesSettings()
					},
					leadingIcon = { Icon(Icons.Outlined.Settings, null, Modifier.size(20.dp)) }
				)
			} else {
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
			}
			// Play's and RuStore's own actions, wherever there are no tiles to carry them: with a
			// list on screen, and on a start screen of one source, which shows no tiles at all.
			if (!(onStartScreen && sources.size > 1)) {
				if (PlaySource in sources || RuStoreSource in sources) {
					HorizontalDivider(Modifier.padding(vertical = 4.dp))
				}
				if (RuStoreSource in sources) {
					DropdownMenuItem(
						text = { Text(stringResource(R.string.rustore_switch_device)) },
						onClick = {
							open = false
							viewModel.switchRuStoreDevice()
						},
						leadingIcon = {
							Icon(painterResource(R.drawable.ic_rustore), null, Modifier.size(20.dp))
						},
						// Nothing disables it: there is no network call and no limit behind it, and
						// the new device is only introduced on the NEXT check anyway.
					)
				}
				if (PlaySource in sources) {
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
			// Only when the last check found something skipped: the skip list itself holds
			// hashes, not names, so there is nothing to show before a check has met the cards.
			if (skippedCount > 0) {
				HorizontalDivider(Modifier.padding(vertical = 4.dp))
				DropdownMenuItem(
					text = {
						Text(
							if (showSkipped) stringResource(R.string.hide_skipped)
							else stringResource(R.string.show_skipped, skippedCount)
						)
					},
					onClick = {
						open = false
						viewModel.toggleShowSkipped()
					},
					leadingIcon = {
						Icon(
							if (showSkipped) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
							null,
							Modifier.size(20.dp)
						)
					}
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
fun ColumnScope.UpdatesScreenIdle(
	viewModel: UpdatesViewModel,
	onRefresh: () -> Unit,
	onCheck: () -> Unit,
	onCheckOnly: (Source) -> Unit,
	sources: List<Source>,
	isTv: Boolean
) {
	val pullState = rememberPullRefreshState(refreshing = false, onRefresh = onRefresh)
	// Not inside the order editor either: a pull there started a full check and closed it.
	val editingOrder by viewModel.editingOrder.collectAsStateWithLifecycle()
	val pullEnabled = !isTv && !editingOrder
	Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState, enabled = pullEnabled)) {
		StartScreen(viewModel, sources, onCheck, onCheckOnly)
		if (pullEnabled) PullRefreshIndicator(
			false, pullState,
			Modifier.align(Alignment.TopCenter),
			contentColor = MaterialTheme.colorScheme.primary
		)
	}
}

/**
 * The big round Check button in the middle of [StartScreen].
 *
 * It duplicates the top-bar button on purpose. With checking at launch off by default, the
 * Updates tab opens empty, and a small icon in a corner is not an answer to "why is there
 * nothing here?" — this is. On a TV it takes the D-pad as soon as it appears, so a single OK
 * starts the check.
 *
 * Focus keeps the button's own colour and adds the app's TvFocus frame, on Surface's own border
 * so it follows the circle. Surface's own onClick draws the press ripple and the focus layer
 * clipped to the circle, where a clickable on an outer modifier would draw them in a square
 * around it.
 */
@Composable
fun BigCheckButton(contentDescription: String, onCheck: () -> Unit) {
	val focus = remember { FocusRequester() }
	RequestInitialTvFocus(focus)
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	Surface(
		onClick = onCheck,
		modifier = Modifier.size(96.dp).focusRequester(focus),
		shape = CircleShape,
		color = MaterialTheme.colorScheme.primaryContainer,
		contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
		// Thicker than the usual frame: it goes round a 96 dp circle, alone on the screen.
		border = if (focused) BorderStroke(3.dp, TvFocus.frame) else null,
		interactionSource = interaction
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(
				painterResource(R.drawable.ic_refresh),
				contentDescription = contentDescription,
				modifier = Modifier.size(44.dp)
			)
		}
	}
}

/**
 * The Updates tab whenever there is no list to show (build 177, the first screen of the
 * redesign — see ui/theme/Design): the big button checks every source, and under it a tile per
 * enabled source checks only that one. That used to live in the ⋮ menu alone, where few people
 * found it.
 *
 * Before the first check [title] asks for one. After a check that found nothing it says so —
 * "GitHub: no updates", or "All apps are up to date!" — and [hint] says what the big button does
 * now. The screen stays the same otherwise, so the tiles are still there after a check of one
 * source; with only the old prompt there, the way back to them was to swipe the app away.
 *
 * The tiles come in the user's order (build 180, ⋮ → "Order of sources", which turns this screen
 * into [SourceOrderEditor]). Under them, a row with Play's "Switch account" and RuStore's "New
 * device", which until then lived only in the ⋮ menu.
 *
 * Columns follow the width: two on a phone, up to five on a TV or a tablet, so ten sources fit
 * a phone without scrolling and take two rows on a TV. It is centred when it fits and scrolls
 * when it does not (a phone in landscape); the scroll also gives pull-to-refresh something to
 * pull, which an empty LazyColumn did before.
 *
 * With one source or none the tiles would only repeat the big button, so they are left out.
 * D-pad: the big button has focus first, DOWN enters the tiles, and the grid is plain rows, so
 * the geometric search moves through it the way it looks; DOWN from the last row reaches the
 * actions under it.
 */
@Composable
fun StartScreen(
	viewModel: UpdatesViewModel,
	sources: List<Source>,
	onCheck: () -> Unit,
	onCheckOnly: (Source) -> Unit,
	title: String = stringResource(R.string.check_prompt),
	hint: String? = null
) = BoxWithConstraints(Modifier.fillMaxSize()) {
	val editing = viewModel.editingOrder.collectAsStateWithLifecycle().value && sources.size > 1
	val columns = ((maxWidth - Design.ScreenPadding * 2) / (Design.TileMinWidth + Design.Gap))
		.toInt()
		.coerceIn(2, 5)
	val screenHeight = maxHeight
	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Column(
			Modifier
				.widthIn(max = Design.ContentMaxWidth)
				.fillMaxWidth()
				.heightIn(min = screenHeight)
				.padding(Design.ScreenPadding),
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally
		) {
			if (editing) {
				SourceOrderEditor(viewModel, sources)
			} else {
				// Named for what it does, not by the title: after a check the title is a result.
				BigCheckButton(stringResource(R.string.refresh_updates), onCheck)
				Spacer(Modifier.height(20.dp))
				Text(
					title,
					style = MaterialTheme.typography.titleMedium,
					textAlign = TextAlign.Center
				)
				if (hint != null) {
					Spacer(Modifier.height(6.dp))
					Text(
						hint,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Center
					)
				}
				if (sources.size > 1) {
					Spacer(Modifier.height(6.dp))
					Text(
						stringResource(R.string.check_prompt_hint),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Center
					)
					Spacer(Modifier.height(20.dp))
					sources.chunked(columns).forEach { row ->
						Row(
							Modifier.fillMaxWidth().padding(bottom = Design.Gap),
							horizontalArrangement = Arrangement.spacedBy(Design.Gap)
						) {
							row.forEach { source ->
								ActionTile(
									icon = source.resourceId,
									label = source.name,
									onClick = { onCheckOnly(source) },
									modifier = Modifier.weight(1f)
								)
							}
							// Keeps a short last row in the same columns as the rows above it.
							repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
						}
					}
					// Play's and RuStore's own actions, in one quiet row under the grid, in the
					// order their sources have. They first sat under their tiles, which left a hole
					// beside each and broke the grid apart whenever the two were not neighbours
					// (Dmitry, on his phone and TV) — so here the source's icon says whose action
					// it is, and the grid stays whole. Guarded in the ViewModel: a second press while
					// an account switch is under way is ignored, and its progress shows in the banner.
					val withActions = sources.filter { it == PlaySource || it == RuStoreSource }
					if (withActions.isNotEmpty()) {
						Row(
							Modifier.fillMaxWidth().padding(top = 8.dp),
							horizontalArrangement = Arrangement.spacedBy(Design.Gap, Alignment.CenterHorizontally)
						) {
							withActions.forEach { source ->
								if (source == PlaySource) {
									SourceActionChip(
										source.resourceId,
										stringResource(R.string.play_switch_account_short),
										onClick = { viewModel.switchPlayAccount() }
									)
								} else {
									SourceActionChip(
										source.resourceId,
										stringResource(R.string.rustore_switch_device_short),
										onClick = { viewModel.switchRuStoreDevice() }
									)
								}
							}
						}
					}
				}
			}
		}
	}
}

/**
 * The start screen while the user puts the sources in order (⋮ → "Order of sources", build 180):
 * one row per enabled source with an up and a down arrow, then "A–Z" and "Done". The tiles follow
 * this list left to right, row by row.
 *
 * A list rather than the grid with sideways arrows of the first sketch, for the TV. The rows are
 * a SettingsGroup, which composes each under its source's key in a single column, so a moved row
 * keeps its node — and the D-pad stays on the arrow just pressed, moving with it. In the grid a
 * tile that changed rows was a different node, and the focus fell to the bottom bar each time.
 * Arrows rather than dragging, because a remote cannot drag.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceOrderEditor(viewModel: UpdatesViewModel, sources: List<Source>) {
	val firstFocus = remember { FocusRequester() }
	RequestInitialTvFocus(firstFocus)
	Text(
		stringResource(R.string.source_order),
		style = MaterialTheme.typography.titleMedium,
		textAlign = TextAlign.Center
	)
	Spacer(Modifier.height(6.dp))
	Text(
		stringResource(R.string.source_order_hint),
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		textAlign = TextAlign.Center
	)
	Spacer(Modifier.height(16.dp))
	SettingsGroup(Modifier.widthIn(max = 520.dp)) {
		sources.forEachIndexed { index, source ->
			row(source.name) {
				// The D-pad stays on the arrow as its row moves, but Compose scrolls a control into
				// view only when it GAINS focus — so on a TV, a few presses of "down" walked the
				// focused row off the bottom of the screen. Each move brings it back into view.
				val bring = remember { BringIntoViewRequester() }
				var holdsFocus by remember { mutableStateOf(false) }
				LaunchedEffect(index) {
					if (holdsFocus) {
						withFrameNanos { }
						bring.bringIntoView()
					}
				}
				Box(Modifier.bringIntoViewRequester(bring).onFocusChanged { holdsFocus = it.hasFocus }) {
					SettingsContentRow {
						Row(verticalAlignment = Alignment.CenterVertically) {
							IconChip(source.resourceId)
							Spacer(Modifier.width(14.dp))
							Text(source.name, Modifier.weight(1f))
							// Both arrows always there and always enabled, the end ones doing nothing:
							// an arrow that vanished or greyed out under the D-pad would drop it.
							TvIconButton(onClick = { viewModel.moveSource(source, -1) }) {
								Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up_cd))
							}
							TvIconButton(
								onClick = { viewModel.moveSource(source, 1) },
								modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
							) {
								Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down_cd))
							}
						}
					}
				}
			}
		}
	}
	Spacer(Modifier.height(16.dp))
	Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
		EditorButton(stringResource(R.string.source_order_az), filled = false) { viewModel.sortSourcesByName() }
		EditorButton(stringResource(R.string.done), filled = true) { viewModel.setEditingOrder(false) }
	}
}

/**
 * The editor's two buttons, with a focus a TV viewer can see: filled with the accent colour, as
 * the card buttons are — a plain Material button marks focus with a faint tint.
 */
@Composable
private fun EditorButton(text: String, filled: Boolean, onClick: () -> Unit) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	FilledTonalButton(
		onClick = onClick,
		interactionSource = interaction,
		colors = ButtonDefaults.filledTonalButtonColors(
			containerColor = when {
				focused -> MaterialTheme.colorScheme.primary
				filled -> MaterialTheme.colorScheme.primaryContainer
				else -> Color.Transparent
			},
			contentColor = when {
				focused -> MaterialTheme.colorScheme.onPrimary
				filled -> MaterialTheme.colorScheme.onPrimaryContainer
				else -> MaterialTheme.colorScheme.primary
			}
		),
		border = if (!filled && !focused) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
	) {
		Text(text)
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
	onCheckOnly: (Source) -> Unit = {},
	refreshFocus: FocusRequester? = null,
	/** Drawn after [updates], dimmed, with Unskip as their one button. Empty unless ⋮ → "Show skipped". */
	skipped: List<AppUpdate> = emptyList(),
	/** The start screen's tiles, in the user's order — a parameter so a reorder reaches it. */
	sources: List<Source> = emptyList()
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
	if (updates.isEmpty() && skipped.isEmpty()) {
		val editingOrder by viewModel.editingOrder.collectAsStateWithLifecycle()
		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState, enabled = pullEnabled && !editingOrder)) {
			// The start screen again, headed by the result: nothing to list means nothing in the
			// way of the big button and the tiles. "All up to date" only when every source was
			// asked. After a check of one source it would be a claim about all the sources that
			// were not checked, so the title names the one that was.
			StartScreen(
				viewModel = viewModel,
				sources = sources,
				onCheck = onCheck,
				onCheckOnly = onCheckOnly,
				title = if (only != null) stringResource(R.string.source_no_updates, only.name)
					else stringResource(R.string.all_up_to_date),
				hint = stringResource(R.string.check_all_prompt)
			)
			if (pullEnabled) PullRefreshIndicator(
				false, pullState,
				Modifier.align(Alignment.TopCenter),
				contentColor = MaterialTheme.colorScheme.primary
			)
		}
	} else {
		val firstId = updates.firstOrNull()?.id
		// Same exclusion as installAll: an ApkMirror update cannot be batch-installed unless the
		// user switched on in-app downloads, so otherwise it must not make the button appear.
		val pendingUpdates = updates.filter { !it.isInstalled && viewModel.isBatchInstallable(it) }
		val showFab = pendingUpdates.size > 1 && !pendingUpdates.any { it.isInstalling }
		val gridPadding = if (showFab) PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp)
			else PaddingValues(horizontal = 8.dp, vertical = 8.dp)

		Box(Modifier.weight(1f).fillMaxWidth().pullRefresh(pullState, enabled = pullEnabled)) {
			// The skipped cards last, so the ones that need doing keep their places at the top.
			val skippedIds = skipped.mapTo(HashSet()) { it.id }
			TvEqualRows(updates + skipped, itemKey = { it.id }, contentPadding = gridPadding) { update, cardModifier ->
					val isSkipped = update.id in skippedIds
					TvUpdateItem(
						update,
						modifier = cardModifier,
						{ viewModel.install(update, handler, notificationPermission) },
						{
							// Skip and Unskip both move the card to the other pile, which TvEqualRows
							// builds as another row: the focused button is disposed and the D-pad
							// falls to the bottom bar (build 137). The top bar is always there.
							if (isTv) refreshFocus?.let { runCatching { it.requestFocus() } }
							viewModel.ignoreVersion(update.id, skip = !isSkipped)
						},
						onOpen = { packageName ->
							context.launchIntentFor(packageName)?.let {
								context.startActivity(it)
							}
						},
						onHide = { viewModel.hideUpdate(it) },
						onSourceClick = if (update.sourceUrl.isNotEmpty()) {{ handler.openUri(update.sourceUrl) }} else null,
						onDownload = { viewModel.downloadToFolder(it) },
						onCancel = { viewModel.userCancelInstall(it) },
						firstItemFocus = if (isTv && update.id == firstId) firstItemFocus else null,
						skipped = isSkipped
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
