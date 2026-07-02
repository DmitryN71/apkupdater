package com.apkupdater.ui.component

import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.Source
import com.apkupdater.util.getAppName
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import com.apkupdater.util.to2f
import androidx.compose.ui.text.AnnotatedString
import com.apkupdater.util.toAnnotatedString
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.ui.graphics.Color
import com.apkupdater.prefs.Prefs
import org.koin.androidx.compose.get


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
fun SizeChip(sizeBytes: Long, modifier: Modifier = Modifier) {
	if (sizeBytes > 0) {
		val text = when {
			sizeBytes >= 1_073_741_824 -> "%.1f GB".format(sizeBytes / 1_073_741_824.0)
			sizeBytes >= 1_048_576 -> "%.1f MB".format(sizeBytes / 1_048_576.0)
			sizeBytes >= 1024 -> "%.0f KB".format(sizeBytes / 1024.0)
			else -> "$sizeBytes B"
		}
		Text(
			text,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			style = MaterialTheme.typography.labelSmall,
			modifier = modifier
				.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
				.padding(horizontal = 8.dp, vertical = 2.dp)
		)
	}
}

@Composable
fun SourceChip(source: Source, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
	val shape = RoundedCornerShape(50)
	val clickable = onClick != null
	// Clickable (source URL available) chips get a tonal-primary look + an
	// open-in-new icon so they read as a link, not a static label.
	val background = if (clickable) MaterialTheme.colorScheme.primaryContainer
		else MaterialTheme.colorScheme.secondaryContainer
	val foreground = if (clickable) MaterialTheme.colorScheme.onPrimaryContainer
		else MaterialTheme.colorScheme.onSecondaryContainer
	Row(
		modifier = modifier
			.background(background, shape)
			.then(if (clickable) Modifier.clickable { onClick() } else Modifier)
			.padding(horizontal = 10.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp)
	) {
		SourceIcon(source, Modifier.size(16.dp))
		Text(
			source.name,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Bold,
			color = foreground
		)
		if (clickable) {
			Icon(
				Icons.AutoMirrored.Filled.OpenInNew,
				contentDescription = null,
				tint = foreground,
				modifier = Modifier.size(12.dp)
			)
		}
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
	onSourceClick: (() -> Unit)? = null,
	fileSize: Long = 0L
) = Row(Modifier.padding(12.dp)) {
	// Read once, unconditionally — get<Prefs>() is @Composable and must not be
	// called behind a short-circuit (overflow flips 0→N after layout measures).
	val animateText = get<Prefs>().playTextAnimations.get()
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
			val scrollState = rememberScrollState()
			val overflow = scrollState.maxValue

			// Auto-scroll only when the user has opted into text animations —
			// constantly moving version text is hard to read, especially in a car.
			if (overflow > 0 && animateText) {
				val transition = rememberInfiniteTransition(label = "bounce")
				val fraction by transition.animateFloat(
					initialValue = 0f, targetValue = 1f,
					animationSpec = infiniteRepeatable(
						animation = tween(durationMillis = 3000, delayMillis = 1500),
						repeatMode = RepeatMode.Reverse
					), label = "scroll"
				)
				androidx.compose.runtime.LaunchedEffect(fraction) {
					scrollState.scrollTo((overflow * fraction).toInt())
				}
			}

			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				modifier = Modifier.padding(top = 4.dp)
					.horizontalScroll(scrollState)
			) {
				VersionChip(oldVersion, isNew = false)
				Icon(
					Icons.AutoMirrored.Filled.ArrowRightAlt,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(16.dp)
				)
				VersionChip(version, isNew = true)
			}
		} else {
			VersionChip(version, isNew = !single, modifier = Modifier.padding(top = 4.dp))
		}
		if (oldVersionCode != null && !single) {
			val code = if (versionCode == 0L) "?" else versionCode.toString()
			MediumText("$oldVersionCode → $code", Modifier.alpha(0.4f).padding(top = 2.dp))
		}
		SizeChip(fileSize, Modifier.padding(top = 2.dp))
	}
}

@Composable
fun TvInstallButton(
	app: AppUpdate,
	onInstall: (String) -> Unit,
	onOpen: (String) -> Unit = {},
	onCancel: (Int) -> Unit = {},
	isSearch: Boolean = false
) {
	// For search: oldVersionCode > 0 means app is installed
	val isInstalledElsewhere = isSearch && app.oldVersionCode > 0L && !app.isInstalled
	val isUpToDate = isInstalledElsewhere && app.oldVersionCode >= app.versionCode
	val isUpdate = isInstalledElsewhere && app.oldVersionCode < app.versionCode

	// D-pad focus cue per Material TV guidance: the button itself switches to a
	// solid fill. Drawn by the component — it matches the pill shape exactly and
	// never paints outside the button's bounds (unlike a border modifier, which
	// hugged the invisible 48dp touch-target box and stuck out vertically).
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()

	val container = when {
		app.isInstalling -> if (focused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer
		app.isInstalled -> if (focused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer
		focused -> MaterialTheme.colorScheme.primary
		else -> MaterialTheme.colorScheme.primaryContainer
	}
	val content = when {
		app.isInstalling -> if (focused) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer
		app.isInstalled -> if (focused) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onTertiaryContainer
		focused -> MaterialTheme.colorScheme.onPrimary
		else -> MaterialTheme.colorScheme.onPrimaryContainer
	}

	FilledTonalButton(
		onClick = {
			when {
				app.isInstalling -> onCancel(app.id)
				app.isInstalled -> onOpen(app.packageName)
				!isUpToDate -> onInstall(app.packageName)
			}
		},
		enabled = !isUpToDate,
		interactionSource = interaction,
		colors = ButtonDefaults.filledTonalButtonColors(
			containerColor = container,
			contentColor = content
		)
	) {
		if (app.isInstalling) {
			Icon(Icons.Filled.Close, stringResource(R.string.cancel_cd), Modifier.size(16.dp))
			Spacer(Modifier.width(4.dp))
			if (app.total != 0L && app.progress != 0L) {
				val p = ((app.progress.toFloat() / app.total) * 100f).coerceAtMost(100f)
				Text("${p.to2f()}%")
			} else {
				Text(stringResource(R.string.cancel_cd))
			}
		} else if (app.isInstalled) {
			Text(stringResource(R.string.open_cd))
		} else if (isUpToDate) {
			Text(stringResource(R.string.installed_cd))
		} else if (isUpdate || !isSearch) {
			Text(stringResource(R.string.update_cd))
		} else {
			Text(stringResource(R.string.install_cd))
		}
	}
}

@Composable
fun TvInstalledItem(app: AppInstalled, onIgnore: (String) -> Unit = {}) = Card(
	modifier = Modifier.alpha(if (app.ignored) 0.5f else 1f),
	shape = RoundedCornerShape(20.dp),
	colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, null, app.versionCode, null)
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.End
		) {
			val interaction = remember { MutableInteractionSource() }
			val focused by interaction.collectIsFocusedAsState()
			TextButton(
				onClick = { onIgnore(app.packageName) },
				interactionSource = interaction,
				colors = ButtonDefaults.textButtonColors(
					containerColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
					contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
				)
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
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	TextButton(
		onClick = { onIgnoreVersion(app.id) },
		enabled = !app.isInstalling,
		interactionSource = interaction,
		colors = ButtonDefaults.textButtonColors(
			containerColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
			contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
		)
	) {
		Text(stringResource(R.string.skip_cd))
	}
}

@Composable
fun TvHideButton(
	onHide: () -> Unit
) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	TextButton(
		onClick = onHide,
		interactionSource = interaction,
		colors = ButtonDefaults.textButtonColors(
			containerColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
			contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
		)
	) {
		Text(stringResource(R.string.hide_cd))
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvDownloadButton(
	app: AppUpdate,
	onDownload: (AppUpdate) -> Unit
) {
	val hasLink = (app.link is Link.Url || app.link is Link.Xapk || app.link is Link.Play) && app.source != ApkMirrorSource
	if (hasLink) {
		// Single focusable node: the button itself. The old extra combinedClickable
		// wrapper was a second focus stop with a rectangular highlight — the cause
		// of the square ring around this round button on TV.
		val interaction = remember { MutableInteractionSource() }
		val focused by interaction.collectIsFocusedAsState()
		TooltipBox(
			positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
			tooltip = { PlainTooltip { Text(stringResource(R.string.download_cd)) } },
			state = rememberTooltipState()
		) {
			IconButton(
				onClick = { if (!app.isInstalling) onDownload(app) },
				enabled = !app.isInstalling,
				interactionSource = interaction,
				colors = IconButtonDefaults.iconButtonColors(
					containerColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
					contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
	onDownload: (AppUpdate) -> Unit = {},
	onCancel: (Int) -> Unit = {}
) = Card(
	modifier = Modifier.fillMaxWidth(),
	shape = RoundedCornerShape(20.dp),
	colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, uri = app.iconUri.takeIf { it != Uri.EMPTY }, source = app.source, onSourceClick = onSourceClick, fileSize = app.link.fileSize)
		WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().focusGroup().padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
		) {
			if (!app.isInstalled) {
				TvIgnoreVersionButton(app, onIgnoreVersion)
			} else {
				TvHideButton { onHide(app.id) }
			}
			TvDownloadButton(app, onDownload)
			TvInstallButton(app, onInstall, onOpen, onCancel)
		}
	}
}

@Composable
fun TvSearchItem(
	app: AppUpdate,
	onInstall: (String) -> Unit = {},
	onOpen: (String) -> Unit = {},
	onSourceClick: (() -> Unit)? = null,
	onDownload: (AppUpdate) -> Unit = {},
	onCancel: (Int) -> Unit = {}
) = Card(
	modifier = Modifier.fillMaxWidth(),
	shape = RoundedCornerShape(20.dp),
	colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
) {
	Column {
		TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, app.iconUri, true, source = app.source, onSourceClick = onSourceClick, fileSize = app.link.fileSize)
		WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().focusGroup().padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
		) {
			TvDownloadButton(app, onDownload)
			TvInstallButton(app, onInstall, onOpen, onCancel, isSearch = true)
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
