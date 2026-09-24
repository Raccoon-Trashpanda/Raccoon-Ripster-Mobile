package net.ripster.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разрешение палитры хрома над развёрнутым плеером.
 *
 * Снимок оркестратора 23.09.2026 (системный светлый режим): шапка, нижняя
 * навигация и полоса «N готово» — светлые, а «Сейчас играет» под ними чёрный.
 * Каждый экран по отдельности выглядел правильным, поэтому ни один существующий
 * тест этого не поймал: ломалась именно СТЫК. Отсюда и проверки ниже — они
 * сравнивают не «красиво ли», а «совпадает ли полярность хрома с полярностью
 * того, на что он лёг».
 */
class ChromeSurfaceTest {

    /** Светлая поверхность-конкурент: плеер, нарисованный холстом темы Light. */
    private val lightSurface = LightColors.surface_canvas

    @Test
    fun `chrome is the theme palette while no player paints its own surface`() {
        for (t in RipsterThemeName.entries) {
            val c = colorsFor(t)
            assertSame(c, chromeColorsFor(c, null))
        }
    }

    @Test
    fun `a dark player over a light theme pulls the chrome dark`() {
        val chrome = chromeColorsFor(LightColors, PlayerDarkSurface)
        assertNotSame(LightColors, chrome)
        assertTrue("хром над тёмным плеером обязан быть тёмным", !isLightSurface(chrome.surface_canvas))
    }

    @Test
    fun `a light player over a dark theme pulls the chrome light`() {
        val chrome = chromeColorsFor(NeonColors, lightSurface)
        assertTrue(isLightSurface(chrome.surface_canvas))
    }

    @Test
    fun `the adopted chrome paints the player surface itself, not a near colour`() {
        // Иначе между полосой хрома и плеером остаётся видимый шов — тот же
        // лоскут, только тоньше.
        assertEquals(PlayerDarkSurface, chromeColorsFor(SepiaColors, PlayerDarkSurface).surface_canvas)
    }

    @Test
    fun `a dark theme keeps its own skin over the dark player`() {
        // Neon/Aurora/Ember и так тёмные: подменять их безликим Dark нельзя —
        // человек выбрал скин, а не «что-нибудь тёмное».
        for (t in listOf(RipsterThemeName.Dark, RipsterThemeName.Midnight, RipsterThemeName.Ember,
                         RipsterThemeName.Neon, RipsterThemeName.Aurora)
        ) {
            val c = colorsFor(t)
            assertSame(t.name, c, chromeColorsFor(c, PlayerDarkSurface))
        }
    }

    @Test
    fun `chrome polarity always matches the surface it lies on`() {
        val surfaces = listOf(null, PlayerDarkSurface, lightSurface, Color(0xFF0A0715), Color(0xFFFFFCF5))
        for (t in RipsterThemeName.entries) {
            val base = colorsFor(t)
            for (s in surfaces) {
                val chrome = chromeColorsFor(base, s)
                val on = s ?: base.surface_canvas
                assertEquals(
                    "$t над $s",
                    isLightSurface(on),
                    isLightSurface(chrome.surface_canvas),
                )
            }
        }
    }

    @Test
    fun `chrome text stays readable on the surface it was moved to`() {
        // Порог тот же, что у PaletteContrastTest: 4.5:1 для основного и
        // второстепенного текста. Светлый текст на светлом же хроме — это ровно
        // то, что человек увидел бы вместо починки.
        for (s in listOf(PlayerDarkSurface, lightSurface, Color(0xFF1E1740), Color(0xFFF5EFE4))) {
            for (t in RipsterThemeName.entries) {
                val chrome = chromeColorsFor(colorsFor(t), s)
                for ((what, fg) in listOf(
                    "text_primary" to chrome.text_primary,
                    "text_secondary" to chrome.text_secondary,
                    "text_tertiary" to chrome.text_tertiary,
                )) {
                    val ratio = contrastRatio(fg, chrome.surface_canvas)
                    assertTrue(
                        "$t над $s: $what = ${"%.2f".format(ratio)}:1, нужно ≥ 4.5",
                        ratio >= 4.5f,
                    )
                }
            }
        }
    }

    @Test
    fun `the chosen accent survives the swap to the player's palette`() {
        // Акцент, который исчезает, стоит развернуть плеер, — это настройка,
        // работающая только наполовине экранов.
        for (t in RipsterThemeName.entries) {
            for (sw in AccentPalette.all) {
                val app = applyAccent(colorsFor(t), sw.hue)
                val chrome = chromeColorsFor(app, PlayerDarkSurface)
                val ratio = contrastRatio(chrome.accent_fill, chrome.surface_canvas)
                assertTrue(
                    "$t/${sw.id}: акцент хрома над плеером = ${"%.2f".format(ratio)}:1",
                    ratio >= 4.5f,
                )
                // Тот же тон, а не «какой-нибудь светлый»: иначе выбор
                // превращается в случайный цвет под каждым экраном.
                val dh = kotlin.math.abs(sw.hue.toOklch().h - chrome.accent_fill.toOklch().h)
                assertTrue(
                    "$t/${sw.id}: тон акцента уехал на ${"%.1f".format(minOf(dh, 360f - dh))}°",
                    minOf(dh, 360f - dh) <= 1f,
                )
            }
        }
    }

    @Test
    fun `the studio player surface is the one the screens actually paint`() {
        // Значение живёт в двух местах (theme и NowPlayingScreen) и обязано
        // совпадать: разные числа — это и есть шов.
        assertEquals(Color(0xFF07070A), PlayerDarkSurface)
        assertEquals(PlayerDarkSurface, net.ripster.mobile.ui.screens.StudioBackground)
    }
}
