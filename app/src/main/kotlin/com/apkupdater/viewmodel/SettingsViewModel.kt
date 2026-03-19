package com.apkupdater.viewmodel

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.apkupdater.data.github.CustomGitRepo
import com.apkupdater.data.github.parseRepoUrl
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.SettingsUiState
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.AppsRepository
import com.apkupdater.ui.theme.isDarkTheme
import com.apkupdater.util.Clipboard
import com.apkupdater.util.Themer
import com.apkupdater.util.UpdatesNotification
import com.apkupdater.worker.UpdatesWorker
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


class SettingsViewModel(
    private val prefs: Prefs,
    private val notification: UpdatesNotification,
    private val workManager: WorkManager,
	private val clipboard: Clipboard,
	private val appsRepository: AppsRepository,
	private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
	private val themer: Themer,
	private val context: Context
) : ViewModel() {

	val state = MutableStateFlow<SettingsUiState>(SettingsUiState.Settings)

	private val _installedApps = MutableStateFlow<List<AppInstalled>>(emptyList())
	val installedApps: StateFlow<List<AppInstalled>> = _installedApps

	fun loadInstalledApps() = viewModelScope.launch(Dispatchers.IO) {
		appsRepository.getApps().collectLatest { result ->
			result.onSuccess { _installedApps.value = it }
		}
	}

	private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
		if (grantResult == PackageManager.PERMISSION_GRANTED) {
			prefs.shizukuInstall.put(true)
			prefs.rootInstall.put(false)
		}
	}

	init {
		try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) } catch (_: Exception) {}
	}

	override fun onCleared() {
		super.onCleared()
		try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) } catch (_: Exception) {}
	}

	fun setPortraitColumns(n: Int) = prefs.portraitColumns.put(n)
	fun getPortraitColumns() = prefs.portraitColumns.get()
	fun setLandscapeColumns(n: Int) = prefs.landscapeColumns.put(n)
	fun getLandscapeColumns() = prefs.landscapeColumns.get()
	fun setPlayTextAnimations(b: Boolean) = prefs.playTextAnimations.put(b)
	fun getPlayTextAnimations() = prefs.playTextAnimations.get()
	fun setIgnoreAlpha(b: Boolean) = prefs.ignoreAlpha.put(b)
	fun getIgnoreAlpha() = prefs.ignoreAlpha.get()
	fun setIgnoreBeta(b: Boolean) = prefs.ignoreBeta.put(b)
	fun getIgnoreBeta() = prefs.ignoreBeta.get()
	fun setIgnorePreRelease(b: Boolean) = prefs.ignorePreRelease.put(b)
	fun getIgnorePreRelease() = prefs.ignorePreRelease.get()
	fun getUseSafeStores() = prefs.useSafeStores.get()
	fun setUseSafeStores(b: Boolean) = prefs.useSafeStores.put(b)
	fun getUseApkMirror() = prefs.useApkMirror.get()
	fun setUseApkMirror(b: Boolean) = prefs.useApkMirror.put(b)
	fun getUseFdroid() = prefs.useFdroid.get()
	fun setUseFdroid(b: Boolean) = prefs.useFdroid.put(b)
	fun getUseIzzy() = prefs.useIzzy.get()
	fun setUseIzzy(b: Boolean) = prefs.useIzzy.put(b)
	fun getUseGitHub() = prefs.useGitHub.get()
	fun setUseGitHub(b: Boolean) = prefs.useGitHub.put(b)
	fun getGitHubToken() = prefs.githubToken.get()
	fun setGitHubToken(token: String) = prefs.githubToken.put(token.trim())
	fun getUseGitLab() = prefs.useGitLab.get()
	fun setUseGitLab(b: Boolean) = prefs.useGitLab.put(b)
	fun getUseAptoide() = prefs.useAptoide.get()
	fun setUseAptoide(b: Boolean) = prefs.useAptoide.put(b)
	fun getUseApkPure() = prefs.useApkPure.get()
	fun setUseApkPure(b: Boolean) = prefs.useApkPure.put(b)
	fun getUsePlay() = prefs.usePlay.get()
	fun setUsePlay(b: Boolean) = prefs.usePlay.put(b)
	fun getUseRuStore() = prefs.useRuStore.get()
	fun setUseRuStore(b: Boolean) = prefs.useRuStore.put(b)
	fun getAndroidTvUi() = prefs.androidTvUi.get()
	fun setAndroidTvUi(b: Boolean) = prefs.androidTvUi.put(b)
	fun getEnableAlarm() = prefs.enableAlarm.get()
	fun getRootInstall() = prefs.rootInstall.get()
	fun getCleanUpAfterInstall() = prefs.cleanUpAfterInstall.get()
	fun setCleanUpAfterInstall(b: Boolean) = prefs.cleanUpAfterInstall.put(b)
	fun getAlarmHour() = prefs.alarmHour.get()
	fun getAlarmFrequency() = prefs.alarmFrequency.get()
	fun getTheme() = prefs.theme.get()

	fun setTheme(theme: Int) {
		prefs.theme.put(theme)
		themer.setTheme(isDarkTheme(theme))
	}

	fun setRootInstall(b: Boolean) {
		if (b && Shell.isAppGrantedRoot() == true) {
			prefs.rootInstall.put(true)
			prefs.shizukuInstall.put(false)
		} else {
			prefs.rootInstall.put(false)
		}
	}

	fun getFakePlayStore() = prefs.fakePlayStore.get()
	fun setFakePlayStore(b: Boolean) = prefs.fakePlayStore.put(b)

	fun getShizukuInstall() = prefs.shizukuInstall.get()

	fun setShizukuInstall(b: Boolean) {
		if (b) {
			try {
				if (Shizuku.pingBinder()) {
					if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
						prefs.shizukuInstall.put(true)
						prefs.rootInstall.put(false)
					} else {
						Shizuku.requestPermission(0)
						prefs.shizukuInstall.put(false)
					}
				} else {
					prefs.shizukuInstall.put(false)
				}
			} catch (e: Exception) {
				prefs.shizukuInstall.put(false)
			}
		} else {
			prefs.shizukuInstall.put(false)
		}
	}

	fun setAlarmFrequency(frequency: Int) {
		prefs.alarmFrequency.put(frequency)
		if (getEnableAlarm()) UpdatesWorker.launch(workManager) else UpdatesWorker.cancel(workManager)
	}

	fun setEnableAlarm(b: Boolean, launcher: ManagedActivityResultLauncher<String, Boolean>) {
		prefs.enableAlarm.put(b)
		if (b) {
			notification.checkNotificationPermission(launcher)
			UpdatesWorker.launch(workManager)
		} else {
			UpdatesWorker.cancel(workManager)
		}
	}

	fun setAlarmHour(hour: Int) {
		prefs.alarmHour.put(hour)
		if (getEnableAlarm()) UpdatesWorker.launch(workManager) else UpdatesWorker.cancel(workManager)
	}

	fun setAbout() {
		state.value = SettingsUiState.About
	}

	fun setSettings() {
		state.value = SettingsUiState.Settings
	}

	fun copyAppList() = viewModelScope.launch(Dispatchers.IO) {
		appsRepository.getApps().collectLatest { apps ->
			apps.onSuccess {
				clipboard.copy(gson.toJson(it), "App List")
			}
		}
	}

	fun copyAppLogs() = viewModelScope.launch(Dispatchers.IO) {
		val process = Runtime.getRuntime().exec("logcat -d")
		val data = process.inputStream.readBytes()
		clipboard.copy(data.decodeToString(), "App Logs")
	}

	fun getIgnoredVersionsCount(): Int = prefs.ignoredVersions.get().size

	fun clearIgnoredVersions() = prefs.ignoredVersions.put(emptyList())

	fun getCustomGitRepos(): List<CustomGitRepo> = prefs.customGitRepos.get()

	fun addCustomGitRepo(url: String, installedPkgName: String = ""): Boolean {
		val repo = parseRepoUrl(url) ?: return false
		val current = prefs.customGitRepos.get()
		if (current.any { it.user == repo.user && it.repo == repo.repo }) return true
		prefs.customGitRepos.put(current + repo.copy(installedPackageName = installedPkgName))
		return true
	}

	fun removeCustomGitRepo(id: String) {
		val current = prefs.customGitRepos.get()
		prefs.customGitRepos.put(current.filterNot { it.id == id })
	}

	fun exportConfig(): String {
		val config = JsonObject().apply {
			addProperty("excludeSystem", prefs.excludeSystem.get())
			addProperty("excludeDisabled", prefs.excludeDisabled.get())
			addProperty("excludeStore", prefs.excludeStore.get())
			addProperty("playTextAnimations", prefs.playTextAnimations.get())
			addProperty("ignoreAlpha", prefs.ignoreAlpha.get())
			addProperty("ignoreBeta", prefs.ignoreBeta.get())
			addProperty("ignorePreRelease", prefs.ignorePreRelease.get())
			addProperty("useSafeStores", prefs.useSafeStores.get())
			addProperty("useApkMirror", prefs.useApkMirror.get())
			addProperty("useGitHub", prefs.useGitHub.get())
			addProperty("useGitLab", prefs.useGitLab.get())
			addProperty("useFdroid", prefs.useFdroid.get())
			addProperty("useIzzy", prefs.useIzzy.get())
			addProperty("useAptoide", prefs.useAptoide.get())
			addProperty("useApkPure", prefs.useApkPure.get())
			addProperty("usePlay", prefs.usePlay.get())
			addProperty("useRuStore", prefs.useRuStore.get())
			addProperty("enableAlarm", prefs.enableAlarm.get())
			addProperty("alarmHour", prefs.alarmHour.get())
			addProperty("alarmFrequency", prefs.alarmFrequency.get())
			addProperty("rootInstall", prefs.rootInstall.get())
			addProperty("shizukuInstall", prefs.shizukuInstall.get())
			addProperty("fakePlayStore", prefs.fakePlayStore.get())
			addProperty("theme", prefs.theme.get())
			addProperty("cleanUpAfterInstall", prefs.cleanUpAfterInstall.get())
			addProperty("githubToken", prefs.githubToken.get())
			add("ignoredApps", gson.toJsonTree(prefs.ignoredApps.get()))
			add("customGitRepos", gson.toJsonTree(prefs.customGitRepos.get()))
		}
		return gson.toJson(config)
	}

	fun exportConfigToUri(uri: Uri): Boolean = runCatching {
		context.contentResolver.openOutputStream(uri)?.use { stream ->
			stream.write(exportConfig().toByteArray())
		}
		true
	}.getOrElse { false }

	fun importConfigFromUri(uri: Uri): Boolean = runCatching {
		val json = context.contentResolver.openInputStream(uri)?.use { stream ->
			stream.bufferedReader().readText()
		} ?: return false
		importConfig(json)
	}.getOrElse { false }

	private fun importConfig(json: String): Boolean = runCatching {
		val obj = JsonParser.parseString(json).asJsonObject
		obj.get("excludeSystem")?.asBoolean?.let { prefs.excludeSystem.put(it) }
		obj.get("excludeDisabled")?.asBoolean?.let { prefs.excludeDisabled.put(it) }
		obj.get("excludeStore")?.asBoolean?.let { prefs.excludeStore.put(it) }
		obj.get("playTextAnimations")?.asBoolean?.let { prefs.playTextAnimations.put(it) }
		obj.get("ignoreAlpha")?.asBoolean?.let { prefs.ignoreAlpha.put(it) }
		obj.get("ignoreBeta")?.asBoolean?.let { prefs.ignoreBeta.put(it) }
		obj.get("ignorePreRelease")?.asBoolean?.let { prefs.ignorePreRelease.put(it) }
		obj.get("useSafeStores")?.asBoolean?.let { prefs.useSafeStores.put(it) }
		obj.get("useApkMirror")?.asBoolean?.let { prefs.useApkMirror.put(it) }
		obj.get("useGitHub")?.asBoolean?.let { prefs.useGitHub.put(it) }
		obj.get("useGitLab")?.asBoolean?.let { prefs.useGitLab.put(it) }
		obj.get("useFdroid")?.asBoolean?.let { prefs.useFdroid.put(it) }
		obj.get("useIzzy")?.asBoolean?.let { prefs.useIzzy.put(it) }
		obj.get("useAptoide")?.asBoolean?.let { prefs.useAptoide.put(it) }
		obj.get("useApkPure")?.asBoolean?.let { prefs.useApkPure.put(it) }
		obj.get("usePlay")?.asBoolean?.let { prefs.usePlay.put(it) }
		obj.get("useRuStore")?.asBoolean?.let { prefs.useRuStore.put(it) }
		obj.get("enableAlarm")?.asBoolean?.let { prefs.enableAlarm.put(it) }
		obj.get("alarmHour")?.asInt?.let { prefs.alarmHour.put(it) }
		obj.get("alarmFrequency")?.asInt?.let { prefs.alarmFrequency.put(it) }
		obj.get("rootInstall")?.asBoolean?.let { prefs.rootInstall.put(it) }
		obj.get("shizukuInstall")?.asBoolean?.let { prefs.shizukuInstall.put(it) }
		obj.get("fakePlayStore")?.asBoolean?.let { prefs.fakePlayStore.put(it) }
		obj.get("theme")?.asInt?.let { prefs.theme.put(it); themer.setTheme(isDarkTheme(it)) }
		obj.get("cleanUpAfterInstall")?.asBoolean?.let { prefs.cleanUpAfterInstall.put(it) }
		obj.get("githubToken")?.asString?.let { prefs.githubToken.put(it) }
		obj.get("ignoredApps")?.let {
			prefs.ignoredApps.put(gson.fromJson(it, Array<String>::class.java).toList())
		}
		obj.get("customGitRepos")?.let {
			prefs.customGitRepos.put(gson.fromJson(it, Array<CustomGitRepo>::class.java).toList())
		}
		true
	}.getOrElse { false }

}
