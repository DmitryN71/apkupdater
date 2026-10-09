package com.apkupdater.ui.screen

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkupdater.R
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.RuStoreSource
import com.apkupdater.data.ui.Source
import com.apkupdater.ui.component.ActionTile
import com.apkupdater.ui.component.IconChip
import com.apkupdater.ui.component.RequestInitialTvFocus
import com.apkupdater.ui.component.SettingsContentRow
import com.apkupdater.ui.component.SettingsGroup
import com.apkupdater.ui.component.SourceActionChip
import com.apkupdater.ui.component.TvIconButton
import com.apkupdater.ui.component.tvFocusFrame
import com.apkupdater.ui.theme.Design
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.viewmodel.SearchViewModel
import com.apkupdater.viewmodel.UpdatesViewModel
import java.text.DateFormat


/**
 * The Home tab (3.10.0): what you can DO — search, check every source or one, put the sources in
 * order, and Play's and RuStore's own actions. What a check FOUND is on the Updates tab.
 *
 * Until 3.10.0 both were the Updates tab, its start screen and its list, with a Home button in its
 * top bar to get from one to the other — a home button inside a tab, which Dmitry rightly called
 * illogical. Split, the list never needs resetting; and Search, which had a tab of its own, became
 * Home's top bar, as in Google Play: four tabs, not five.
 *
 * While a search has something to show — a query has been sent, or one is running — it takes Home
 * over; × or Back clears it and the tiles come back. A check started here goes on in the Updates
 * tab, which [onOpenUpdates] opens.
 */
@Composable
fun HomeScreen(
	updatesViewModel: UpdatesViewModel,
	searchViewModel: SearchViewModel,
	onOpenUpdates: () -> Unit,
	onOpenSourcesSettings: () -> Unit
) = Column {
	val query by searchViewModel.query.collectAsStateWithLifecycle()
	val searching by searchViewModel.searching.collectAsStateWithLifecycle()
	val searchActive = query.isNotEmpty() || searching
	// A search started while the order editor was open hides the editor; it must not come back
	// on its own once the search is cleared.
	LaunchedEffect(searchActive) { if (searchActive) updatesViewModel.setEditingOrder(false) }
	HomeTopBar(updatesViewModel, searchViewModel, searchActive, onOpenSourcesSettings)
	// The same banner as the Updates tab's: "Switching the Google Play account…" while that runs —
	// its button is on Home since 3.10.0, and the line had stayed behind on Updates, so the
	// switch looked like nothing happened for the seconds a sign-in takes (Dmitry) — and the
	// sources still answering while a check runs.
	ProgressBanner(updatesViewModel.refreshProgress.collectAsStateWithLifecycle().value)
	if (searchActive) {
		// Arrived from the Apps tab's "find updates" — the search asked for is already showing.
		// On a TV something must take the D-pad: the field is not focused on arrival here, and
		// the big button that would have claimed it is not drawn while a search shows. The
		// "All sources" chip is the first thing under the field.
		val scopeFocus = remember { FocusRequester() }
		val fromApps = remember { searchViewModel.requestedQuery.value != null }
		RequestInitialTvFocus(scopeFocus, enabled = fromApps)
		SearchContent(searchViewModel, scopeFocus)
	} else {
		// Re-read on every visit, for an order that came in with a config import.
		LaunchedEffect(Unit) { updatesViewModel.reloadSourceOrder() }
		// Collected here and handed down, so that moving a source redraws the tiles at once.
		val sourceOrder = updatesViewModel.sourceOrder.collectAsStateWithLifecycle().value
		val sources = updatesViewModel.enabledSources(sourceOrder)
		// Back leaves the order editor before it leaves the app — while the editor is really on
		// screen: with one source left there are no tiles to order, and none are shown.
		val editingOrder = updatesViewModel.editingOrder.collectAsStateWithLifecycle().value
		BackHandler(enabled = editingOrder && sources.size > 1) { updatesViewModel.setEditingOrder(false) }
		Box(Modifier.weight(1f).fillMaxWidth()) {
			// The check runs in the Updates tab, which is opened at once; on a TV it takes the
			// D-pad there (UpdatesScreen), since the button pressed here leaves with this tab.
			StartScreen(
				updatesViewModel,
				sources,
				onCheck = {
					updatesViewModel.refresh()
					onOpenUpdates()
				},
				onCheckOnly = { source ->
					updatesViewModel.refresh(only = source)
					onOpenUpdates()
				},
				onOpenUpdates = onOpenUpdates
			)
		}
	}
}

/**
 * Home's bar is the search field, as the Search tab's was — one bar rather than a bar with a field
 * under it. Not focused on arrival: Home is opened far more often to check than to search, and a
 * focused field opens the keyboard on a phone and holds the D-pad on a TV.
 *
 * The bar takes the screen's own background rather than the tinted colour of the other tabs' bars,
 * as Google Play's home does: the field's pill stands out against it. On the tinted bar the pill
 * was only a shade apart and Dmitry looked straight past it, twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
	updatesViewModel: UpdatesViewModel,
	searchViewModel: SearchViewModel,
	searchActive: Boolean,
	onOpenSourcesSettings: () -> Unit
) = TopAppBar(
	title = { SearchText(searchViewModel, autoFocus = false) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Home, null)
		}
	},
	actions = { HomeMoreAction(updatesViewModel, searchActive, onOpenSourcesSettings) }
)

/**
 * Home's ⋮: the order of the tiles and a way to Settings › Sources. Checking one source by itself
 * is what the tiles are for; the Updates tab's ⋮ keeps that, with the skipped versions, for when
 * a list is open there.
 */
@Composable
fun HomeMoreAction(
	viewModel: UpdatesViewModel,
	searchActive: Boolean,
	onOpenSourcesSettings: () -> Unit
) {
	var open by remember { mutableStateOf(false) }
	val editingOrder by viewModel.editingOrder.collectAsStateWithLifecycle()
	val sourceOrder by viewModel.sourceOrder.collectAsStateWithLifecycle()
	Box {
		TvIconButton(onClick = { open = true }) {
			Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options_cd))
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			val sources = viewModel.enabledSources(sourceOrder)
			// Not while a search fills Home: the editor would open behind it, unseen.
			if (sources.size > 1 && !editingOrder && !searchActive) {
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
		}
	}
}

/**
 * Under the big button: when the last check ran and how many updates wait — or that a check is
 * running — as a link to the Updates tab, where the list is. Nothing before the first check.
 *
 * The count is the list's, live, not the one the check found: install two of three and it says
 * one. The time is the device's own short format, with the date once it is not today; a check of
 * one source names it, so "nothing waiting" is not read as a claim about all of them.
 */
@Composable
private fun LastCheckLine(viewModel: UpdatesViewModel, onOpenUpdates: () -> Unit) {
	val checking by viewModel.isChecking.collectAsStateWithLifecycle()
	val lastCheck by viewModel.lastCheck.collectAsStateWithLifecycle()
	val state by viewModel.state().collectAsStateWithLifecycle()
	val check = lastCheck
	val text = when {
		checking -> stringResource(R.string.home_checking)
		check != null -> {
			val time = DateUtils.formatSameDayTime(
				check.at, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT
			).toString()
			val at = check.only?.let { "$time (${it.name})" } ?: time
			val waiting = state.updates().count { !it.isInstalled }
			if (waiting > 0) stringResource(R.string.home_last_check, at, waiting)
			else stringResource(R.string.home_last_check_none, at)
		}
		else -> return
	}
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	Text(
		"$text ›",
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.primary,
		textAlign = TextAlign.Center,
		modifier = Modifier
			.padding(top = 4.dp)
			.tvFocusFrame(focused, RoundedCornerShape(8.dp))
			.clickable(interactionSource = interaction, indication = null, onClick = onOpenUpdates)
			.padding(horizontal = 10.dp, vertical = 4.dp)
	)
}

/**
 * Home's body (3.10.0): the big button checks every source, and under it a tile per enabled
 * source checks only that one, in the user's order (⋮ → "Order of sources", which turns this
 * into [SourceOrderEditor]). Under the tiles, Play's "Switch account" and RuStore's "New device".
 * Under the button, [LastCheckLine] says when the last check ran and how much it left to do.
 *
 * It was the Updates tab's start screen from build 177 to 180, shown whenever that tab had no
 * list; a check started here now runs in the Updates tab instead.
 *
 * Columns follow the width: two on a phone, up to five on a TV or a tablet, so ten sources fit a
 * phone without scrolling and take two rows on a TV. It is centred when it fits and scrolls when
 * it does not (a phone in landscape).
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
	onOpenUpdates: () -> Unit
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
				BigCheckButton(stringResource(R.string.refresh_updates), onCheck)
				Spacer(Modifier.height(20.dp))
				Text(
					stringResource(R.string.check_prompt),
					style = MaterialTheme.typography.titleMedium,
					textAlign = TextAlign.Center
				)
				LastCheckLine(viewModel, onOpenUpdates)
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
