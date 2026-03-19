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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
				onDownload = { viewModel.downloadToFolder(it) }
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
			Icon(Icons.Filled.Search, "Tab Icon")
		}
	}
)

@Composable
fun SearchText(viewModel: SearchViewModel) = Box {
	val keyboardController = LocalSoftwareKeyboardController.current
	val focusRequester = remember { FocusRequester() }
	var value by remember { mutableStateOf("") }
	TextField(
		value = value,
		onValueChange = { value = it },
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
		if (value.length >= 3) {
			delay(1000)
			viewModel.search(value)
		}
	}
}
