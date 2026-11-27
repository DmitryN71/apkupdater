package com.apkupdater.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorSchemeTest {

	@Test
	fun parsesValidHexAndRejectsInvalidInput() {
		assertEquals(Color(0xFF4F5BD5), parseHexColor("#4F5BD5"))
		assertEquals(Color(0xFF4F5BD5), parseHexColor("4f5bd5"))
		assertNull(parseHexColor("#12345"))
		assertNull(parseHexColor("not-a-color"))
	}

	@Test
	fun generatesDistinctSchemesAcrossPaletteStylesAndAmoled() {
		val tonal = buildAppColorScheme(
			ThemeConfig(darkTheme = true, useDynamicColor = false, accentColor = "indigo", paletteStyle = 0),
			context = null
		)
		val expressive = buildAppColorScheme(
			ThemeConfig(darkTheme = true, useDynamicColor = false, accentColor = "indigo", paletteStyle = 1),
			context = null
		)
		val amoled = buildAppColorScheme(
			ThemeConfig(darkTheme = true, useDynamicColor = false, accentColor = "indigo", paletteStyle = 0, amoledBlack = true),
			context = null
		)
		assertNotNull(tonal)
		assertNotEquals(tonal.tertiary, expressive.tertiary)
		assertEquals(Color.Black, amoled.background)
		assertNotEquals(Color.Black, amoled.surfaceContainer)
	}
}
