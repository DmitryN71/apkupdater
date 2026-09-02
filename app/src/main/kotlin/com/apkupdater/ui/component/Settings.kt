package com.apkupdater.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterEnd
import androidx.compose.ui.Alignment.Companion.CenterStart
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Alignment.Companion.Start
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apkupdater.R


@Composable
fun SliderSetting(
    getValue: () -> Float,
    setValue: (Float) -> Unit,
    text: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    @DrawableRes icon: Int
) = Row(
    Modifier
        .fillMaxWidth()
        .height(70.dp)
        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
) {
    var position by remember { mutableFloatStateOf(getValue()) }
    Icon(painterResource(id = icon), text, Modifier.align(CenterVertically))
    Column(Modifier.padding(start = 8.dp).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Text(text, Modifier.align(CenterStart).padding(start = 8.dp))
            Text("${getValue().toInt()}", Modifier.align(CenterEnd).padding(end = 8.dp))
        }
        Slider(
            value = position,
            valueRange = valueRange,
            steps = steps,
            onValueChange = {
                position = it
                setValue(it)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
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
) = Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
    var position by remember { mutableIntStateOf(getValue()) }
    Icon(painterResource(id = icon), text, Modifier.align(CenterVertically))
    Column(Modifier.padding(start = 8.dp).fillMaxWidth()) {
        Text(text, Modifier.align(Start).padding(start = 8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.padding(8.dp).fillMaxWidth()) {
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
    @DrawableRes icon: Int = R.drawable.ic_system
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Android-TV-style D-pad focus: the whole row fills with the inverse surface color and
    // the label/icon invert. A border ring around the Switch was tried and rejected — it
    // hugs the invisible 48dp touch target, not the visible pill, so it looked lopsided;
    // and the switch's own thumb halo is internal to Material and can't be amplified.
    // A full-width row fill can't look crooked and is clearly visible from a couch.
    val rowColor = if (focused) MaterialTheme.colorScheme.inverseSurface else Color.Transparent
    val contentColor = if (focused) MaterialTheme.colorScheme.inverseOnSurface else LocalContentColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(rowColor, RoundedCornerShape(16.dp))
            .heightIn(min = 60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = CenterVertically
    ) {
        var value by remember { mutableStateOf(getValue()) }
        Icon(
            painterResource(id = icon),
            text,
            Modifier.padding(end = 16.dp).size(24.dp),
            tint = contentColor
        )
        // weight(1f) reserves space for the switch so long labels (e.g. Russian) wrap
        // instead of being drawn underneath it.
        Text(text, Modifier.weight(1f), color = contentColor)
        Switch(
            checked = value,
            onCheckedChange = {
                setValue(it)
                value = getValue()
            },
            interactionSource = interaction,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Suppress("unused")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropDownSetting(
    text: String,
    options: List<String>,
    getValue: () -> Int,
    setValue: (Int) -> Unit,
    @DrawableRes icon: Int,
    width: Int = 100
) = Row(
    Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth(),
    verticalAlignment = CenterVertically
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(options[getValue()]) }

    Icon(
        painterResource(id = icon),
        text,
        Modifier.padding(end = 16.dp).size(24.dp)
    )
    Text(text, Modifier.weight(1f))
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.width(width.dp)
    ) {
        CompositionLocalProvider(LocalTextInputService provides null) { // Disable Keyboard
            OutlinedTextField(
                readOnly = true,
                value = selectedOptionText,
                onValueChange = { setValue(options.indexOf(it)) },
                modifier = Modifier.menuAnchor().clickable { expanded = !expanded },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors()
            )
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(text = option) },
                    onClick = {
                        selectedOptionText = option
                        expanded = false
                        setValue(i)
                    }
                )
            }
        }
    }
}

@Suppress("unused")
@Composable
fun TextFieldSetting(
    text: String,
    valueRange: IntRange = 0..23,
    getValue: () -> Int,
    setValue: (Int) -> Unit,
    @DrawableRes icon: Int
) = Row(
    Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth(),
    verticalAlignment = CenterVertically
) {

    var value by remember { mutableStateOf(getValue().toString()) }

    Icon(
        painterResource(id = icon),
        text,
        Modifier.padding(end = 16.dp).size(24.dp)
    )
    Text(text, Modifier.weight(1f))
    OutlinedTextField(
        modifier = Modifier
            .width(100.dp)
            .onFocusChanged { if (!it.hasFocus && value == "") value = getValue().toString() },
        value = value,
        singleLine = true,
        maxLines = 1,
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
        onValueChange = {
            var new = it.toIntOrNull()
            if (new != null) {
                if (new < valueRange.first) {
                    new = valueRange.first
                } else if (new > valueRange.last) {
                    new = valueRange.last
                }
                value = new.toString()
                setValue(new)
            } else {
                value = ""
            }
        },
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
}

@Composable
fun SectionHeader(text: String) = Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
fun ButtonSetting(
    text: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    @DrawableRes iconButton: Int
) = Row(
    Modifier
        .fillMaxWidth()
        .height(70.dp)
        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
) {
    Icon(painterResource(id = icon), text, Modifier.align(CenterVertically))
    Text(text, Modifier.align(CenterVertically).padding(start = 16.dp))
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onClick) {
        Icon(painterResource(iconButton), stringResource(R.string.copy_to_clipboard))
    }
}

/**
 * A tappable row with a title, an optional subtitle and an icon — the Settings front page rows
 * that open a category, and inside a category any row whose whole job is one action.
 *
 * Same focus treatment as [SwitchSetting] — the whole row fills on D-pad focus — because these
 * rows are the first thing a TV user lands on, and a full-width fill cannot look crooked. The
 * subtitle carries the state worth knowing without opening anything: how many sources are
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
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rowColor = if (focused) MaterialTheme.colorScheme.inverseSurface else Color.Transparent
    val contentColor = if (focused) MaterialTheme.colorScheme.inverseOnSurface else LocalContentColor.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(rowColor, RoundedCornerShape(16.dp))
            .heightIn(min = 64.dp)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = CenterVertically
    ) {
        Icon(
            painterResource(id = icon),
            text,
            Modifier.padding(end = 16.dp).size(24.dp),
            tint = contentColor
        )
        Column(Modifier.weight(1f)) {
            Text(text, color = contentColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailingArrow) Icon(Icons.Filled.KeyboardArrowRight, null, tint = contentColor)
    }
}
