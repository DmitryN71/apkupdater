package com.apkupdater.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp


/**
 * The building blocks of the redesign, which goes in stages: the start screen first (build 177),
 * the update and search cards next (178), then one screen per build, each checked on a TV before
 * the next.
 *
 * Kept apart from MaterialTheme's own shapes on purpose. Setting those would restyle every
 * button, card, menu and dialog in the app at once — exactly the all-at-once change the stages
 * exist to avoid. A screen picks these up when it is redone; once every screen has, they can
 * move into the theme proper.
 *
 * Colours are not here: they stay Material 3's roles, so everything keeps following the
 * wallpaper (dynamic colour, Android 12+) like the rest of the app.
 */
object Design {

	/** A tappable tile: rounder than a button, squarer than a pill. */
	val TileShape = RoundedCornerShape(16.dp)
	val TileMinHeight = 56.dp
	/** Narrowest a tile gets before the grid drops a column. */
	val TileMinWidth = 160.dp

	/** The tinted square an icon sits on inside a tile. */
	val ChipShape = RoundedCornerShape(10.dp)
	val ChipSize = 32.dp
	val ChipIconSize = 18.dp

	/** A box set into a card, such as the update card's "What's new" (build 178). */
	val InsetShape = RoundedCornerShape(12.dp)

	/** Between tiles, both ways. */
	val Gap = 8.dp
	/** Around a screen's content. */
	val ScreenPadding = 16.dp
	/** Widest a centred block of tiles grows on a tablet or a TV. */
	val ContentMaxWidth = 920.dp

}
