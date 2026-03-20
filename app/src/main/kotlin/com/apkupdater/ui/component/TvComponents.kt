package com.apkupdater.ui.component

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.Source
import com.apkupdater.util.getAppName
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import com.apkupdater.util.to2f
import androidx.compose.ui.text.AnnotatedString
import com.apkupdater.util.toAnnotatedString
import kotlinx.coroutines.launch


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
fun SourceChip(source: Source, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
	val shape = RoundedCornerShape(50)
	Row(
		modifier = modifier
			.background(
				if (onClick != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.secondaryContainer,
				shape
			)
			.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
			.padding(horizontal = 10.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp)
	) {
		SourceIcon(source, Modifier.size(16.dp))
		Text(
			source.name,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Bold,
			color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer
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
	source: Source? = null,
	onSourceClick: (() -> Unit)? = null
) = Row(Modifier.padding(12.dp)) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		if (uri == null) {
			LoadingImageApp(packageName, Modifier.height(100.dp))
		} else {
			LoadingImage(uri, Modifier.height(100.dp))
		}
		if (source != null) {
			SourceChip(source, Modifier.padding(top = 6.dp), onClick = onSourceClick)
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
					.horizontalScroll(rememberScrollState())
					.basicMarquee(iterations = Int.MAX_VALUE, velocity = 30.dp)
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
	onInstall: (String) -> Unit,
	onOpen: (String) -> Unit = {}
) = OutlinedButton(
	modifier = Modifier.padding(4.dp),
	onClick = {
		if (app.isInstalled) onOpen(app.packageName)
		else onInstall(app.packageName)
	},
	colors = ButtonDefaults.outlinedButtonColors(
		contentColor = if (app.isInstalled) MaterialTheme.colorScheme.tertiary
			else MaterialTheme.colorScheme.primary
	),
	border = ButtonDefaults.outlinedButtonBorder(enabled = true)
) {
	if (app.isInstalling) {
		if (app.total != 0L && app.progress != 0L) {
			val p = ((app.progress.toFloat() / app.total) * 100f).coerceAtMost(100f)
			Text("${p.to2f()}%")
		} else {
			CircularProgressIndicator(Modifier.size(24.dp))
		}
	} else if (app.isInstalled) {
		Text(stringResource(R.string.open_cd))
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
) {
	OutlinedButton(
		modifier = Modifier.padding(2.dp),
		onClick = { onIgnoreVersion(app.id) },
		contentPadding = ButtonDefaults.ContentPadding.let {
			androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = it.calculateTopPadding())
		},
		colors = ButtonDefaults.outlinedButtonColors(
			contentColor = MaterialTheme.colorScheme.onSurfaceVariant
		)
	) {
		Text(stringResource(R.string.skip_cd))
	}
}

@Composable
fun TvHideButton(
	onHide: () -> Unit
) {
	OutlinedButton(
		modifier = Modifier.padding(2.dp),
		onClick = onHide,
		contentPadding = ButtonDefaults.ContentPadding.let {
			androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = it.calculateTopPadding())
		},
		colors = ButtonDefaults.outlinedButtonColors(
			contentColor = MaterialTheme.colorScheme.onSurfaceVariant
		)
	) {
		Text(stringResource(R.string.hide_cd))
	}
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TvDownloadButton(
	app: AppUpdate,
	onDownload: (AppUpdate) -> Unit
) {
	val hasLink = (app.link is Link.Url || app.link is Link.Xapk || app.link is Link.Play) && app.source != ApkMirrorSource
	if (hasLink && !app.isInstalling) {
		val tooltipState = rememberTooltipState()
		val scope = rememberCoroutineScope()
		TooltipBox(
			positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
			tooltip = { PlainTooltip { Text(stringResource(R.string.download_cd)) } },
			state = tooltipState
		) {
			OutlinedIconButton(
				modifier = Modifier.padding(2.dp).combinedClickable(
					onClick = { onDownload(app) },
					onLongClick = { scope.launch { tooltipState.show() } }
				),
				onClick = { onDownload(app) },
				colors = IconButtonDefaults.outlinedIconButtonColors(
					contentColor = MaterialTheme.colorScheme.onSurfaceVariant
				)
			) {
				Icon(painterResource(R.drawable.ic_save), contentDescription = stringResource(R.string.download_cd))
			}
		}
	}
}

@Composable
fun TvUpdateItem(
	app: AppUpdate,
	onInstall: (String) -> Unit = {},
	onIgnoreVersion: (Int) -> Unit,
	onOpen: (String) -> Unit = {},
	onHide: (Int) -> Unit = {},
	onSourceClick: (() -> Unit)? = null,
	onDownload: (AppUpdate) -> Unit = {}
) = OutlinedCard(Modifier.fillMaxWidth()) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, uri = app.iconUri.takeIf { it != Uri.EMPTY }, source = app.source, onSourceClick = onSourceClick)
		WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.End
		) {
			if (!app.isInstalled) {
				TvIgnoreVersionButton(app, onIgnoreVersion)
			} else {
				TvHideButton { onHide(app.id) }
			}
			TvDownloadButton(app, onDownload)
			TvInstallButton(app, onInstall, onOpen)
		}
	}
}

@Composable
fun TvSearchItem(
	app: AppUpdate,
	onInstall: (String) -> Unit = {},
	onOpen: (String) -> Unit = {},
	onSourceClick: (() -> Unit)? = null,
	onDownload: (AppUpdate) -> Unit = {}
) = OutlinedCard(Modifier.fillMaxWidth()) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, app.iconUri, true, source = app.source, onSourceClick = onSourceClick)
		WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.End
		) {
			TvDownloadButton(app, onDownload)
			TvInstallButton(app, onInstall, onOpen)
		}
	}
}

@Composable
fun WhatsNew(whatsNew: String, source: Source) {
	if (whatsNew.isNotBlank()) {
		val text = remember(whatsNew) {
			runCatching {
				val truncated = if (whatsNew.length > 1500) whatsNew.take(1500) + "\u2026" else whatsNew
				val html = markdownToHtml(truncated.trim())
				HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toAnnotatedString()
			}.getOrElse { AnnotatedString(whatsNew.take(1500)) }
		}
		if (text.text.isNotBlank()) {
			ExpandingAnnotatedText(text, Modifier.padding(8.dp).fillMaxWidth())
		}
	}
}

/** Simple Markdown to HTML converter for changelogs. */
private fun markdownToHtml(md: String): String {
	// If it already looks like HTML, return as-is
	if (md.trimStart().startsWith("<")) return md

	val sb = StringBuilder()
	var inList = false

	for (line in md.lines()) {
		val trimmed = line.trim()
		when {
			trimmed.isEmpty() -> {
				if (inList) { sb.append("</ul>"); inList = false }
				sb.append("<br>")
			}
			trimmed.startsWith("### ") -> {
				if (inList) { sb.append("</ul>"); inList = false }
				sb.append("<b>").append(inlineMd(trimmed.removePrefix("### "))).append("</b><br>")
			}
			trimmed.startsWith("## ") -> {
				if (inList) { sb.append("</ul>"); inList = false }
				sb.append("<b>").append(inlineMd(trimmed.removePrefix("## "))).append("</b><br>")
			}
			trimmed.startsWith("# ") -> {
				if (inList) { sb.append("</ul>"); inList = false }
				sb.append("<b>").append(inlineMd(trimmed.removePrefix("# "))).append("</b><br>")
			}
			trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
				if (!inList) { sb.append("<ul>"); inList = true }
				sb.append("<li>").append(inlineMd(trimmed.drop(2))).append("</li>")
			}
			else -> {
				if (inList) { sb.append("</ul>"); inList = false }
				sb.append(inlineMd(trimmed)).append("<br>")
			}
		}
	}
	if (inList) sb.append("</ul>")
	return sb.toString()
}

/** Convert inline markdown: **bold**, *italic*, `code` */
private fun inlineMd(text: String): String = text
	.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
	.replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
	.replace(Regex("`(.+?)`"), "<tt>$1</tt>")
