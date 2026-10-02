package com.apkupdater.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.apkupdater.R
import com.apkupdater.ui.theme.Design
import com.apkupdater.util.isAndroidTv


/**
 * The shape the row being composed should take: set by [SettingsGroup] from the row's place in
 * its group. Outside a group a row is drawn as a group of one.
 */
private val LocalSettingsRowShape = staticCompositionLocalOf<Shape> { Design.groupRowShape(0, 1) }

/** What [SettingsGroup]'s builder collects: one entry per row, in order. */
class SettingsGroupScope internal constructor() {
	internal val rows = mutableListOf<Pair<Any, @Composable () -> Unit>>()

	/**
	 * A row of the group. [key] must be unique within the group and must not change: the rows
	 * are composed under it, so a row that appears or disappears above another (the APKMirror
	 * download switch, the alarm settings) leaves the others — and the D-pad focus on one of
	 * them — where they are.
	 */
	fun row(key: Any, content: @Composable () -> Unit) {
		rows += key to content
	}
}

/**
 * A group of settings rows (build 179, redesign stage 3, variant A): each row its own rounded
 * surface, 2 dp apart, the group's outer corners big and the ones between rows small.
 *
 * The rows are declared in a builder rather than as plain content because each one has to know
 * where it stands — first, last, between — to draw its corners; see Design.groupRowShape for why
 * the group does not simply clip them. Conditions in the builder are read during this group's
 * composition, so a row that comes and goes is followed like any other state.
 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
	val rows = SettingsGroupScope().apply(content).rows
	Column(
		modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(Design.GroupGap)
	) {
		rows.forEachIndexed { index, (rowKey, row) ->
			key(rowKey) {
				CompositionLocalProvider(LocalSettingsRowShape provides Design.groupRowShape(index, rows.size)) {
					row()
				}
			}
		}
	}
}

/**
 * A row's surface in its group: the tonal fill of the redesign's tappable surfaces, or — while
 * it holds the D-pad — the app's [TvFocus] fill and frame, drawn in the row's own shape. Clipped
 * to that shape too, so the press ripple on a phone stays inside the corners.
 */
@Composable
private fun Modifier.settingsRowSurface(focused: Boolean, selected: Boolean = false): Modifier {
	val shape = LocalSettingsRowShape.current
	val fill = when {
		focused -> TvFocus.fill
		// Not secondaryContainer: that is the icon chip's own colour, and the chip vanished into it.
		selected -> MaterialTheme.colorScheme.primaryContainer
		else -> MaterialTheme.colorScheme.surfaceContainerHigh
	}
	return this
		.fillMaxWidth()
		.clip(shape)
		.background(fill, shape)
		.then(if (focused) Modifier.border(TvFocus.stroke, shape) else Modifier)
}

/**
 * For a row whose focus lives in a control inside it — a text field, the segmented theme picker,
 * a slider: true while anything in the row holds the D-pad, so the row shows the same fill and
 * frame as every other focused row. TV only: on a phone a tapped text field is focused too, and
 * would get a frame round its row while typing.
 */
@Composable
private fun rememberRowFocus(): Pair<Boolean, Modifier> {
	var focused by remember { mutableStateOf(false) }
	val isTv = LocalContext.current.isAndroidTv()
	return (focused && isTv) to Modifier.onFocusChanged { focused = it.hasFocus }
}

/**
 * A press ripple on a phone, nothing on a TV. There the row's fill and frame already say where
 * the D-pad is, and Material's own focus layer would only show through them as a paler patch.
 */
@Composable
private fun rowIndication() = if (LocalContext.current.isAndroidTv()) null else LocalIndication.current

/** The icon, title and subtitle every settings row starts with. */
@Composable
private fun RowScope.RowLabel(@DrawableRes icon: Int, text: String, subtitle: String?) {
	IconChip(icon)
	Spacer(Modifier.width(14.dp))
	Column(Modifier.weight(1f)) {
		Text(text)
		if (subtitle != null) {
			Text(
				subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

@Composable
fun SliderSetting(
	getValue: () -> Float,
	setValue: (Float) -> Unit,
	text: String,
	valueRange: ClosedFloatingPointRange<Float>,
	steps: Int,
	@DrawableRes icon: Int
) {
	val (focused, watch) = rememberRowFocus()
	Column(watch.settingsRowSurface(focused).padding(horizontal = 12.dp, vertical = 10.dp)) {
		var position by remember { mutableFloatStateOf(getValue()) }
		Row(verticalAlignment = CenterVertically) {
			RowLabel(icon, text, null)
			Text("${position.toInt()}", color = MaterialTheme.colorScheme.primary)
		}
		Slider(
			value = position,
			valueRange = valueRange,
			steps = steps,
			onValueChange = {
				position = it
				setValue(it)
			},
			modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedButtonSetting(
	text: String,
	options: List<String>,
	getValue: () -> Int,
	setValue: (Int) -> Unit,
	@DrawableRes icon: Int = R.drawable.ic_system
) {
	val (focused, watch) = rememberRowFocus()
	Column(watch.settingsRowSurface(focused).padding(horizontal = 12.dp, vertical = 10.dp)) {
		var position by remember { mutableIntStateOf(getValue()) }
		Row(verticalAlignment = CenterVertically) {
			RowLabel(icon, text, null)
		}
		SingleChoiceSegmentedButtonRow(Modifier.padding(top = 10.dp).fillMaxWidth()) {
			options.forEachIndexed { index, label ->
				SegmentedButton(
					shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
					onClick = {
						position = index
						setValue(position)
					},
					selected = index == position
				) {
					Text(label)
				}
			}
		}
	}
}

@Composable
fun SwitchSetting(
	getValue: () -> Boolean,
	setValue: (Boolean) -> Unit,
	text: String,
	@DrawableRes icon: Int = R.drawable.ic_system,
	modifier: Modifier = Modifier
) {
	var value by remember { mutableStateOf(getValue()) }
	SwitchSetting(
		checked = value,
		onCheckedChange = {
			setValue(it)
			value = getValue()
		},
		text = text,
		icon = icon,
		modifier = modifier
	)
}

/**
 * The same row with the value held by the caller: for a switch whose change goes through a
 * dialog, or that shows or hides other rows. The row is never re-created to show a new value —
 * re-creating it (with a changing key()) dropped the D-pad focus off the page on a TV.
 *
 * The whole row is the switch since build 179: a tap anywhere on it toggles, as in Android's own
 * settings, where it used to take a tap on the small switch itself. That also leaves the row as
 * the one focus target on a TV, so its fill and frame follow the row's shape — the Switch is
 * drawn for its state only and takes no input of its own.
 */
@Composable
fun SwitchSetting(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	text: String,
	@DrawableRes icon: Int = R.drawable.ic_system,
	modifier: Modifier = Modifier,
	subtitle: String? = null
) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	Row(
		modifier
			.settingsRowSurface(focused)
			.toggleable(
				value = checked,
				interactionSource = interaction,
				indication = rowIndication(),
				role = Role.Switch,
				onValueChange = onCheckedChange
			)
			.heightIn(min = 60.dp)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = CenterVertically
	) {
		// weight(1f) inside RowLabel reserves space for the switch, so long labels (Russian)
		// wrap instead of being drawn underneath it.
		RowLabel(icon, text, subtitle)
		Switch(
			checked = checked,
			onCheckedChange = null,
			modifier = Modifier.padding(start = 8.dp)
		)
	}
}

/**
 * A choice from a short list — the check hour on a TV, the check frequency. Since build 179 it is
 * an ordinary row that opens a menu, the current choice shown at its end.
 *
 * It used to be a read-only text field with a click handler on it. That is two focus targets
 * stacked, and on a TV the inner one took the focus and ate the short OK press, so only a LONG
 * press opened it — the fault TvTextField fixed in build 153. A row is one target; the menu is a
 * focusable popup, so the D-pad goes into it.
 */
@Composable
fun DropDownSetting(
	text: String,
	options: List<String>,
	getValue: () -> Int,
	setValue: (Int) -> Unit,
	@DrawableRes icon: Int
) {
	var expanded by remember { mutableStateOf(false) }
	var selected by remember { mutableIntStateOf(getValue()) }
	SettingsRow(text, null, icon, onClick = { expanded = true }) {
		// Anchored to the value, so the menu opens at the row's end, where the eye already is.
		Box {
			Row(verticalAlignment = CenterVertically) {
				Text(options.getOrElse(selected) { "" }, color = MaterialTheme.colorScheme.primary)
				Icon(
					Icons.Filled.ArrowDropDown,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
				options.forEachIndexed { i, option ->
					DropdownMenuItem(
						text = { Text(option) },
						onClick = {
							selected = i
							expanded = false
							setValue(i)
						}
					)
				}
			}
		}
	}
}

/**
 * The title over a group: in the accent colour, lined up with the icons of the rows under it.
 * The divider it used to carry is gone — the gap between groups already separates them.
 */
@Composable
fun SectionHeader(text: String) = Text(
	text,
	style = MaterialTheme.typography.titleSmall,
	color = MaterialTheme.colorScheme.primary,
	modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 2.dp)
)

/**
 * A row whose whole job is one action — export, import, copy the logs. The whole row is the
 * button, with the action's icon at its end. It used to be a label and a small icon button
 * beside it: two things to aim at on a phone, and on a TV a 40 dp circle as the only focus mark.
 */
@Composable
fun ButtonSetting(
	text: String,
	onClick: () -> Unit,
	@DrawableRes icon: Int,
	@DrawableRes iconButton: Int,
	modifier: Modifier = Modifier
) = SettingsRow(text, null, icon, modifier, onClick = onClick) {
	Icon(
		painterResource(iconButton),
		contentDescription = null,
		tint = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.size(20.dp)
	)
}

/**
 * A tappable row with a title, an optional subtitle and an icon — the Settings front page rows
 * that open a category, and inside a category any row whose whole job is one action.
 *
 * The subtitle carries the state worth knowing without opening anything: how many sources are
 * enabled, which installer is in use, where downloads are saved.
 *
 * Set [trailingArrow] false for a row that acts rather than navigates. One action per row and
 * nothing focusable nested inside it, so the D-pad has exactly one stop here — that is why the
 * download folder is offered as two rows (pick, reset) instead of a row with a clear button.
 */
@Composable
fun SettingsCategory(
	text: String,
	subtitle: String?,
	@DrawableRes icon: Int,
	modifier: Modifier = Modifier,
	trailingArrow: Boolean = true,
	onClick: () -> Unit
) = SettingsRow(text, subtitle, icon, modifier, onClick = onClick) {
	if (trailingArrow) {
		Icon(
			Icons.AutoMirrored.Filled.KeyboardArrowRight,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

/**
 * The tappable row under [SettingsCategory], [ButtonSetting] and [DropDownSetting], open for rows
 * of their own: a custom repository, with its delete button as [trailing]. [selected] marks the
 * one row a screen is working on — the repository loaded into the form above for editing.
 *
 * A focusable [trailing] sits INSIDE the row's own focus target, and a D-pad move never looks
 * there: the caller routes RIGHT to it and LEFT back with focusProperties, as the custom
 * repositories do.
 */
@Composable
fun SettingsRow(
	text: String,
	subtitle: String?,
	@DrawableRes icon: Int,
	modifier: Modifier = Modifier,
	selected: Boolean = false,
	onClick: () -> Unit,
	trailing: @Composable () -> Unit
) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	Row(
		modifier
			.settingsRowSurface(focused, selected)
			.clickable(interactionSource = interaction, indication = rowIndication()) { onClick() }
			.heightIn(min = 64.dp)
			.padding(horizontal = 12.dp, vertical = 10.dp),
		verticalAlignment = CenterVertically
	) {
		RowLabel(icon, text, subtitle)
		Box(Modifier.padding(start = 8.dp)) { trailing() }
	}
}

/**
 * A row that holds something with its own focus and input — a text field — on the group's
 * surface. The row itself takes no focus.
 */
@Composable
fun SettingsContentRow(content: @Composable () -> Unit) {
	val (focused, watch) = rememberRowFocus()
	Box(watch.settingsRowSurface(focused).padding(horizontal = 12.dp, vertical = 8.dp)) {
		content()
	}
}
