package com.apkupdater.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.apkupdater.util.isAndroidTv

/**
 * The colours of the app's text fields since 3.10.0: a filled, fully rounded field on the
 * strongest tonal surface, with no underline and no outline — the look of Home's search bar,
 * which every other field now shares. The disabled colours are the enabled ones: on a TV the field
 * is disabled as a focus mechanism (see [TvTextField]), not because it is unavailable, and it must
 * not look greyed out.
 */
@Composable
fun appTextFieldColors(): TextFieldColors {
	val container = MaterialTheme.colorScheme.surfaceContainerHighest
	val onSurface = MaterialTheme.colorScheme.onSurface
	val muted = MaterialTheme.colorScheme.onSurfaceVariant
	return TextFieldDefaults.colors(
		focusedContainerColor = container,
		unfocusedContainerColor = container,
		disabledContainerColor = container,
		// A tint of the error colour rather than a red line, which this shape does not have.
		errorContainerColor = MaterialTheme.colorScheme.errorContainer,
		focusedIndicatorColor = Color.Transparent,
		unfocusedIndicatorColor = Color.Transparent,
		disabledIndicatorColor = Color.Transparent,
		errorIndicatorColor = Color.Transparent,
		disabledTextColor = onSurface,
		disabledLabelColor = muted,
		disabledPlaceholderColor = muted,
		disabledSupportingTextColor = muted,
		disabledLeadingIconColor = muted,
		disabledTrailingIconColor = muted,
		unfocusedLeadingIconColor = muted,
		focusedLeadingIconColor = muted
	)
}

/**
 * A text field a D-pad can get into with one press of OK, and back out of with Back — drawn, since
 * 3.10.0, like Home's search bar: filled, fully rounded, with an optional [leadingIcon] saying what
 * it is for. The outlined fields before it read as boxes drawn round nothing (Dmitry).
 *
 * A focused Compose text field swallows the directional keys to move its own cursor, so on a
 * television it traps the focus. Builds since then worked around that by making the field
 * `readOnly` outside an editing mode and hanging a `clickable` on the same modifier — but a
 * read-only field is still a focus target, so the card had TWO of them stacked, the inner one
 * took the focus and ate the OK press, and only a long press got through to the outer one.
 * Reported on 4PDA and by Dmitry on his own TV: "чтобы туда войти, нужно долго держать ОК".
 *
 * The cure is to leave exactly one focus target in each state. While not editing the field is
 * `enabled = false`, which in Compose means "not focusable and not clickable" — so the wrapper
 * around it is the only thing the D-pad can land on, and one press of OK is one click. Entering
 * editing enables the field and hands it the focus, which opens the keyboard; Back, the
 * keyboard's own Done, or focus leaving by any other route ends it. While the D-pad rests on the
 * wrapper, the app's TV focus frame goes round the field — unless the field carries supporting
 * text, which sits inside its bounds: a frame round both would look like a box round a paragraph,
 * and the one field that has it (the GitHub token) sits in a settings row that frames itself.
 *
 * Phones are untouched — [isAndroidTv] is false, so the field is always enabled and no wrapper
 * click is attached.
 */
@Composable
fun TvTextField(
	value: String,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	label: @Composable (() -> Unit)? = null,
	placeholder: @Composable (() -> Unit)? = null,
	supportingText: @Composable (() -> Unit)? = null,
	isError: Boolean = false,
	leadingIcon: ImageVector? = null
) {
	val isTv = LocalContext.current.isAndroidTv()
	val keyboardController = LocalSoftwareKeyboardController.current
	var editing by remember { mutableStateOf(false) }
	val focusRequester = remember { FocusRequester() }
	// Only while editing: requesting focus on a field that is not attached throws, and the
	// field is not composed as focusable until it is enabled.
	LaunchedEffect(editing) {
		if (editing) runCatching { focusRequester.requestFocus() }
	}
	val wrapperInteraction = remember { MutableInteractionSource() }
	val wrapperFocused by wrapperInteraction.collectIsFocusedAsState()
	val shape = MaterialTheme.shapes.extraLarge
	TextField(
		value = value,
		onValueChange = onValueChange,
		label = label,
		placeholder = placeholder,
		supportingText = supportingText,
		isError = isError,
		singleLine = true,
		enabled = !isTv || editing,
		textStyle = LocalTextStyle.current,
		shape = shape,
		colors = appTextFieldColors(),
		leadingIcon = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null) } },
		modifier = modifier
			.then(
				if (isTv && !editing && wrapperFocused && supportingText == null) {
					Modifier.border(TvFocus.stroke, shape)
				} else {
					Modifier
				}
			)
			// Attached only while NOT editing. Left on during editing it would be a second
			// focus target beside the now-enabled field — exactly the fault being fixed.
			.then(
				if (isTv && !editing) {
					Modifier.clickable(interactionSource = wrapperInteraction, indication = null) { editing = true }
				} else {
					Modifier
				}
			)
			.focusRequester(focusRequester)
			// Back always ends editing, whatever the keyboard did with the focus — without it a
			// television could be left inside the field with no way out but the app's own back.
			.onPreviewKeyEvent { event ->
				if (isTv && editing && event.type == KeyEventType.KeyUp && event.key == Key.Back) {
					editing = false
					keyboardController?.hide()
					true
				} else {
					false
				}
			}
			.onFocusChanged { if (!it.isFocused) editing = false }
	)
}
