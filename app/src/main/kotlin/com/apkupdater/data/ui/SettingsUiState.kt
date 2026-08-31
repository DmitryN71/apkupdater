package com.apkupdater.data.ui


/**
 * Which Settings screen is showing. [Settings] is the category list; everything else is one
 * category opened from it. The screen's BackHandler returns to [Settings] from any of them.
 */
sealed class SettingsUiState {
    object Settings : SettingsUiState()
    object Sources : SettingsUiState()
    object Updates : SettingsUiState()
    object Install : SettingsUiState()
    object Appearance : SettingsUiState()
    object Tools : SettingsUiState()
    object CustomRepos : SettingsUiState()
    object About : SettingsUiState()
}
