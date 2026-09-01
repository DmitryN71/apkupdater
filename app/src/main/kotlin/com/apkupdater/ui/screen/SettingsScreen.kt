package com.apkupdater.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkupdater.BuildConfig
import com.apkupdater.R
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.LaunchedEffect
import com.apkupdater.data.github.GitProvider
import com.apkupdater.data.ui.GitHubSource
import android.widget.Toast
import com.apkupdater.data.ui.SettingsUiState
import com.apkupdater.ui.component.ButtonSetting
import com.apkupdater.ui.component.DropDownSetting
import com.apkupdater.ui.component.LargeTitle
import com.apkupdater.ui.component.LoadingImageApp
import com.apkupdater.ui.component.SectionHeader
import com.apkupdater.ui.component.SettingsCategory
import com.apkupdater.ui.component.RequestInitialTvFocus
import com.apkupdater.ui.component.MediumText
import com.apkupdater.ui.component.MediumTitle
import com.apkupdater.ui.component.SegmentedButtonSetting
import com.apkupdater.ui.component.SliderSetting
import com.apkupdater.ui.component.SourceIcon
import com.apkupdater.ui.component.SwitchSetting
import com.apkupdater.ui.component.TvIconButton
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.util.isAndroidTv
import com.apkupdater.viewmodel.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import java.util.Calendar


@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) = Column {
	val state = viewModel.state.collectAsStateWithLifecycle().value
	// In a sub-screen (About / Custom repos), the system back gesture should
	// return to the main settings list, not pop the whole tab.
	BackHandler(enabled = state != SettingsUiState.Settings) {
		viewModel.setSettings()
	}
	when (state) {
		SettingsUiState.Settings -> {
			SettingsTopBar(viewModel)
			Settings(viewModel)
		}
		SettingsUiState.Sources -> {
			SubSettingsTopBar(stringResource(R.string.settings_sources), viewModel)
			SourcesSettings(viewModel)
		}
		SettingsUiState.Updates -> {
			SubSettingsTopBar(stringResource(R.string.settings_updates), viewModel)
			UpdatesSettings(viewModel)
		}
		SettingsUiState.Install -> {
			SubSettingsTopBar(stringResource(R.string.settings_install), viewModel)
			InstallSettings(viewModel)
		}
		SettingsUiState.Appearance -> {
			SubSettingsTopBar(stringResource(R.string.settings_ui), viewModel)
			AppearanceSettings(viewModel)
		}
		SettingsUiState.Tools -> {
			SubSettingsTopBar(stringResource(R.string.settings_utils), viewModel)
			ToolsSettings(viewModel)
		}
		SettingsUiState.CustomRepos -> {
			CustomReposTopBar(viewModel)
			CustomRepos(viewModel)
		}
		else -> {
			AboutTopBar(viewModel)
			About()
		}
	}
}

@Composable
fun About() = LazyColumn(
	Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
	item {
		Column(Modifier.padding(vertical = 16.dp)) {
			LoadingImageApp(BuildConfig.APPLICATION_ID)
			LargeTitle(stringResource(R.string.app_name), Modifier.align(CenterHorizontally))
			MediumText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.align(CenterHorizontally))
			MediumText("Based on APKUpdater by rumboalla", Modifier.align(CenterHorizontally))
			MediumText("Forked by Dmitry_N", Modifier.align(CenterHorizontally))
		}
	}
	item {
		AboutItem(
			"GitHub - APKUpdater",
			stringResource(R.string.about_github),
			"https://github.com/DmitryN71/apkupdater",
			{ SourceIcon(GitHubSource, Modifier.size(64.dp).align(CenterVertically)) }
		)
	}
}


@Composable
fun AboutItem(
	title: String,
	body: String,
	link: String,
	icon: @Composable RowScope.() -> Unit,
	handler: UriHandler = LocalUriHandler.current
) = OutlinedCard(
	Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { handler.openUri(link) }) {
	Row(Modifier.padding(8.dp)) {
		icon()
		Column(Modifier.padding(start = 16.dp)) {
			MediumTitle(title)
			MediumText(body, maxLines = 2)
		}
	}
}

@Composable
fun Settings(viewModel: SettingsViewModel) = LazyColumn {
	item {
		// Otherwise opening this tab leaves focus on the bottom bar: DOWN does nothing and UP
		// jumps to the LAST row. Same fix Updates got in 130.
		val firstRowFocus = remember { FocusRequester() }
		RequestInitialTvFocus(firstRowFocus)
		SettingsCategory(
			stringResource(R.string.settings_sources),
			stringResource(
				R.string.settings_sources_summary,
				viewModel.getEnabledSourceCount(),
				viewModel.getSourceCount()
			),
			R.drawable.ic_appstore,
			modifier = Modifier.focusRequester(firstRowFocus)
		) { viewModel.setSources() }
		SettingsCategory(
			stringResource(R.string.settings_custom_repos),
			stringResource(R.string.settings_repos_summary, viewModel.getCustomGitRepos().size),
			R.drawable.ic_github
		) { viewModel.setCustomRepos() }
		SettingsCategory(
			stringResource(R.string.settings_updates),
			if (viewModel.getEnableAlarm()) stringResource(
				when (viewModel.getAlarmFrequency()) {
					1 -> R.string.settings_alarm_3day
					2 -> R.string.settings_alarm_weekly
					else -> R.string.settings_alarm_daily
				}
			) else stringResource(R.string.settings_alarm_off),
			R.drawable.ic_alarm
		) { viewModel.setUpdates() }
		SettingsCategory(
			stringResource(R.string.settings_install),
			// The first thing we ask a reporter whenever an install misbehaves — the three
			// install paths fail in completely different ways. Now it is on the front page.
			stringResource(
				when {
					viewModel.getRootInstall() -> R.string.root_install
					viewModel.getShizukuInstall() -> R.string.shizuku_install
					else -> R.string.install_standard
				}
			),
			R.drawable.ic_install
		) { viewModel.setInstall() }
		SettingsCategory(
			stringResource(R.string.settings_ui),
			stringResource(
				when (viewModel.getTheme()) {
					1 -> R.string.theme_dark
					2 -> R.string.theme_light
					else -> R.string.theme_system
				}
			),
			R.drawable.ic_theme
		) { viewModel.setAppearance() }
		SettingsCategory(stringResource(R.string.settings_utils), null, R.drawable.ic_export) {
			viewModel.setTools()
		}
		SettingsCategory(stringResource(R.string.about), null, R.drawable.ic_info) {
			viewModel.setAbout()
		}
	}
}

@Composable
fun SourcesSettings(viewModel: SettingsViewModel) = LazyColumn {
	item {
		SwitchSetting(
			{ viewModel.getUseGitHub() },
			{ viewModel.setUseGitHub(it) },
			stringResource(R.string.source_github),
			R.drawable.ic_github
		)
		var githubToken by remember { mutableStateOf(viewModel.getGitHubToken()) }
		val isTv = LocalContext.current.isAndroidTv()
		var githubTokenEditing by remember { mutableStateOf(false) }
		OutlinedTextField(
			value = githubToken,
			onValueChange = { githubToken = it; viewModel.setGitHubToken(it) },
			label = { Text(stringResource(R.string.github_token)) },
			placeholder = { Text(stringResource(R.string.github_token_hint)) },
			supportingText = { Text(stringResource(R.string.github_token_help)) },
			singleLine = true,
			readOnly = isTv && !githubTokenEditing,
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
				.then(if (isTv) Modifier.clickable { githubTokenEditing = true } else Modifier)
		)
		SwitchSetting(
			{ viewModel.getUseGitLab() },
			{ viewModel.setUseGitLab(it) },
			stringResource(R.string.source_gitlab),
			R.drawable.ic_gitlab
		)
		SwitchSetting(
			{ viewModel.getUseApkMirror() },
			{ viewModel.setUseApkMirror(it) },
			stringResource(R.string.source_apkmirror),
			R.drawable.ic_apkmirror
		)
		SwitchSetting(
			{ viewModel.getUseFdroid() },
			{ viewModel.setUseFdroid(it) },
			stringResource(R.string.source_fdroid),
			R.drawable.ic_fdroid
		)
		SwitchSetting(
			{ viewModel.getUseIzzy() },
			{ viewModel.setUseIzzy(it) },
			stringResource(R.string.source_izzy),
			R.drawable.ic_izzy
		)
		SwitchSetting(
			{ viewModel.getUseAptoide() },
			{ viewModel.setUseAptoide(it) },
			stringResource(R.string.source_aptoide),
			R.drawable.ic_aptoide
		)
		SwitchSetting(
			{ viewModel.getUseApkPure() },
			{ viewModel.setUseApkPure(it) },
			stringResource(R.string.source_apkpure),
			R.drawable.ic_apkpure
		)
		SwitchSetting(
			{ viewModel.getUsePlay() },
			{ viewModel.setUsePlay(it) },
			stringResource(R.string.source_play),
			R.drawable.ic_play
		)
		SwitchSetting(
			{ viewModel.getUseRuStore() },
			{ viewModel.setUseRuStore(it) },
			stringResource(R.string.source_rustore),
			R.drawable.ic_rustore
		)
		SwitchSetting(
			{ viewModel.getUseSafeStores() },
			{ viewModel.setUseSafeStores(it) },
			stringResource(R.string.use_safe_stores),
			R.drawable.ic_safe
		)
	}
}

@Composable
fun UpdatesSettings(viewModel: SettingsViewModel) = LazyColumn {
	item {
		val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
		var alarmEnabled by remember { mutableStateOf(viewModel.getEnableAlarm()) }
		SectionHeader(stringResource(R.string.settings_alarm))
		SwitchSetting(
			getValue = { alarmEnabled },
			setValue = { viewModel.setEnableAlarm(it, launcher); alarmEnabled = it },
			text = stringResource(R.string.settings_alarm),
			icon = R.drawable.ic_alarm
		)
		if (alarmEnabled) {
			if (LocalContext.current.isAndroidTv()) {
				DropDownSetting(
					text = stringResource(R.string.settings_hour),
					options = (0..23).map { it.toString() },
					getValue = { viewModel.getAlarmHour() },
					setValue = { viewModel.setAlarmHour(it) },
					icon = R.drawable.ic_hour
				)
			} else {
				SliderSetting(
					getValue = { viewModel.getAlarmHour().toFloat() },
					setValue = { viewModel.setAlarmHour(it.toInt()) },
					text = stringResource(R.string.settings_hour),
					valueRange = 0f..23f,
					steps = 23,
					R.drawable.ic_hour
				)
			}
			DropDownSetting(
				text = stringResource(R.string.frequency),
				options = listOf(
					stringResource(R.string.settings_alarm_daily),
					stringResource(R.string.settings_alarm_3day),
					stringResource(R.string.settings_alarm_weekly)
				),
				getValue = { viewModel.getAlarmFrequency() },
				setValue = { viewModel.setAlarmFrequency(it) },
				icon = R.drawable.ic_frequency,
				width = 170
			)
		}
	}
	item {
		SectionHeader(stringResource(R.string.settings_versions))
		SwitchSetting(
			{ viewModel.getIgnoreAlpha() },
			{ viewModel.setIgnoreAlpha(it) },
			stringResource(R.string.ignore_alpha),
			R.drawable.ic_alpha
		)
		SwitchSetting(
			{ viewModel.getIgnoreBeta() },
			{ viewModel.setIgnoreBeta(it) },
			stringResource(R.string.ignore_beta),
			R.drawable.ic_beta
		)
		SwitchSetting(
			{ viewModel.getIgnorePreRelease() },
			{ viewModel.setIgnorePreRelease(it) },
			stringResource(R.string.ignore_preRelease),
			R.drawable.ic_pre_release
		)
	}
}

@Composable
fun InstallSettings(viewModel: SettingsViewModel) = LazyColumn {
	item {
		SwitchSetting(
			{ viewModel.getRootInstall() },
			{ viewModel.setRootInstall(it) },
			stringResource(R.string.root_install),
			R.drawable.ic_root
		)
		SwitchSetting(
			{ viewModel.getShizukuInstall() },
			{ viewModel.setShizukuInstall(it) },
			stringResource(R.string.shizuku_install),
			R.drawable.ic_shizuku
		)
		SwitchSetting(
			{ viewModel.getFakePlayStore() },
			{ viewModel.setFakePlayStore(it) },
			stringResource(R.string.fake_play_store),
			R.drawable.ic_play
		)
		SwitchSetting(
			{ viewModel.getCleanUpAfterInstall() },
			{ viewModel.setCleanUpAfterInstall(it) },
			stringResource(R.string.clean_up_after_install),
			R.drawable.ic_cleanup
		)
		SwitchSetting(
			{ viewModel.getNotifyOnInstall() },
			{ viewModel.setNotifyOnInstall(it) },
			stringResource(R.string.notify_on_install),
			R.drawable.ic_notification
		)
	}
}

@Composable
fun AppearanceSettings(viewModel: SettingsViewModel) = LazyColumn {
	item {
		SwitchSetting(
			{ viewModel.getPlayTextAnimations() },
			{ viewModel.setPlayTextAnimations(it) },
			stringResource(R.string.play_text_animations),
			R.drawable.ic_animation
		)
		SegmentedButtonSetting(
			stringResource(R.string.theme),
			listOf(
				stringResource(R.string.theme_system),
				stringResource(R.string.theme_dark),
				stringResource(R.string.theme_light)
			),
			{ viewModel.getTheme() },
			{ viewModel.setTheme(it) },
			R.drawable.ic_theme
		)
	}
}

@Composable
fun ToolsSettings(viewModel: SettingsViewModel) = LazyColumn {
	item {
		val context = LocalContext.current
		val exportLauncher = rememberLauncherForActivityResult(
			ActivityResultContracts.CreateDocument("application/json")
		) { uri ->
			if (uri != null) {
				val success = viewModel.exportConfigToUri(uri)
				Toast.makeText(
					context,
					context.getString(if (success) R.string.config_exported else R.string.config_import_failed),
					Toast.LENGTH_SHORT
				).show()
			}
		}
		val importLauncher = rememberLauncherForActivityResult(
			ActivityResultContracts.OpenDocument()
		) { uri ->
			if (uri != null) {
				val success = viewModel.importConfigFromUri(uri)
				Toast.makeText(
					context,
					context.getString(if (success) R.string.config_imported else R.string.config_import_failed),
					Toast.LENGTH_SHORT
				).show()
			}
		}

		ButtonSetting(
			stringResource(R.string.export_config),
			{ exportLauncher.launch("apkupdater-config.json") },
			R.drawable.ic_export,
			R.drawable.ic_export
		)
		ButtonSetting(
			stringResource(R.string.import_config),
			{ importLauncher.launch(arrayOf("application/json")) },
			R.drawable.ic_import,
			R.drawable.ic_import
		)
		val ignoredCount = remember { mutableStateOf(viewModel.getIgnoredVersionsCount()) }
		ButtonSetting(
			stringResource(R.string.clear_ignored_versions, ignoredCount.value),
			{
				if (ignoredCount.value > 0) {
					viewModel.clearIgnoredVersions()
					ignoredCount.value = 0
					Toast.makeText(context, context.getString(R.string.ignored_versions_cleared), Toast.LENGTH_SHORT).show()
				} else {
					Toast.makeText(context, context.getString(R.string.no_ignored_versions), Toast.LENGTH_SHORT).show()
				}
			},
			R.drawable.ic_cleanup,
			R.drawable.ic_cleanup
		)
		ButtonSetting(
			stringResource(R.string.copy_app_list),
			{ viewModel.copyAppList() },
			R.drawable.ic_root,
			R.drawable.ic_copy
		)
		ButtonSetting(
			stringResource(R.string.copy_app_logs),
			{ viewModel.copyAppLogs() },
			R.drawable.ic_root,
			R.drawable.ic_copy
		)
		// Only shown after a crash was captured on the previous run — lets the user hand over
		// the stack trace without logcat/adb or the ROM's (often broken) crash uploader.
		if (viewModel.hasCrashReport()) {
			ButtonSetting(
				stringResource(R.string.copy_crash_report),
				{ viewModel.copyCrashReport() },
				R.drawable.ic_system,
				R.drawable.ic_copy
			)
		}
	}
}

@Composable
fun CustomRepos(viewModel: SettingsViewModel) = LazyColumn(Modifier.fillMaxSize()) {
	item {
		var repoUrl by remember { mutableStateOf("") }
		var errorMsg by remember { mutableStateOf<String?>(null) }
		var repos by remember { mutableStateOf(viewModel.getCustomGitRepos()) }
		val invalidUrlMsg = stringResource(R.string.invalid_repo_url)
		// Non-null while editing an existing repo (its id); null in add mode.
		var editingId by remember { mutableStateOf<String?>(null) }

		// App picker state
		var appQuery by remember { mutableStateOf("") }
		var selectedPkgName by remember { mutableStateOf("") }
		var appDropdownExpanded by remember { mutableStateOf(false) }
		LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
		val allApps = viewModel.installedApps.collectAsStateWithLifecycle().value

		var repoUrlEditing by remember { mutableStateOf(false) }
		val isTvRepo = LocalContext.current.isAndroidTv()
		OutlinedTextField(
			value = repoUrl,
			onValueChange = { repoUrl = it; errorMsg = null },
			label = { Text(stringResource(R.string.custom_repo_hint)) },
			isError = errorMsg != null,
			supportingText = errorMsg?.let { msg -> { Text(msg) } },
			singleLine = true,
			readOnly = isTvRepo && !repoUrlEditing,
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
				.then(if (isTvRepo) Modifier.clickable { repoUrlEditing = true } else Modifier)
		)

		// Installed app picker
		var appQueryEditing by remember { mutableStateOf(false) }
		Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
			OutlinedTextField(
				value = appQuery,
				onValueChange = {
					appQuery = it
					selectedPkgName = ""
					appDropdownExpanded = it.length >= 2
				},
				label = { Text(stringResource(R.string.link_installed_app)) },
				singleLine = true,
				readOnly = isTvRepo && !appQueryEditing,
				modifier = Modifier.fillMaxWidth()
					.then(if (isTvRepo) Modifier.clickable { appQueryEditing = true } else Modifier)
			)
			val filtered = if (appQuery.length >= 2) {
				allApps.filter { it.name.contains(appQuery, ignoreCase = true) }.take(8)
			} else emptyList()
			DropdownMenu(
				expanded = appDropdownExpanded && filtered.isNotEmpty(),
				onDismissRequest = { appDropdownExpanded = false },
				modifier = Modifier.heightIn(max = 250.dp),
				properties = PopupProperties(focusable = false)
			) {
				filtered.forEach { app ->
					DropdownMenuItem(
						text = { Text(app.name) },
						onClick = {
							appQuery = app.name
							selectedPkgName = app.packageName
							appDropdownExpanded = false
						}
					)
				}
			}
		}

		fun clearRepoForm() {
			repoUrl = ""; errorMsg = null; appQuery = ""; selectedPkgName = ""; editingId = null
		}

		Row(
			Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = CenterVertically
		) {
			if (editingId != null) {
				TextButton(onClick = { clearRepoForm() }) {
					Text(stringResource(R.string.cancel_cd))
				}
			}
			Spacer(Modifier.weight(1f))
			Button(
				enabled = repoUrl.isNotBlank(),
				onClick = {
					val id = editingId
					val success = if (id != null) viewModel.updateCustomGitRepo(id, repoUrl, selectedPkgName)
						else viewModel.addCustomGitRepo(repoUrl, selectedPkgName)
					if (success) {
						clearRepoForm()
						repos = viewModel.getCustomGitRepos()
					} else errorMsg = invalidUrlMsg
				}
			) {
				Icon(
					if (editingId != null) Icons.Default.Check else Icons.Default.Add,
					null,
					Modifier.size(18.dp)
				)
				Spacer(Modifier.width(8.dp))
				Text(stringResource(if (editingId != null) R.string.save_repo else R.string.add_repo))
			}
		}

		repos.forEach { repo ->
			val host = if (repo.platform == GitProvider.GITHUB) "github.com" else "gitlab.com"
			Row(
				Modifier
					.fillMaxWidth()
					.clickable {
						// Load this repo into the form for editing.
						repoUrl = "$host/${repo.user}/${repo.repo}"
						selectedPkgName = repo.installedPackageName
						appQuery = allApps.find { it.packageName == repo.installedPackageName }?.name
							?: repo.installedPackageName
						errorMsg = null
						editingId = repo.id
					}
					.background(
						if (editingId == repo.id) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
						else androidx.compose.ui.graphics.Color.Transparent
					)
					.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalAlignment = CenterVertically
			) {
				Icon(
					painterResource(if (repo.platform == GitProvider.GITHUB) R.drawable.ic_github else R.drawable.ic_gitlab),
					repo.platform.name,
					Modifier.size(24.dp)
				)
				Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
					Text("${repo.user}/${repo.repo}", style = MaterialTheme.typography.bodyLarge)
					if (repo.installedPackageName.isNotEmpty()) {
						Text(repo.installedPackageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
				}
				TvIconButton(onClick = {
					viewModel.removeCustomGitRepo(repo.id)
					repos = viewModel.getCustomGitRepos()
					if (editingId == repo.id) clearRepoForm()
				}) {
					Icon(Icons.Default.Delete, stringResource(R.string.delete))
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomReposTopBar(viewModel: SettingsViewModel) = TopAppBar(
	title = { Text(stringResource(R.string.settings_custom_repos)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	navigationIcon = {
		TvIconButton(onClick = { viewModel.setSettings() }) {
			Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tab_settings))
		}
	}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(viewModel: SettingsViewModel) = TopAppBar(
	title = { Text(stringResource(R.string.tab_settings)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	actions = {
		TvIconButton(onClick = { viewModel.setAbout() }) {
			Icon(painterResource(R.drawable.ic_info), stringResource(R.string.about))
		}
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Settings, null)
		}
	}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutTopBar(viewModel: SettingsViewModel) = TopAppBar(
	title = { Text(stringResource(R.string.about)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	actions = {
		TvIconButton(onClick = { viewModel.setSettings() }) {
			Icon(Icons.Default.Settings, stringResource(R.string.tab_settings))
		}
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Info, null)
		}
	}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubSettingsTopBar(title: String, viewModel: SettingsViewModel) = TopAppBar(
	title = { Text(title) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	navigationIcon = {
		TvIconButton(onClick = { viewModel.setSettings() }) {
			Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tab_settings))
		}
	}
)
