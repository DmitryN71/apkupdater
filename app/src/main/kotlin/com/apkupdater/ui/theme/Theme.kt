package com.apkupdater.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.apkupdater.R
import com.apkupdater.util.isDark
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

data class ThemeConfig(
	val darkTheme: Boolean,
	val useDynamicColor: Boolean = true,
	val accentColor: String = "indigo",
	val paletteStyle: Int = 0,
	val amoledBlack: Boolean = false,
	val highContrast: Boolean = false
)

data class AccentPreset(
	val id: String,
	@StringRes val labelRes: Int,
	val seedColor: Color
)

val AccentPresets: List<AccentPreset> = listOf(
	AccentPreset("indigo", R.string.accent_indigo, Color(0xFF4F5BD5)),
	AccentPreset("blue", R.string.accent_blue, Color(0xFF1A73E8)),
	AccentPreset("teal", R.string.accent_teal, Color(0xFF00897B)),
	AccentPreset("green", R.string.accent_green, Color(0xFF2E7D32)),
	AccentPreset("amber", R.string.accent_amber, Color(0xFFEF6C00)),
	AccentPreset("rose", R.string.accent_rose, Color(0xFFD81B60)),
	AccentPreset("purple", R.string.accent_purple, Color(0xFF7C4DFF)),
	AccentPreset("slate", R.string.accent_slate, Color(0xFF546E7A))
)

fun parseHexColor(raw: String): Color? {
	val cleaned = raw.trim().removePrefix("#")
	if (cleaned.length != 6 && cleaned.length != 8) return null
	val parsed = cleaned.toLongOrNull(16) ?: return null
	val argb = if (cleaned.length == 6) (0xFF000000L or parsed) else parsed
	return Color(argb.toInt())
}

fun resolveAccentColor(accentKey: String): Color =
	AccentPresets.find { it.id.equals(accentKey, ignoreCase = true) }?.seedColor
		?: parseHexColor(accentKey)
		?: AccentPresets.first().seedColor

fun buildAppColorScheme(config: ThemeConfig, context: Context?): ColorScheme {
	val baseScheme = when {
		config.useDynamicColor && context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
			val systemScheme = if (config.darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
			if (config.highContrast) {
				dynamicSchemeFromSeed(
					seedColor = systemScheme.primary,
					isDark = config.darkTheme,
					paletteStyle = 0,
					highContrast = true
				)
			} else {
				systemScheme
			}
		}
		else -> {
			dynamicSchemeFromSeed(
				seedColor = resolveAccentColor(config.accentColor),
				isDark = config.darkTheme,
				paletteStyle = config.paletteStyle,
				highContrast = config.highContrast
			)
		}
	}
	return if (config.darkTheme && config.amoledBlack) baseScheme.withAmoledBlack() else baseScheme
}

internal fun dynamicSchemeFromSeed(
	seedColor: Color,
	isDark: Boolean,
	paletteStyle: Int,
	highContrast: Boolean
): ColorScheme {
	val hct = Hct.fromInt(seedColor.toArgb())
	val contrast = if (highContrast) 1.0 else 0.0
	val spec = ColorSpec.SpecVersion.SPEC_2025
	val scheme: DynamicScheme = when (paletteStyle) {
		1 -> SchemeExpressive(hct, isDark, contrast, spec)
		2 -> SchemeVibrant(hct, isDark, contrast, spec)
		3 -> SchemeNeutral(hct, isDark, contrast, spec)
		4 -> SchemeMonochrome(hct, isDark, contrast, spec)
		else -> SchemeTonalSpot(hct, isDark, contrast, spec)
	}
	return scheme.toComposeColorScheme()
}

private fun DynamicScheme.toComposeColorScheme(): ColorScheme = if (isDark) {
	darkColorScheme(
		primary = Color(primary),
		onPrimary = Color(onPrimary),
		primaryContainer = Color(primaryContainer),
		onPrimaryContainer = Color(onPrimaryContainer),
		inversePrimary = Color(inversePrimary),
		secondary = Color(secondary),
		onSecondary = Color(onSecondary),
		secondaryContainer = Color(secondaryContainer),
		onSecondaryContainer = Color(onSecondaryContainer),
		tertiary = Color(tertiary),
		onTertiary = Color(onTertiary),
		tertiaryContainer = Color(tertiaryContainer),
		onTertiaryContainer = Color(onTertiaryContainer),
		background = Color(background),
		onBackground = Color(onBackground),
		surface = Color(surface),
		onSurface = Color(onSurface),
		surfaceVariant = Color(surfaceVariant),
		onSurfaceVariant = Color(onSurfaceVariant),
		surfaceTint = Color(surfaceTint),
		inverseSurface = Color(inverseSurface),
		inverseOnSurface = Color(inverseOnSurface),
		error = Color(error),
		onError = Color(onError),
		errorContainer = Color(errorContainer),
		onErrorContainer = Color(onErrorContainer),
		outline = Color(outline),
		outlineVariant = Color(outlineVariant),
		scrim = Color(scrim),
		surfaceBright = Color(surfaceBright),
		surfaceDim = Color(surfaceDim),
		surfaceContainer = Color(surfaceContainer),
		surfaceContainerHigh = Color(surfaceContainerHigh),
		surfaceContainerHighest = Color(surfaceContainerHighest),
		surfaceContainerLow = Color(surfaceContainerLow),
		surfaceContainerLowest = Color(surfaceContainerLowest)
	)
} else {
	lightColorScheme(
		primary = Color(primary),
		onPrimary = Color(onPrimary),
		primaryContainer = Color(primaryContainer),
		onPrimaryContainer = Color(onPrimaryContainer),
		inversePrimary = Color(inversePrimary),
		secondary = Color(secondary),
		onSecondary = Color(onSecondary),
		secondaryContainer = Color(secondaryContainer),
		onSecondaryContainer = Color(onSecondaryContainer),
		tertiary = Color(tertiary),
		onTertiary = Color(onTertiary),
		tertiaryContainer = Color(tertiaryContainer),
		onTertiaryContainer = Color(onTertiaryContainer),
		background = Color(background),
		onBackground = Color(onBackground),
		surface = Color(surface),
		onSurface = Color(onSurface),
		surfaceVariant = Color(surfaceVariant),
		onSurfaceVariant = Color(onSurfaceVariant),
		surfaceTint = Color(surfaceTint),
		inverseSurface = Color(inverseSurface),
		inverseOnSurface = Color(inverseOnSurface),
		error = Color(error),
		onError = Color(onError),
		errorContainer = Color(errorContainer),
		onErrorContainer = Color(onErrorContainer),
		outline = Color(outline),
		outlineVariant = Color(outlineVariant),
		scrim = Color(scrim),
		surfaceBright = Color(surfaceBright),
		surfaceDim = Color(surfaceDim),
		surfaceContainer = Color(surfaceContainer),
		surfaceContainerHigh = Color(surfaceContainerHigh),
		surfaceContainerHighest = Color(surfaceContainerHighest),
		surfaceContainerLow = Color(surfaceContainerLow),
		surfaceContainerLowest = Color(surfaceContainerLowest)
	)
}

private fun ColorScheme.withAmoledBlack(): ColorScheme = copy(
	background = Color.Black,
	surface = Color.Black,
	surfaceDim = Color.Black,
	surfaceContainerLowest = Color.Black,
	surfaceContainerLow = Color(0xFF0A0A0C),
	surfaceContainer = Color(0xFF121215),
	surfaceContainerHigh = Color(0xFF1A1A1E),
	surfaceContainerHighest = Color(0xFF222227)
)

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
	val spec = tween<Color>(durationMillis = 260)
	val primary by animateColorAsState(target.primary, spec, label = "primary")
	val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
	val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
	val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
	val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
	val onSecondary by animateColorAsState(target.onSecondary, spec, label = "onSecondary")
	val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer")
	val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")
	val tertiary by animateColorAsState(target.tertiary, spec, label = "tertiary")
	val onTertiary by animateColorAsState(target.onTertiary, spec, label = "onTertiary")
	val tertiaryContainer by animateColorAsState(target.tertiaryContainer, spec, label = "tertiaryContainer")
	val onTertiaryContainer by animateColorAsState(target.onTertiaryContainer, spec, label = "onTertiaryContainer")
	val background by animateColorAsState(target.background, spec, label = "background")
	val onBackground by animateColorAsState(target.onBackground, spec, label = "onBackground")
	val surface by animateColorAsState(target.surface, spec, label = "surface")
	val onSurface by animateColorAsState(target.onSurface, spec, label = "onSurface")
	val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant")
	val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant")
	val outline by animateColorAsState(target.outline, spec, label = "outline")
	val outlineVariant by animateColorAsState(target.outlineVariant, spec, label = "outlineVariant")
	val surfaceContainerLowest by animateColorAsState(target.surfaceContainerLowest, spec, label = "surfaceContainerLowest")
	val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow")
	val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer")
	val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh")
	val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest")
	return target.copy(
		primary = primary,
		onPrimary = onPrimary,
		primaryContainer = primaryContainer,
		onPrimaryContainer = onPrimaryContainer,
		secondary = secondary,
		onSecondary = onSecondary,
		secondaryContainer = secondaryContainer,
		onSecondaryContainer = onSecondaryContainer,
		tertiary = tertiary,
		onTertiary = onTertiary,
		tertiaryContainer = tertiaryContainer,
		onTertiaryContainer = onTertiaryContainer,
		background = background,
		onBackground = onBackground,
		surface = surface,
		onSurface = onSurface,
		surfaceVariant = surfaceVariant,
		onSurfaceVariant = onSurfaceVariant,
		outline = outline,
		outlineVariant = outlineVariant,
		surfaceContainerLowest = surfaceContainerLowest,
		surfaceContainerLow = surfaceContainerLow,
		surfaceContainer = surfaceContainer,
		surfaceContainerHigh = surfaceContainerHigh,
		surfaceContainerHighest = surfaceContainerHighest
	)
}

@Composable
fun AppTheme(
	darkTheme: Boolean,
	dynamicColor: Boolean = true,
	content: @Composable () -> Unit
) = AppTheme(
	config = ThemeConfig(darkTheme = darkTheme, useDynamicColor = dynamicColor),
	content = content
)

@Composable
fun AppTheme(
	config: ThemeConfig,
	content: @Composable () -> Unit
) {
	val context = LocalContext.current
	val view = LocalView.current
	val targetScheme = remember(config) {
		buildAppColorScheme(config, if (view.isInEditMode) null else context)
	}
	val colorScheme = if (view.isInEditMode) targetScheme else animateColorScheme(targetScheme)

	if (!view.isInEditMode) {
		SideEffect {
			val activity = view.context as? Activity ?: return@SideEffect

			// Deprecated since Android 15, which draws every app edge to edge and ignores both
			// colours; below it they are still what paints the system bars, so they stay.
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				@Suppress("DEPRECATION")
				activity.window.navigationBarColor = targetScheme.background.toArgb()
				WindowCompat.getInsetsController(
					activity.window,
					view
				).isAppearanceLightNavigationBars = !config.darkTheme
			}

			@Suppress("DEPRECATION")
			activity.window.statusBarColor = targetScheme.statusBarColor().toArgb()
			WindowCompat.getInsetsController(
				activity.window,
				view
			).isAppearanceLightStatusBars = !config.darkTheme
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
