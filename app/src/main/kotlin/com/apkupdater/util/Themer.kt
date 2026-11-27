package com.apkupdater.util

import com.apkupdater.prefs.Prefs
import com.apkupdater.ui.theme.ThemeConfig
import com.apkupdater.ui.theme.isDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Themer(private val prefs: Prefs) {

	private val config = MutableStateFlow(readConfig())

	fun flow(): StateFlow<ThemeConfig> = config

	fun refresh() {
		config.value = readConfig()
	}

	fun setTheme(v: Boolean) {
		config.value = config.value.copy(darkTheme = v)
	}

	private fun readConfig() = ThemeConfig(
		darkTheme = isDarkTheme(prefs.theme.get()),
		useDynamicColor = prefs.useDynamicColor.get(),
		accentColor = prefs.accentColor.get(),
		paletteStyle = prefs.paletteStyle.get(),
		amoledBlack = prefs.amoledBlack.get(),
		highContrast = prefs.highContrast.get()
	)
}
