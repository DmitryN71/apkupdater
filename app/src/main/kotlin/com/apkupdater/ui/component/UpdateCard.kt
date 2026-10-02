package com.apkupdater.ui.component

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apkupdater.R
import com.apkupdater.data.ui.ReleaseType
import com.apkupdater.data.ui.Source
import com.apkupdater.prefs.Prefs
import com.apkupdater.ui.theme.Design
import com.apkupdater.util.formatBytes
import com.apkupdater.util.getAppName
import org.koin.androidx.compose.get


/**
 * The top of an update or search card since build 178 (redesign stage 2, variant A — see
 * ui/theme/Design): a 48 dp icon, the name, the version change, and one line saying where the
 * update comes from, how big it is and when it was published.
 *
 * It replaced two layouts. The full one gave a 100 dp icon a third of the card and stacked four
 * rows of chips beside it, with the package name and both version codes on the face of the card;
 * the compact one squeezed everything into a single scrolling row. Both modes share this header
 * now and differ only in whether [CardDetails] is drawn under it. The package name and the version
 * codes moved into [CardDetails]: they are rarely read, and they are one tap away.
 *
 * @param oldVersion the installed version, or null to show the new one alone (Search).
 * @param uri the icon the source gave, or null to use the installed app's own.
 * @param chipRightFocus where D-pad RIGHT goes from the source chip: this card's action buttons.
 * Without it the geometric search leaks to the next column of cards or the bottom bar.
 */
@Composable
fun UpdateCardHeader(
	packageName: String,
	name: String,
	version: String,
	oldVersion: String?,
	uri: Uri?,
	source: Source,
	onSourceClick: (() -> Unit)?,
	fileSize: Long,
	updateDate: String,
	releaseType: ReleaseType,
	installedFrom: String,
	chipRightFocus: FocusRequester?,
	modifier: Modifier = Modifier
) {
	// Read once, unconditionally — get<Prefs>() is @Composable and must not be called behind a
	// short-circuit (overflow flips 0→N after layout measures).
	val animateText = get<Prefs>().playTextAnimations.get()
	Row(modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
		// 52 dp of box with a 2 dp inset leaves 48 dp of icon — launcher-sized.
		if (uri == null) {
			LoadingImageApp(packageName, Modifier.size(52.dp), padding = 2.dp)
		} else {
			LoadingImage(uri, Modifier.size(52.dp), padding = 2.dp)
		}
		// weight(1f), or this column takes the whole row: a Row measures its unweighted children
		// first, each against everything that is left.
		Column(Modifier.padding(start = 12.dp).weight(1f)) {
			// Two lines rather than an ellipsis: with the package name gone from the face of the
			// card, "1Password: Password Ma…" would no longer say which app it is.
			Text(
				name.ifEmpty { LocalContext.current.getAppName(packageName) }.ifEmpty { packageName },
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
			// Scrolls rather than wrapping or cutting: a long version name is exactly what would
			// push the new version, the one that matters, out of sight.
			Row(
				Modifier.padding(top = 2.dp).then(bounceScroll(animateText)),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				if (oldVersion != null) {
					Text(
						"$oldVersion →",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						softWrap = false
					)
				}
				Text(
					version,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					color = if (oldVersion != null) MaterialTheme.colorScheme.primary
						else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					softWrap = false
				)
				ReleaseTypeChip(releaseType)
			}
			Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
				val chipModifier = if (chipRightFocus != null) {
					Modifier.focusProperties { right = chipRightFocus }
				} else {
					Modifier
				}
				SourceChip(source, chipModifier, onClick = onSourceClick)
				// Served after the chip and allowed to shrink, so a long source name such as
				// "F-Droid (Izzy)" costs the date its tail, never the chip its name.
				val meta = listOfNotNull(
					fileSize.takeIf { it > 0L }?.let(::formatBytes),
					updateDate.takeIf { it.isNotBlank() }
				).joinToString(" · ")
				if (meta.isNotEmpty()) {
					Text(
						meta,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false)
					)
				}
			}
			// On its own line: in the meta row it would be served last and cut off mid-word —
			// "Ставили" instead of "Ставили отсюда" — which is what Maximoff reported against 157.
			ProvenanceChip(installedFrom, source, Modifier.padding(top = 6.dp))
		}
	}
}

/**
 * The box under the card's header: the changelog, three lines of it until opened, and once
 * opened also the package name and the version codes that used to sit on the face of the card.
 * With no changelog it is a one-line "Details" that opens to the same two facts.
 *
 * Opened and closed by a tap anywhere on it, or OK on a TV, where it is one focus stop — as the
 * changelog text was before it. Focus draws a primary frame round the box, the update cards'
 * own focus language (TvFocus covers everything outside them). rememberSaveable, so a box left
 * open is still open after scrolling away and back: the lazy grid keeps each item's saveable
 * state under its key.
 *
 * Set a shade below the card, which works whichever way the card moves: it lightens to
 * surfaceContainerHighest while it holds the D-pad, and a box one step above it would vanish
 * into it then.
 */
@Composable
fun CardDetails(
	whatsNew: String,
	packageName: String,
	versionCode: Long,
	/** The installed version's code, or null where there is none to show (Search). */
	oldVersionCode: Long?,
	modifier: Modifier = Modifier
) {
	val changelog = rememberChangelog(whatsNew)
	val hasChangelog = changelog.text.isNotBlank()
	var expanded by rememberSaveable { mutableStateOf(false) }
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	Surface(
		onClick = { expanded = !expanded },
		modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
		shape = Design.InsetShape,
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
		interactionSource = interaction
	) {
		Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				// A heading, and drawn as one: the first build had it as a 12 sp grey label, which
				// read as a caption of the text under it rather than the title of the box (Dmitry,
				// on his phone).
				Text(
					stringResource(if (hasChangelog) R.string.whats_new else R.string.card_details),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.weight(1f)
				)
				Icon(
					if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
					contentDescription = stringResource(if (expanded) R.string.show_less_cd else R.string.show_more_cd),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(20.dp)
				)
			}
			if (hasChangelog) {
				Text(
					changelog,
					style = MaterialTheme.typography.bodySmall,
					maxLines = if (expanded) Int.MAX_VALUE else 3,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 4.dp)
				)
			}
			if (expanded) {
				Spacer(Modifier.height(6.dp))
				Text(
					packageName,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				// Some sources do not give a version code at all; 0 is "unknown", never a code.
				val code = when {
					oldVersionCode != null -> "$oldVersionCode → ${if (versionCode == 0L) "?" else versionCode}"
					versionCode != 0L -> versionCode.toString()
					else -> null
				}
				if (code != null) {
					Text(
						stringResource(R.string.version_code, code),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		}
	}
}

/**
 * How far a download has got, across the card above its buttons: a bar and "20.3 MB of 45.2 MB".
 *
 * Until build 178 the percentage was the label of the Update button itself, which made the
 * button a different width on most ticks; this leaves the button saying "Cancel" and nothing
 * else. Before the size is known — the source is still being asked for the file, or APKMirror's
 * chain of pages is being walked — the bar runs without a figure and the line says so. A server
 * that never says the size gets a running bar and the bytes so far.
 */
@Composable
fun DownloadProgress(progress: Long, total: Long, modifier: Modifier = Modifier) {
	Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
		val known = total > 0L
		val done = progress.coerceIn(0L, total.coerceAtLeast(0L))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				when {
					known -> stringResource(R.string.download_progress, formatBytes(done), formatBytes(total))
					// A server that sends no Content-Length leaves the total at 0 for the whole
					// download; the bytes still count up, and "Preparing…" would be a lie by then.
					progress > 0L -> formatBytes(progress)
					else -> stringResource(R.string.download_preparing)
				},
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f)
			)
			if (known) {
				Text(
					"${(done * 100 / total).toInt()}%",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		Spacer(Modifier.height(4.dp))
		if (known) {
			LinearProgressIndicator(
				progress = { done.toFloat() / total },
				modifier = Modifier.fillMaxWidth()
			)
		} else {
			LinearProgressIndicator(Modifier.fillMaxWidth())
		}
	}
}
