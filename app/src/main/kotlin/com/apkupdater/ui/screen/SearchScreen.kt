package com.apkupdater.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkupdater.R
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.SearchUiState
import com.apkupdater.data.ui.Source
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.TvEqualRows
import com.apkupdater.ui.component.TvSearchItem
import com.apkupdater.ui.component.TvIconButton
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.viewmodel.SearchViewModel
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel


@Composable
fun SearchScreen(
	viewModel: SearchViewModel = koinViewModel()
) = Column {
	SearchTopBar(viewModel)
	// Sources answer one at a time, so a filled list does not mean the search is over. This
	// thin bar is the only visible difference between "still searching" and "done" — the
	// shimmer grid only ever showed while the list was still completely empty.
	val searching by viewModel.searching.collectAsStateWithLifecycle()
	if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
	val state = viewModel.state().collectAsStateWithLifecycle().value
	val selectedSources by viewModel.sourceFilter.collectAsStateWithLifecycle()
	// Above the results rather than inside them, so it keeps its own height and the grid keeps
	// the weighted remainder. The row is built from EVERY result, never from the filtered ones
	// — deselecting the last source would otherwise take the chips away with it.
	state.onSuccess {
		SourceFilterRow(it.updates, selectedSources) { name -> viewModel.toggleSourceFilter(name) }
	}
	Box(Modifier.weight(1f).fillMaxWidth()) {
		state.onError {
			DefaultErrorScreen()
		}.onSuccess {
			SearchScreenSuccess(it, viewModel, selectedSources)
		}.onLoading {
			LoadingGrid()
		}
	}
}

@Composable
fun SearchScreenSuccess(
	state: SearchUiState.Success,
	viewModel: SearchViewModel,
	selectedSources: Set<String> = emptySet()
) {
	val uriHandler = LocalUriHandler.current
	val context = LocalContext.current
	// Same as the Updates tab: ask for POST_NOTIFICATIONS the first time the user actually
	// installs something, so the confirmation and result notifications are not dropped.
	val notificationPermission = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission()
	) {}

	// Nothing selected means no filter at all — see SearchViewModel.sourceFilter.
	val shown = remember(state.updates, selectedSources) {
		if (selectedSources.isEmpty()) state.updates
		else state.updates.filter { selectedSources.contains(it.source.name) }
	}

	if (state.updates.isEmpty()) {
		// Three different situations used to show the same "type something to search" hint:
		// nothing asked yet, still asking, and asked but found nothing. Say which it is.
		val searching by viewModel.searching.collectAsStateWithLifecycle()
		val query by viewModel.query.collectAsStateWithLifecycle()
		Box(Modifier.fillMaxSize(), Alignment.Center) {
			if (!searching) {
				Text(
					stringResource(
						if (query.isBlank()) R.string.search_empty else R.string.search_no_results
					),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.padding(horizontal = 32.dp)
				)
			}
		}
		return
	}

	if (shown.isEmpty()) {
		Box(Modifier.fillMaxSize(), Alignment.Center) {
			Text(
				stringResource(R.string.search_filter_none),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(horizontal = 32.dp)
			)
		}
		return
	}

	TvEqualRows(shown, itemKey = { it.id }) { update, cardModifier ->
			TvSearchItem(
				update,
				modifier = cardModifier,
				onInstall = { viewModel.install(update, uriHandler, notificationPermission) },
				onOpen = { packageName ->
					context.packageManager.getLaunchIntentForPackage(packageName)?.let {
						context.startActivity(it)
					}
				},
				onSourceClick = if (update.sourceUrl.isNotEmpty()) {{ uriHandler.openUri(update.sourceUrl) }} else null,
				onDownload = { viewModel.downloadToFolder(it) },
				onCancel = { viewModel.userCancelInstall(it) }
			)
	}
}

/**
 * Lets the user narrow a result list that mixes sources. Shown only when there is something to
 * narrow: one source means one chip, which would be a control that does nothing.
 *
 * Nothing selected means everything is shown, and no chip is lit — see
 * SearchViewModel.sourceFilter for why that way round.
 */
@Composable
fun SourceFilterRow(
	updates: List<AppUpdate>,
	selected: Set<String>,
	onToggle: (String) -> Unit
) {
	// Order follows the results, so the source that answered first sits first and the row does
	// not reshuffle itself as more arrive.
	val sources = remember(updates) { updates.map { it.source }.distinctBy { it.name } }
	if (sources.size < 2) return
	Row(
		Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 12.dp, vertical = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		sources.forEach { source ->
			SourceFilterChip(source, selected.contains(source.name)) { onToggle(source.name) }
		}
	}
}

@Composable
fun SourceFilterChip(source: Source, isSelected: Boolean, onClick: () -> Unit) {
	// Same focus treatment as every other chip in the app since 112: a solid inverseSurface
	// fill, because Material's own state layer is too faint to find from across a room. See
	// the cache chip in UpdatesTopBar.
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	// Named apart from the background/content modifiers on purpose — a local val called
	// `background` next to Modifier.background reads as a shadowing bug even when it is not.
	val chipBackground = when {
		focused -> MaterialTheme.colorScheme.inverseSurface
		isSelected -> MaterialTheme.colorScheme.primaryContainer
		else -> MaterialTheme.colorScheme.surfaceVariant
	}
	val chipContent = when {
		focused -> MaterialTheme.colorScheme.inverseOnSurface
		isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
		else -> MaterialTheme.colorScheme.onSurfaceVariant
	}
	Row(
		Modifier
			.clip(RoundedCornerShape(50))
			.background(chipBackground)
			.clickable(interactionSource = interaction, indication = null) { onClick() }
			.padding(horizontal = 12.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Icon(
			painterResource(source.resourceId),
			contentDescription = null,
			modifier = Modifier.size(16.dp),
			tint = chipContent
		)
		Spacer(Modifier.width(6.dp))
		Text(
			source.name,
			style = MaterialTheme.typography.labelMedium,
			color = chipContent,
			maxLines = 1
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(viewModel: SearchViewModel) = TopAppBar(
	title = { SearchText(viewModel) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	actions = {},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Search, null)
		}
	}
)

@Composable
fun SearchText(viewModel: SearchViewModel) = Box {
	val keyboardController = LocalSoftwareKeyboardController.current
	val focusRequester = remember { FocusRequester() }
	// rememberSaveable, not a StateFlow in the ViewModel: navigation saves and restores this
	// destination's state, so the text survives a trip to another tab — which was the actual
	// complaint — while typing stays synchronous. Round-tripping every keystroke through a
	// StateFlow re-enters composition a frame later and is a known way to drop characters.
	//
	// Seeded from the view model rather than from "". That saved state belongs to the
	// navigation destination, and it survives a TAB SWITCH (which navigates with
	// saveState/restoreState) but NOT the BACK gesture, which pops Search off the stack and
	// discards it. The view model outlives both — it is created up in MainScreen, so the
	// activity owns it — so after a back-out the field came back empty while the results, and
	// the tab badge counting them, were still there. With the field empty there is no clear
	// button and nothing to erase, so that count could not be got rid of at all. Falling back
	// to the last query run puts field, list and badge back into agreement.
	var value by rememberSaveable { mutableStateOf(viewModel.query.value) }
	TextField(
		value = value,
		onValueChange = {
			value = it
			// Erasing the field is the natural way to ask for a clean slate, and under three
			// characters there is nothing to search for — so drop the stale results rather than
			// leave them sitting under an empty field.
			if (it.length < 3) viewModel.clearSearch()
		},
		trailingIcon = {
			if (value.isNotEmpty()) {
				TvIconButton(onClick = { value = ""; viewModel.clearSearch() }) {
					Icon(Icons.Filled.Close, stringResource(R.string.clear_search_cd))
				}
			}
		},
		modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focusRequester),
		placeholder = { Text(stringResource(R.string.tab_search)) },
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
		keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
		colors = TextFieldDefaults.colors(
			focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
			unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
			focusedIndicatorColor = Color.Transparent,
			unfocusedIndicatorColor = Color.Transparent
		),
		shape = RoundedCornerShape(28.dp),
		maxLines = 1,
		singleLine = true
	)
	LaunchedEffect(Unit) {
		focusRequester.requestFocus()
	}
	// Adopted rather than merged: whoever asked for this search wants exactly this text, and
	// the search itself has already been started by searchFor, so the debounce below sees
	// value == query and stays quiet.
	val requested by viewModel.requestedQuery.collectAsStateWithLifecycle()
	LaunchedEffect(requested) {
		requested?.let {
			value = it
			viewModel.consumeRequestedQuery()
		}
	}
	LaunchedEffect(value) {
		// Compare against the last query actually run, otherwise simply returning to the tab
		// would re-fire the same search — nine network requests for a result we already have.
		if (value.length >= 3 && value != viewModel.query.value) {
			delay(1000)
			viewModel.search(value)
		}
	}
}
