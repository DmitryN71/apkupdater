package com.apkupdater.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.GitHubSource
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.ui.component.LocalPreviewCompactCards
import com.apkupdater.ui.component.LocalPreviewFocusTarget
import com.apkupdater.ui.component.TvUpdateItem
import com.apkupdater.ui.theme.AppTheme
import com.apkupdater.ui.theme.statusBarColor

@Preview(
	name = "Android TV 1080p - Post-Install Card States",
	device = "spec:width=1920px,height=1360px,dpi=320",
	locale = "ru",
	uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_TELEVISION,
	showBackground = true
)
@Composable
fun TvPostInstallColorsPreview() {
	AppTheme(darkTheme = true, dynamicColor = false) {
		CompositionLocalProvider(LocalPreviewCompactCards provides false) {
			Surface(
				modifier = Modifier.fillMaxSize(),
				color = MaterialTheme.colorScheme.background
			) {
				Column(Modifier.fillMaxSize()) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.background(MaterialTheme.colorScheme.statusBarColor())
							.padding(horizontal = 24.dp, vertical = 14.dp)
					) {
						Text(
							text = "Обновления – Android TV (до и после установки)",
							style = MaterialTheme.typography.titleLarge,
							color = MaterialTheme.colorScheme.onSurface
						)
					}

					Column(
						modifier = Modifier
							.fillMaxSize()
							.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(16.dp)
					) {
						Row(
							modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
							horizontalArrangement = Arrangement.spacedBy(16.dp)
						) {
							CompositionLocalProvider(LocalPreviewFocusTarget provides null) {
								TvUpdateItem(
									app = AppUpdate(
										name = "SmartTube (до установки – покой)",
										packageName = "com.teamsmart.videomanager.tv",
										version = "25.18-beta",
										oldVersion = "25.12",
										versionCode = 25180,
										oldVersionCode = 25120,
										source = GitHubSource,
										link = Link.Url("https://example.com/smarttube.apk", 38_400_000L),
										whatsNew = "- Исправлено воспроизведение 4K HDR на Android TV\n- Ускорена навигация пультом",
										sourceUrl = "https://github.com/yuliskov/SmartTube",
										updateDate = "24 нояб. 2026",
										installedFrom = "GitHub",
										isInstalled = false
									),
									modifier = Modifier.weight(1f).fillMaxHeight(),
									onIgnoreVersion = {},
									onSourceClick = {}
								)
							}

							CompositionLocalProvider(LocalPreviewFocusTarget provides "install") {
								TvUpdateItem(
									app = AppUpdate(
										name = "VLC for Android (после установки – фокус на «Открыть»)",
										packageName = "org.videolan.vlc",
										version = "3.6.5-beta",
										oldVersion = "3.6.3",
										versionCode = 3060500,
										oldVersionCode = 3060300,
										source = PlaySource,
										link = Link.Url("https://example.com/vlc.apk", 46_800_000L),
										whatsNew = "- Улучшена поддержка звуковых дорожек TrueHD и DTS\n- Исправления интерфейса Android TV",
										sourceUrl = "https://play.google.com/store/apps/details?id=org.videolan.vlc",
										updateDate = "25 нояб. 2026",
										installedFrom = "Play",
										isInstalled = true
									),
									modifier = Modifier.weight(1f).fillMaxHeight(),
									onIgnoreVersion = {},
									onSourceClick = {}
								)
							}
						}

						Row(
							modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
							horizontalArrangement = Arrangement.spacedBy(16.dp)
						) {
							CompositionLocalProvider(LocalPreviewFocusTarget provides null) {
								TvUpdateItem(
									app = AppUpdate(
										name = "Kodi (после установки – без фокуса)",
										packageName = "org.xbmc.kodi",
										version = "21.2",
										oldVersion = "21.1",
										versionCode = 21200000,
										oldVersionCode = 21100000,
										source = PlaySource,
										link = Link.Url("https://example.com/kodi.apk", 64_100_000L),
										whatsNew = "- Обновлен декодер AV1 и сетевой кэш SMB/NFS",
										sourceUrl = "https://play.google.com/store/apps/details?id=org.xbmc.kodi",
										updateDate = "22 нояб. 2026",
										installedFrom = "Play",
										isInstalled = true
									),
									modifier = Modifier.weight(1f).fillMaxHeight(),
									onIgnoreVersion = {},
									onSourceClick = {}
								)
							}

							CompositionLocalProvider(LocalPreviewFocusTarget provides "hide") {
								TvUpdateItem(
									app = AppUpdate(
										name = "TorrServe MatriX (после установки – фокус на «Скрыть»)",
										packageName = "ru.yourok.torrserve",
										version = "136-beta",
										oldVersion = "134",
										versionCode = 136,
										oldVersionCode = 134,
										source = GitHubSource,
										link = Link.Url("https://example.com/torrserve.apk", 29_500_000L),
										whatsNew = "- Оптимизация буфера в оперативной памяти на приставках",
										sourceUrl = "https://github.com/YouROK/TorrServe",
										updateDate = "23 нояб. 2026",
										installedFrom = "GitHub",
										isInstalled = true
									),
									modifier = Modifier.weight(1f).fillMaxHeight(),
									onIgnoreVersion = {},
									onSourceClick = {}
								)
							}
						}
					}
				}
			}
		}
	}
}
