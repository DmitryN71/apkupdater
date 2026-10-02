package com.apkupdater.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apkupdater.ui.theme.Design


/**
 * An icon on a tinted rounded square — the redesign's way of showing what a tile is about.
 * One tonal colour from the theme rather than a colour per source: under dynamic colour the
 * whole app takes its palette from the wallpaper, and a rainbow of fixed hues would fight it.
 */
@Composable
fun IconChip(
	@DrawableRes icon: Int,
	modifier: Modifier = Modifier,
	contentDescription: String? = null
) = Box(
	modifier
		.size(Design.ChipSize)
		.clip(Design.ChipShape)
		.background(MaterialTheme.colorScheme.secondaryContainer),
	contentAlignment = Alignment.Center
) {
	Icon(
		painterResource(icon),
		contentDescription,
		Modifier.size(Design.ChipIconSize),
		tint = MaterialTheme.colorScheme.onSecondaryContainer
	)
}

/**
 * A tile that does one thing when tapped: an [IconChip] and a one-line label on a tonal surface.
 *
 * TV focus follows the app's card-shaped pattern — [TvFocus] fill plus frame — and both go
 * through Surface's own colour and border, so they follow the rounded corners. A clickable on
 * an outer modifier would draw its focus and ripple in a rectangle round them (the About rows'
 * corners, build 153).
 */
@Composable
fun ActionTile(
	@DrawableRes icon: Int,
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	Surface(
		onClick = onClick,
		modifier = modifier.heightIn(min = Design.TileMinHeight),
		shape = Design.TileShape,
		color = if (focused) TvFocus.fill else MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = MaterialTheme.colorScheme.onSurface,
		border = if (focused) TvFocus.stroke else null,
		interactionSource = interaction
	) {
		Row(
			Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			IconChip(icon)
			Spacer(Modifier.width(10.dp))
			Text(
				label,
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}
