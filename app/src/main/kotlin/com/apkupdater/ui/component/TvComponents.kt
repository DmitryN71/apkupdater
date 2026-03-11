package com.apkupdater.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.apkupdater.R
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Source
import com.apkupdater.util.getAppName
import androidx.compose.ui.text.font.FontWeight
import com.apkupdater.util.to2f
import androidx.compose.ui.text.AnnotatedString
import com.apkupdater.util.toAnnotatedString


@Composable
fun VersionChip(
	version: String,
	isNew: Boolean = false,
	modifier: Modifier = Modifier
) {
	val bgColor = if (isNew) MaterialTheme.colorScheme.primaryContainer
		else MaterialTheme.colorScheme.surfaceVariant
	val textColor = if (isNew) MaterialTheme.colorScheme.onPrimaryContainer
		else MaterialTheme.colorScheme.onSurfaceVariant
	Text(
		version,
		color = textColor,
		style = MaterialTheme.typography.labelSmall,
		maxLines = 1,
		modifier = modifier
			.background(bgColor, RoundedCornerShape(12.dp))
			.padding(horizontal = 8.dp, vertical = 2.dp)
	)
}

@Composable
fun SourceChip(source: Source, modifier: Modifier = Modifier) {
	val shape = RoundedCornerShape(50)
	Row(
		modifier = modifier
			.background(MaterialTheme.colorScheme.secondaryContainer, shape)
			.padding(horizontal = 10.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp)
	) {
		SourceIcon(source, Modifier.size(16.dp))
		Text(
			source.name,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSecondaryContainer
		)
	}
}

@Composable
fun TvCommonItem(
	packageName: String,
	name: String,
	version: String,
	oldVersion: String?,
	versionCode: Long,
	oldVersionCode: Long?,
	uri: Uri? = null,
	single: Boolean = false,
	source: Source? = null
) = Row(Modifier.padding(12.dp)) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		if (uri == null) {
			LoadingImageApp(packageName, Modifier.height(100.dp))
		} else {
			LoadingImage(uri, Modifier.height(100.dp))
		}
		if (source != null) {
			SourceChip(source, Modifier.padding(top = 6.dp))
		}
	}
	Column(Modifier.align(Alignment.CenterVertically).padding(start = 12.dp)) {
		LargeTitle(name.ifEmpty { LocalContext.current.getAppName(packageName) }.ifEmpty { packageName })
		MediumText(packageName, Modifier.alpha(0.6f))
		if (oldVersion != null && !single) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				modifier = Modifier.padding(top = 4.dp)
			) {
				VersionChip(oldVersion, isNew = false)
				Text("→", style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant)
				VersionChip(version, isNew = true)
			}
		} else {
			VersionChip(version, isNew = !single, modifier = Modifier.padding(top = 4.dp))
		}
		if (oldVersionCode != null && !single) {
			val code = if (versionCode == 0L) "?" else versionCode.toString()
			MediumText("$oldVersionCode → $code", Modifier.alpha(0.4f).padding(top = 2.dp))
		}
	}
}

@Composable
fun TvInstallButton(
	app: AppUpdate,
	onInstall: (String) -> Unit
) = OutlinedButton(
	modifier = Modifier.padding(4.dp),
	onClick = { onInstall(app.packageName) },
	colors = ButtonDefaults.outlinedButtonColors(
		contentColor = MaterialTheme.colorScheme.primary
	),
	border = ButtonDefaults.outlinedButtonBorder(enabled = true)
) {
	if (app.isInstalling) {
		if (app.total != 0L && app.progress != 0L) {
			val p = (app.progress.toFloat() / app.total) * 100f
			Text("${p.to2f()}%")
		} else {
			CircularProgressIndicator(Modifier.size(24.dp))
		}
	} else {
		Text(stringResource(R.string.install_cd))
	}
}

@Composable
fun TvInstalledItem(app: AppInstalled, onIgnore: (String) -> Unit = {}) = OutlinedCard(
	modifier = Modifier.alpha(if (app.ignored) 0.5f else 1f)
) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, null, app.versionCode, null)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.End
		) {
			OutlinedButton(
				modifier = Modifier.padding(4.dp),
				onClick = { onIgnore(app.packageName) },
				border = ButtonDefaults.outlinedButtonBorder(enabled = true)
			) {
				Text(stringResource(if (app.ignored) R.string.unignore_cd else R.string.ignore_cd))
			}
		}
	}
}

@Composable
fun TvIgnoreVersionButton(
	app: AppUpdate,
	onIgnoreVersion: (Int) -> Unit,
) = OutlinedButton(
	modifier = Modifier.padding(4.dp),
	onClick = { onIgnoreVersion(app.id) },
	colors = ButtonDefaults.outlinedButtonColors(
		contentColor = MaterialTheme.colorScheme.onSurfaceVariant
	),
	border = ButtonDefaults.outlinedButtonBorder(enabled = true)
) {
	Text(stringResource(R.string.ignore_version))
}

@Composable
fun TvUpdateItem(
	app: AppUpdate,
	onInstall: (String) -> Unit = {},
	onIgnoreVersion: (Int) -> Unit
) = OutlinedCard(Modifier.fillMaxWidth()) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, uri = app.iconUri.takeIf { it != Uri.EMPTY }, source = app.source)
		WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.End
		) {
			TvIgnoreVersionButton(app, onIgnoreVersion)
			TvInstallButton(app, onInstall)
		}
	}
}

@Composable
fun TvSearchItem(app: AppUpdate, onInstall: (String) -> Unit = {}) = OutlinedCard(Modifier.fillMaxWidth()) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, app.iconUri, true, source = app.source)
		WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.End
		) {
			TvInstallButton(app, onInstall)
		}
	}
}

@Composable
fun WhatsNew(whatsNew: String, source: Source) {
	if (whatsNew.isNotBlank()) {
		val text = remember(whatsNew) {
			runCatching {
				val truncated = if (whatsNew.length > 1500) whatsNew.take(1500) + "\u2026" else whatsNew
				HtmlCompat.fromHtml(truncated.trim(), HtmlCompat.FROM_HTML_MODE_COMPACT).toAnnotatedString()
			}.getOrElse { AnnotatedString(whatsNew.take(1500)) }
		}
		if (text.text.isNotBlank()) {
			ExpandingAnnotatedText(text, Modifier.padding(8.dp).fillMaxWidth())
		}
	}
}
