package net.ripster.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разрешение «настройка × ночной режим системы» — единственное место, где
 * рождается ответ «какая палитра на экране прямо сейчас». Ошибка здесь
 * невидима поодиночке: каждый экран по отдельности выглядит правильно,
 * а расходятся заставка, бары и контент — то есть человек видит белый
 * флэш или нечитаемые иконки статуса. Поэтому таблица полная,
 * а не выборочная.
 */
class ThemeModeTest {

    @Test
    fun `system setting resolves to the honest light and dark palettes`() {
        assertEquals(RipsterThemeName.Light, resolveThemeSetting(THEME_SETTING_SYSTEM, systemDark = false))
        assertEquals(RipsterThemeName.Dark, resolveThemeSetting(THEME_SETTING_SYSTEM, systemDark = true))
    }

    @Test
    fun `explicit palette wins over the system night mode`() {
        for (t in RipsterThemeName.entries) {
            assertEquals(t, resolveThemeSetting(t.name, systemDark = false))
            assertEquals(t, resolveThemeSetting(t.name, systemDark = true))
        }
    }

    @Test
    fun `missing or broken setting falls back to the default skin, never crashes`() {
        for (raw in listOf(null, "", "dark", "Neon ", "Beach")) {
            assertEquals(DefaultThemeName, resolveThemeSetting(raw, systemDark = true))
            assertEquals(DefaultThemeName, resolveThemeSetting(raw, systemDark = false))
        }
    }

    @Test
    fun `picker options are system first and cover every palette exactly once`() {
        val options = themeSettingOptions()
        assertEquals(THEME_SETTING_SYSTEM, options.first())
        assertEquals(
            RipsterThemeName.entries.map { it.name }.toSet(),
            options.drop(1).toSet(),
        )
        assertEquals(options.size, options.distinct().size)
    }

    @Test
    fun `every picker option round-trips through the resolver as a real setting`() {
        // Персист хранит тему строкой; любое значение, которое показывает пикер,
        // обязано выживать в round-trip и не уходить в дефолт.
        for (opt in themeSettingOptions()) {
            if (opt == THEME_SETTING_SYSTEM) {
                assertEquals(RipsterThemeName.Dark, resolveThemeSetting(opt, systemDark = true))
                assertEquals(RipsterThemeName.Light, resolveThemeSetting(opt, systemDark = false))
            } else {
                assertEquals(opt, resolveThemeSetting(opt, systemDark = true).name)
                assertEquals(opt, resolveThemeSetting(opt, systemDark = false).name)
            }
        }
    }

    @Test
    fun `bar icon polarity follows the canvas, not the system night mode`() {
        assertTrue(isLightSurface(LightColors.surface_canvas))
        assertTrue(isLightSurface(SepiaColors.surface_canvas))
        for (dark in listOf(DarkColors, MidnightColors, NeonColors, AuroraColors, EmberColors)) {
            assertFalse(isLightSurface(dark.surface_canvas))
        }
    }
}
