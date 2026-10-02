package com.apkupdater.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.apkupdater.util.isDark


@Composable
fun AppTheme(
	darkTheme: Boolean,
	dynamicColor: Boolean = true,
	content: @Composable () -> Unit
) {
	val colorScheme = when {
		dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
			val context = LocalContext.current
			if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
		}
		darkTheme -> darkColorScheme()
		else -> lightColorScheme()
	}

	val view = LocalView.current
	if (!view.isInEditMode) {
		SideEffect {
			val activity = view.context as Activity

			// Deprecated since Android 15, which draws every app edge to edge and ignores both
			// colours; below it they are still what paints the system bars, so they stay.
			// Set Navigation Bar color: the screen's own background since build 179, because the
			// bottom panel floats over it now — the old full-width bar's colour would show as a
			// band under the panel.
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				@Suppress("DEPRECATION")
				activity.window.navigationBarColor = colorScheme.background.toArgb()
				WindowCompat.getInsetsController(
					activity.window,
					view
				).isAppearanceLightNavigationBars = !darkTheme
			}

			// Set Status Bar color
			@Suppress("DEPRECATION")
			activity.window.statusBarColor = colorScheme.statusBarColor().toArgb()
			WindowCompat.getInsetsController(
				activity.window,
				view
			).isAppearanceLightStatusBars = !darkTheme
		}
	}

	MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, content = content)
}

/**
 * The redesign's corner scale (3.9.0). Material's own values, written out so they are a decision
 * rather than a default: the tiles are `large`, the boxes set into cards and the icon backings
 * `medium` (see Design). Changing a value here changes every component that uses that size,
 * Material's own included — which is the point of having it in the theme.
 */
val AppShapes = Shapes(
	extraSmall = RoundedCornerShape(4.dp),
	small = RoundedCornerShape(8.dp),
	medium = RoundedCornerShape(12.dp),
	large = RoundedCornerShape(16.dp),
	extraLarge = RoundedCornerShape(28.dp)
)

fun ColorScheme.statusBarColor() = surfaceColorAtElevation(3.dp)

fun isDarkTheme(theme: Int): Boolean {
	if (theme == 1) return true
	if (theme == 2) return false
	return isDark()
}
