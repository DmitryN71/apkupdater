package com.apkupdater.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.apkupdater.R
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.ApkMirrorSource
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.ReleaseType
import com.apkupdater.data.ui.Source
import com.apkupdater.util.getAppName
import kotlinx.coroutines.delay
import com.apkupdater.util.installerLabel
import com.apkupdater.util.isAndroidTv
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import com.apkupdater.util.toAnnotatedString
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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

/**
 * Warns that this version is not a finished release. Deliberately the only coloured chip in the
 * row — size and date are neutral surfaceVariant, so the eye goes to this one and only when
 * there is something to say. Stable draws nothing at all.
 */
@Composable
fun ReleaseTypeChip(releaseType: ReleaseType, modifier: Modifier = Modifier) {
	val label = releaseType.labelRes ?: return
	Text(
		stringResource(label),
		color = MaterialTheme.colorScheme.onTertiaryContainer,
		style = MaterialTheme.typography.labelSmall,
		maxLines = 1,
		modifier = modifier
			.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
			.padding(horizontal = 8.dp, vertical = 2.dp)
	)
}

/**
 * Where the user last installed this app from, through this app. Says "installed from here" on
 * the card whose source matches — the one to pick again when the same app is listed from several
 * — and "last time: X" on the others. Draws nothing when there is no record. Informs, decides
 * nothing: no card is hidden or reordered by it.
 */
@Composable
fun ProvenanceChip(installedFrom: String, source: Source?, modifier: Modifier = Modifier) {
	if (installedFrom.isEmpty()) return
	val fromHere = source != null && source.name == installedFrom
	val text = if (fromHere) stringResource(R.string.installed_from_here)
		else stringResource(R.string.installed_last_from, installedFrom)
	val background = if (fromHere) MaterialTheme.colorScheme.secondaryContainer
		else MaterialTheme.colorScheme.surfaceVariant
	val content = if (fromHere) MaterialTheme.colorScheme.onSecondaryContainer
		else MaterialTheme.colorScheme.onSurfaceVariant
	Text(
		text,
		color = content,
		style = MaterialTheme.typography.labelSmall,
		maxLines = 1,
		modifier = modifier
			.background(background, RoundedCornerShape(12.dp))
			.padding(horizontal = 8.dp, vertical = 2.dp)
	)
}

/**
 * Which store put this app on the device. Quiet on purpose — it is context, not a warning, and
 * the eye should still go to the release-type chip when there is one.
 */
@Composable
fun InstallerChip(installer: String, modifier: Modifier = Modifier) {
	Row(
		modifier = modifier
			.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
			.padding(horizontal = 8.dp, vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp)
	) {
		Icon(
			Icons.Outlined.Inventory2,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(13.dp)
		)
		Text(
			installer,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			style = MaterialTheme.typography.labelSmall,
			maxLines = 1
		)
	}
}

@Composable
fun DateChip(date: String, modifier: Modifier = Modifier) {
	if (date.isNotBlank()) {
		Row(
			modifier = modifier
				.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
				.padding(horizontal = 8.dp, vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			Icon(
				Icons.Outlined.Schedule,
				contentDescription = stringResource(R.string.updated_on, ""),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(13.dp)
			)
			Text(
				date,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				style = MaterialTheme.typography.labelSmall
			)
		}
	}
}

@Composable
fun SourceChip(source: Source, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
	val shape = RoundedCornerShape(50)
	val clickable = onClick != null
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	// Clickable chips read as a link (primary tonal + open-in-new icon). On TV,
	// D-pad focus fills the pill solid primary — the same clean highlight the
	// action buttons use, instead of a rectangular focus box.
	val background = when {
		focused -> MaterialTheme.colorScheme.primary
		clickable -> MaterialTheme.colorScheme.primaryContainer
		else -> MaterialTheme.colorScheme.secondaryContainer
	}
	val foreground = when {
		focused -> MaterialTheme.colorScheme.onPrimary
		clickable -> MaterialTheme.colorScheme.onPrimaryContainer
		else -> MaterialTheme.colorScheme.onSecondaryContainer
	}
	Row(
		modifier = modifier
			.background(background, shape)
			.then(if (clickable) Modifier.clickable(interactionSource = interaction, indication = null) { onClick() } else Modifier)
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

/**
 * A horizontal-scroll modifier that walks its content to the end and back whenever the content
 * is wider than the space it was given.
 *
 * The version row has behaved this way for a long time; the compact card needs the same for the
 * app name, which is the one thing there that cannot be shortened without losing the point. The
 * walk itself is opt-in through the text-animations setting — text that never stops moving is
 * hard to read, and worse on a screen you glance at.
 */
@Composable
private fun bounceScroll(animate: Boolean): Modifier {
	val scrollState = rememberScrollState()
	val overflow = scrollState.maxValue
	if (overflow > 0 && animate) {
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
	return Modifier.horizontalScroll(scrollState)
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
	fileSize: Long = 0L,
	updateDate: String = "",
	releaseType: ReleaseType = ReleaseType.Stable,
	chipRightFocus: FocusRequester? = null,
	// Decided by the caller, not read from the setting here: a card the user has tapped open
	// must show the full layout even though the setting still says compact. The Apps tab passes
	// true unconditionally — it is a list you scroll looking for a name and its card has one
	// action.
	compact: Boolean = false,
	// Compact drops the package name: on Updates and Search it is the least-read line on the
	// card, and one tap brings the whole full layout back. The Apps tab has neither of those —
	// nothing there expands, and its own search box matches on the package (AppsScreen), so
	// hiding it would leave people searching for text they cannot see.
	showPackageName: Boolean = false,
	/** Already-readable name of the installing store, "" to draw nothing. See installerLabel. */
	installer: String = "",
	/** Source.name this app was last installed from by us, "" if never — see ProvenanceChip. */
	installedFrom: String = "",
) {
	// Read once, unconditionally — get<Prefs>() is @Composable and must not be
	// called behind a short-circuit (overflow flips 0→N after layout measures).
	val animateText = get<Prefs>().playTextAnimations.get()
	if (compact) {
		CompactCommonItem(
			packageName, name, version, oldVersion, uri, single, source, onSourceClick,
			fileSize, releaseType, chipRightFocus, animateText, showPackageName, installer,
			installedFrom
		)
	} else {
		FullCommonItem(
			packageName, name, version, oldVersion, versionCode, oldVersionCode, uri, single,
			source, onSourceClick, fileSize, updateDate, releaseType, chipRightFocus, animateText,
			installedFrom
		)
	}
}

/**
 * The card as it has always been: a 100 dp icon with the source under it, the package name, both
 * version codes, and room for the changelog underneath.
 */
@Composable
private fun FullCommonItem(
	packageName: String,
	name: String,
	version: String,
	oldVersion: String?,
	versionCode: Long,
	oldVersionCode: Long?,
	uri: Uri?,
	single: Boolean,
	source: Source?,
	onSourceClick: (() -> Unit)?,
	fileSize: Long,
	updateDate: String,
	releaseType: ReleaseType,
	chipRightFocus: FocusRequester?,
	animateText: Boolean,
	installedFrom: String
) = Row(Modifier.padding(12.dp)) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		if (uri == null) {
			LoadingImageApp(packageName, Modifier.height(100.dp))
		} else {
			LoadingImage(uri, Modifier.height(100.dp))
		}
		if (source != null) {
				// When wired, D-pad RIGHT from the chip jumps to this card's action buttons
				// (overrides geometric/grid focus search so it can't leak to the next column
				// or the bottom nav bar).
				val chipMod = Modifier.padding(top = 6.dp).then(
					if (chipRightFocus != null) Modifier.focusProperties { right = chipRightFocus } else Modifier
				)
				SourceChip(source, chipMod, onClick = onSourceClick)
		}
	}
	Column(Modifier.align(Alignment.CenterVertically).padding(start = 12.dp)) {
		LargeTitle(name.ifEmpty { LocalContext.current.getAppName(packageName) }.ifEmpty { packageName })
		MediumText(packageName, Modifier.alpha(0.6f))
		if (oldVersion != null && !single) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				modifier = Modifier.padding(top = 4.dp).then(bounceScroll(animateText))
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
		// Download size + source release date, as chips in one row (Obtainium-style).
		Row(
			Modifier.padding(top = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			ReleaseTypeChip(releaseType)
			SizeChip(fileSize)
			DateChip(updateDate)
			ProvenanceChip(installedFrom, source)
		}
	}
}

/**
 * Two lines and a 48 dp icon, for people who would rather see eight apps than two.
 *
 * What it drops is the material that is either derivable or rarely read: both version codes
 * (the version names say the same thing in words), the release date, and — unless the caller
 * asks for it back — the package name. What it keeps is everything the choice to update rests
 * on: which app, from which source, to which version, how big, and whether it is a beta.
 *
 * The changelog is dropped by the callers, not here: only the Updates card has one.
 */
@Composable
private fun CompactCommonItem(
	packageName: String,
	name: String,
	version: String,
	oldVersion: String?,
	uri: Uri?,
	single: Boolean,
	source: Source?,
	onSourceClick: (() -> Unit)?,
	fileSize: Long,
	releaseType: ReleaseType,
	chipRightFocus: FocusRequester?,
	animateText: Boolean,
	showPackageName: Boolean,
	installer: String,
	installedFrom: String
) = Row(
	Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
	verticalAlignment = Alignment.CenterVertically
) {
	// 52 dp of box with a 2 dp inset leaves 48 dp of icon — a launcher-sized icon, and twice
	// what the first attempt drew. The default 10 dp inset is written for the 100 dp card.
	if (uri == null) {
		LoadingImageApp(packageName, Modifier.size(52.dp), padding = 2.dp)
	} else {
		LoadingImage(uri, Modifier.size(52.dp), padding = 2.dp)
	}
	// weight(1f), or this column takes the whole row: a Row measures its unweighted children
	// first, each against everything that is left, and only then divides the remainder among
	// the weighted ones. Unweighted here, the column would leave nothing for the button beside
	// it on the Apps tab.
	Column(Modifier.padding(start = 10.dp).weight(1f)) {
		// Scrolls rather than ending in an ellipsis: "1Password: Password Ma…" is the real
		// case, and a truncated name is worse here than in the full card, where the package
		// name underneath said which app it was. titleLarge would eat the height the smaller
		// icon just saved.
		Text(
			name.ifEmpty { LocalContext.current.getAppName(packageName) }.ifEmpty { packageName },
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Bold,
			maxLines = 1,
			softWrap = false,
			modifier = Modifier.fillMaxWidth().then(bounceScroll(animateText))
		)
		// Scrollable rather than wrapping: a second line here would undo the whole point, and
		// a long version name is exactly what would cause one.
		Row(
			Modifier.padding(top = 3.dp).then(bounceScroll(animateText)),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			if (oldVersion != null && !single) {
				VersionChip(oldVersion, isNew = false)
				Icon(
					Icons.AutoMirrored.Filled.ArrowRightAlt,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(14.dp)
				)
			}
			VersionChip(version, isNew = !single)
			ReleaseTypeChip(releaseType)
			SizeChip(fileSize)
			if (source != null) {
				// Down here rather than beside the name, and for the measurement reason above:
				// the chip is unweighted, so on the name row it was served first and a source
				// like "F-Droid (Izzy)" ate the space the name needed. In this row nothing is
				// weighted and the row scrolls, so it can starve nothing. D-pad RIGHT from the
				// chip still reaches this card's action buttons.
				val chipMod = if (chipRightFocus != null) {
					Modifier.focusProperties { right = chipRightFocus }
				} else {
					Modifier
				}
				SourceChip(source, chipMod, onClick = onSourceClick)
			}
			// Both last, and inside the row that already scrolls, so they cost no height — the
			// whole point of the compact card is that it is two lines and stays two lines.
			ProvenanceChip(installedFrom, source)
			if (installer.isNotEmpty()) InstallerChip(installer)
			if (showPackageName) MediumText(packageName, Modifier.alpha(0.6f))
		}
	}
}

@Composable
fun TvInstallButton(
	app: AppUpdate,
	onInstall: (String) -> Unit,
	onOpen: (String) -> Unit = {},
	onCancel: (Int) -> Unit = {},
	isSearch: Boolean = false,
	modifier: Modifier = Modifier
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
		modifier = modifier,
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
				// Whole percent in a fixed-width slot. Two decimals made the label a
				// different width on every tick, so the button kept resizing and the
				// End-aligned row of buttons visibly jittered for the whole download.
				val p = ((app.progress.toFloat() / app.total) * 100f).coerceIn(0f, 100f)
				Text(
					"${p.toInt()}%",
					maxLines = 1,
					textAlign = TextAlign.Center,
					modifier = Modifier.widthIn(min = 36.dp)
				)
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

/**
 * A card that outlines itself while ANY control inside it holds D-pad focus.
 *
 * Until now only the control itself lit up, and on a couch-distance screen a 40dp pill changing
 * colour inside a half-screen-wide card is genuinely hard to find — reported on 4PDA as "фокус
 * подсвечивается блекло". The complaint is not about the colour being pale; it is about the lit
 * area being tiny relative to the card.
 *
 * This is NOT the focus ring that was rejected in build 107. That one wrapped a *button*, whose
 * invisible 48dp touch target is taller than the visible 40dp pill, so the rectangle stuck out.
 * A Card's layout bounds are exactly the card you see, and the border is given the card's own
 * shape, so the outline traces it precisely. The inner control keeps its solid fill, which gives
 * two levels of feedback at once: which card, and which button within it.
 *
 * `hasFocus` — not `isFocused` — is what lets a parent react to a focused descendant.
 */
@Composable
fun TvFocusCard(
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit
) {
	var focused by remember { mutableStateOf(false) }
	val shape = RoundedCornerShape(20.dp)
	val glow = MaterialTheme.colorScheme.inverseSurface
	Card(
		modifier = modifier
			.fillMaxWidth()
			// The build-134 glow, restored. Four variants were tried on an actual TV: a 3dp
			// outline (130) read as excessive, this one, a faint halo of concentric strokes
			// (135) was invisible, and a stronger halo (136) banded into a visible stack of
			// outlines. This is the one that looked right on the screen, so it wins.
			//
			// It IS slightly lopsided — heavier below and toward the screen edge — and that is
			// unavoidable: an elevation shadow models a light source above the display, so it
			// falls away from that light and cannot be centred. Noticed on 4PDA and judged not
			// critical there either. clip = false so the glow falls outside the card, which is
			// the whole point; colored shadows need API 28+, older Android draws plain black.
			.then(
				if (focused) Modifier.shadow(
					elevation = 16.dp,
					shape = shape,
					clip = false,
					ambientColor = glow,
					spotColor = glow
				) else Modifier
			)
			.onFocusChanged { focused = it.hasFocus },
		shape = shape,
		colors = CardDefaults.cardColors(
			containerColor = if (focused) MaterialTheme.colorScheme.surfaceContainerHighest
			else MaterialTheme.colorScheme.surfaceContainerHigh
		),
		content = content
	)
}

@Composable
fun TvInstalledItem(
	app: AppInstalled,
	onIgnore: (String) -> Unit = {},
	onOpenInfo: (String) -> Unit = {},
	onFindUpdates: (AppInstalled) -> Unit = {}
) = TvFocusCard(
	Modifier.alpha(if (app.ignored) 0.5f else 1f)
) {
	// Always compact, whatever the setting says, and the button shares the row rather than
	// claiming one of its own — that row costs about 50 dp, which on this tab is most of the
	// card. Nothing is lost by it: this list has one action and no changelog, and the package
	// name is asked for explicitly below, so the name, the package and the version all fit.
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		// Touch only, for the same reason as the update cards: clickable is focusable on a
		// D-pad but not on a finger, and on a television it would add a stop with no highlight.
		val tappable = !LocalContext.current.isAndroidTv()
		Box(
			Modifier
				.weight(1f)
				.then(if (tappable) Modifier.clickable { onOpenInfo(app.packageName) } else Modifier)
		) {
			TvCommonItem(
				app.packageName, app.name, app.version, null, app.versionCode, null,
				compact = true, showPackageName = true,
				// Our own record outranks the system's installer field: for anything this app
				// installed, that field says only "APKUpdater".
				installer = app.installedFrom.ifEmpty { installerLabel(app.installer) }
			)
		}
		FindUpdatesButton(app, onFindUpdates)
		IgnoreAppButton(app, onIgnore, Modifier.padding(end = 4.dp))
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

/**
 * Opens and closes a compact card.
 *
 * It lives in the action row rather than beside the app name, where the first attempt put it.
 * Up there it had to compete for width with a name that can be any length and with the source
 * chip, and a long name simply pushed it off the card — reported straight away. Down here the
 * row is right-aligned with nothing at its left end, so the control has a fixed home, the same
 * shape as the download button next to it, and it can say BOTH things: chevron down to open,
 * chevron up to close, which is what the first version could not do at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvExpandButton(expanded: Boolean, onToggle: () -> Unit) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	val label = stringResource(if (expanded) R.string.collapse_card_cd else R.string.expand_card_cd)
	TooltipBox(
		positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(label) } },
		state = rememberTooltipState()
	) {
		IconButton(
			onClick = onToggle,
			interactionSource = interaction,
			colors = IconButtonDefaults.iconButtonColors(
				containerColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
				contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
			)
		) {
			Icon(
				if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
				contentDescription = label
			)
		}
	}
}

/**
 * An icon, not the labelled button this used to be, and the label lives in its tooltip.
 *
 * A Row measures its UNWEIGHTED children first, each against the whole remaining width, and
 * only then divides what is left among the weighted ones. A text button is unweighted and never
 * wraps, so it took as much as its label wanted — and in Russian that label is "Перестать
 * игнорировать приложение". On a 360 dp phone it claimed about 270 dp of 344, leaving roughly
 * five for the name, the version and the package: an ignored app rendered as an icon and a
 * card-wide button with nothing else on it. English hid this; the forum is Russian.
 *
 * The old layout gave the button a row of its own, where its width cost nothing. The compact
 * row cannot, so the control has to be one that is the same size in every language.
 */
/**
 * Sends one app to the Search tab, already typed in.
 *
 * "Why does this one never update?" is the question the forum asks most, and answering it meant
 * running a nine-source check and reading the whole list. A package name — dotted, no spaces —
 * takes a different path through search entirely: it bypasses ranking and asks each source for
 * that exact package, which is precisely the diagnostic wanted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindUpdatesButton(app: AppInstalled, onFindUpdates: (AppInstalled) -> Unit) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	val label = stringResource(R.string.find_updates_cd)
	TooltipBox(
		positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(label) } },
		state = rememberTooltipState()
	) {
		IconButton(
			onClick = { onFindUpdates(app) },
			interactionSource = interaction,
			colors = IconButtonDefaults.iconButtonColors(
				containerColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
				contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
			)
		) {
			Icon(Icons.Outlined.Search, contentDescription = label)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IgnoreAppButton(
	app: AppInstalled,
	onIgnore: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	val label = stringResource(if (app.ignored) R.string.unignore_cd else R.string.ignore_cd)
	TooltipBox(
		positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(label) } },
		state = rememberTooltipState()
	) {
		IconButton(
			onClick = { onIgnore(app.packageName) },
			interactionSource = interaction,
			modifier = modifier,
			colors = IconButtonDefaults.iconButtonColors(
				containerColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
				contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
			)
		) {
			Icon(
				if (app.ignored) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
				contentDescription = label
			)
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
	onCancel: (Int) -> Unit = {},
	// Set on the FIRST card only, so the screen can put D-pad focus there instead of leaving
	// it on the bottom navigation bar — see UpdatesScreenSuccess.
	firstItemFocus: FocusRequester? = null
) = TvFocusCard {
	Column {
		// Route D-pad RIGHT from the source chip to this card's action buttons instead of
		// letting geometric/grid focus search leak to the next column or the bottom nav bar.
		val actionFocus = remember { FocusRequester() }
		// A compact card opens on a tap anywhere that is not a button, and shows exactly what
		// the full layout shows — which is the point: compact hides the changelog, and the
		// changelog is often the reason to update at all.
		//
		// rememberSaveable, keyed by nothing of ours: the lazy grid stores each item's saveable
		// state under the item key it was given, so a card left open is still open after
		// scrolling away and back. The tap is on the information row only, so the buttons
		// underneath keep their own clicks and their own D-pad focus; in the full layout there
		// is nothing to reveal, so no click is attached at all and that path is untouched.
		var expanded by rememberSaveable { mutableStateOf(false) }
		val compact = get<Prefs>().compactCards.get()
		val showFull = !compact || expanded
		// fillMaxWidth FIRST: the full layout's Row has no weighted child, so it is only as
		// wide as its contents, and the Box was inheriting that. On a card with a short name
		// and a small icon the right-hand third was outside the clickable area entirely and
		// swallowed the tap — reported as "this card collapses, that one doesn't", which is
		// exactly the difference between a long title and a short one.
		// Touch only. Modifier.clickable delegates a FocusableInNonTouchMode node, which is
		// focusable on a D-pad and not on a finger — so on a television every compact card
		// would gain a focus stop over its information area with no highlight of its own,
		// while the chevron in the action row is already the proper, styled control there.
		val tapToExpand = compact && !LocalContext.current.isAndroidTv()
		Box(Modifier.fillMaxWidth().then(if (tapToExpand) Modifier.clickable { expanded = !expanded } else Modifier)) {
			TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, uri = app.iconUri.takeIf { it != Uri.EMPTY }, source = app.source, onSourceClick = onSourceClick, fileSize = app.link.fileSize, updateDate = app.updateDate, releaseType = app.releaseType, chipRightFocus = actionFocus, compact = !showFull, installedFrom = app.installedFrom)
		}
		if (showFull) WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().focusRequester(actionFocus).focusGroup().padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
		) {
			// Pinned to the far left by the spacer, away from the three that act on the app.
			// It does something different from them — it changes what you are looking at, not
			// what happens to the app — and the gap says so.
			if (compact) {
				TvExpandButton(expanded) { expanded = !expanded }
				Spacer(Modifier.weight(1f))
			}
			if (!app.isInstalled) {
				TvIgnoreVersionButton(app, onIgnoreVersion)
			} else {
				TvHideButton { onHide(app.id) }
			}
			TvDownloadButton(app, onDownload)
			TvInstallButton(
				app, onInstall, onOpen, onCancel,
				modifier = if (firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier
			)
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
) = TvFocusCard {
	Column {
		// Route D-pad RIGHT from the source chip to this card's action buttons instead of
		// letting geometric/grid focus search leak to the next column or the bottom nav bar.
		val actionFocus = remember { FocusRequester() }
		// Tap to open, exactly as on the Updates card — see TvUpdateItem for why.
		var expanded by rememberSaveable { mutableStateOf(false) }
		val compact = get<Prefs>().compactCards.get()
		val showFull = !compact || expanded
		// fillMaxWidth FIRST: the full layout's Row has no weighted child, so it is only as
		// wide as its contents, and the Box was inheriting that. On a card with a short name
		// and a small icon the right-hand third was outside the clickable area entirely and
		// swallowed the tap — reported as "this card collapses, that one doesn't", which is
		// exactly the difference between a long title and a short one.
		// Touch only. Modifier.clickable delegates a FocusableInNonTouchMode node, which is
		// focusable on a D-pad and not on a finger — so on a television every compact card
		// would gain a focus stop over its information area with no highlight of its own,
		// while the chevron in the action row is already the proper, styled control there.
		val tapToExpand = compact && !LocalContext.current.isAndroidTv()
		Box(Modifier.fillMaxWidth().then(if (tapToExpand) Modifier.clickable { expanded = !expanded } else Modifier)) {
			TvCommonItem(app.packageName, app.name, app.version, app.oldVersion, app.versionCode, app.oldVersionCode, app.iconUri, true, source = app.source, onSourceClick = onSourceClick, fileSize = app.link.fileSize, updateDate = app.updateDate, releaseType = app.releaseType, chipRightFocus = actionFocus, compact = !showFull, installedFrom = app.installedFrom)
		}
		if (showFull) WhatsNew(app.whatsNew, app.source)
		HorizontalDivider(
			Modifier.padding(horizontal = 12.dp),
			color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
		)
		Row(
			modifier = Modifier.fillMaxWidth().focusRequester(actionFocus).focusGroup().padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
		) {
			// Pinned to the far left by the spacer, away from the three that act on the app.
			// It does something different from them — it changes what you are looking at, not
			// what happens to the app — and the gap says so.
			if (compact) {
				TvExpandButton(expanded) { expanded = !expanded }
				Spacer(Modifier.weight(1f))
			}
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

/**
 * An icon button that fills solid on D-pad focus.
 *
 * Material's own focus indication on an IconButton is a faint state layer — reported from 4PDA
 * as "с расстояния 1-2 метра сливается с общим фоном", and worse still on a light background.
 * Google's TV focus guide offers four indications — scale, outline, glow, colour — and colour is
 * the one that suits a 40dp icon: scale on a button was rejected in build 107 for drawing beyond
 * its bounds, and an outline hugs the invisible 48dp touch target rather than the visible shape.
 * This is the same treatment the card action buttons have used since build 106.
 */
@Composable
fun TvIconButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit
) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	IconButton(
		onClick = onClick,
		modifier = modifier,
		interactionSource = interaction,
		// inverseSurface, not primary: the settings rows have highlighted this way since 112 and
		// it reads as neutral and system-like rather than branded — dark on a light theme, light on
		// a dark one. Asked for on 4PDA, and it makes the whole interface answer in one voice.
		colors = IconButtonDefaults.iconButtonColors(
			containerColor = if (focused) MaterialTheme.colorScheme.inverseSurface else Color.Transparent,
			contentColor = if (focused) MaterialTheme.colorScheme.inverseOnSurface else LocalContentColor.current
		),
		content = content
	)
}

/**
 * Puts D-pad focus on a screen's first item when that screen appears. TV only.
 *
 * Without it nothing on a freshly opened tab claims focus, so it stays on the bottom navigation:
 * DOWN then does nothing — the bar is already the bottom — and UP runs a geometric search that
 * lands on the LAST visible row and drags the list with it. Reported from a TV for Settings,
 * after the same thing was fixed for Updates in build 130.
 *
 * Always waits a frame first: a lazy list subcomposes its items during layout, which happens
 * after this effect starts, so an immediate request would either throw or bind to a stale node
 * from the previous frame and then lose focus when that node is recycled.
 */
@Composable
fun RequestInitialTvFocus(focusRequester: FocusRequester, enabled: Boolean = true) {
	val isTv = LocalContext.current.isAndroidTv()
	LaunchedEffect(isTv, enabled) {
		if (!isTv || !enabled) return@LaunchedEffect
		repeat(3) {
			delay(150)
			if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
		}
	}
}

