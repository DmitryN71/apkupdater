package com.apkupdater.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.foundation.lazy.grid.items
import com.apkupdater.R
import com.apkupdater.data.ui.SearchUiState
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvSearchItem
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
	Box(Modifier.weight(1f).fillMaxWidth()) {
		viewModel.state().collectAsStateWithLifecycle().value.onError {
			DefaultErrorScreen()
		}.onSuccess {
			SearchScreenSuccess(it, viewModel)
		}.onLoading {
			LoadingGrid()
		}
	}
}

@Composable
fun SearchScreenSuccess(
	state: SearchUiState.Success,
	viewModel: SearchViewModel
) {
	val uriHandler = LocalUriHandler.current
	val context = LocalContext.current

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

	TvInstalledGrid {
		items(state.updates, key = { it.id }) { update ->
			TvSearchItem(
				update,
				onInstall = { viewModel.install(update, uriHandler) },
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
	var value by rememberSaveable { mutableStateOf("") }
	TextField(
		value = value,
		onValueChange = {
			value = it
			// Erasing the field is the natural way to ask for a clean slate, and under three
			// characters there is nothing to search for — so drop the stale results rather than
			// leave them sitting under an empty field.
			if (it.length < 3) viewModel.clear()
		},
		trailingIcon = {
			if (value.isNotEmpty()) {
				IconButton(onClick = { value = ""; viewModel.clear() }) {
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
	LaunchedEffect(value) {
		// Compare against the last query actually run, otherwise simply returning to the tab
		// would re-fire the same search — nine network requests for a result we already have.
		if (value.length >= 3 && value != viewModel.query.value) {
			delay(1000)
			viewModel.search(value)
		}
	}
}
