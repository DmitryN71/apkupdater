package com.apkupdater.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp


/**
 * The building blocks of the redesign: the start screen (build 177), the update and search cards
 * (178), then Settings, the bottom bar and the dialog in 3.9.0 (179).
 *
 * The stages kept these apart from MaterialTheme, so that no stage restyled every button, menu
 * and dialog in the app at once. With every screen redone they have moved in (179) as far as the
 * theme has room: the shapes that have a Material role — a tile is `large`, a box set into a card
 * and an icon's backing are `medium` — are read from MaterialTheme.shapes, set in AppTheme, so a
 * change there reaches every one of them. What stays here is what the theme has no slot for:
 * sizes, gaps, and the settings groups' two-radius corners.
 *
 * Colours are not here: they stay Material 3's roles, so everything keeps following the
 * wallpaper (dynamic colour, Android 12+) like the rest of the app.
 */
object Design {

	/** A tappable tile: rounder than a button, squarer than a pill. The theme's `large`. */
	val TileShape: Shape
		@Composable @ReadOnlyComposable get() = MaterialTheme.shapes.large
	val TileMinHeight = 56.dp
	/** Narrowest a tile gets before the grid drops a column. */
	val TileMinWidth = 160.dp

	/** The tinted square an icon sits on inside a tile or a settings row. The theme's `medium`. */
	val ChipShape: Shape
		@Composable @ReadOnlyComposable get() = MaterialTheme.shapes.medium
	val ChipSize = 32.dp
	val ChipIconSize = 18.dp

	/** A box set into a card, such as the update card's "What's new" (build 178). The theme's `medium`. */
	val InsetShape: Shape
		@Composable @ReadOnlyComposable get() = MaterialTheme.shapes.medium

	/**
	 * Settings rows sit in groups (build 179, variant A): rows 2 dp apart, big corners where a
	 * group starts and ends, small ones between its rows — the way Android's own settings look
	 * since Android 16. Each row draws its own shape rather than the group clipping them all,
	 * because a clip would also cut the TV focus frame off at the group's corners.
	 */
	val GroupOuterCorner = 20.dp
	val GroupInnerCorner = 6.dp
	val GroupGap = 2.dp

	/** The shape of row [index] in a group of [count]. */
	fun groupRowShape(index: Int, count: Int): Shape {
		val top = if (index == 0) GroupOuterCorner else GroupInnerCorner
		val bottom = if (index == count - 1) GroupOuterCorner else GroupInnerCorner
		return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
	}

	/** Between tiles, both ways. */
	val Gap = 8.dp
	/** Around a screen's content. */
	val ScreenPadding = 16.dp
	/** Widest a centred block of tiles grows on a tablet or a TV. */
	val ContentMaxWidth = 920.dp

}
