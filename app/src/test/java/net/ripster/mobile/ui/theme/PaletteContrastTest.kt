package net.ripster.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблица контраста всех семи палитр.
 *
 * Палитры в RipsterColors.kt объявлены замкнутым множеством — «ещё одну тему
 * руками» они не переживут (см. шапку RipsterTheme.kt). Этот тест — тот же
 * порог WCAG, что стоял за генерацией из десктопного CSS: Body-текст
 * (primary/secondary) и все статусные цвета обязаны давать ≥ 4.5:1 на
 * поверхностях, где они реально лежат. Порог 4.5 — не украшение: при 3:1
 * основной текст в светлой теме читается, но устаёт, а на Sunken/Midnight
 * проваливается в шум.
 *
 * Границы (border_*) не проверяем: в светлых палитрах они полупрозрачные по
 * замыслу — контраст линии к холсту считается от смешанного цвета, а это
 * уже другая метрика (3:1 для нетекстовых элементов), и ею тут не пахнет.
 */
class PaletteContrastTest {

    private val bodyThreshold = 4.5f

    private fun surfaces(c: RipsterColors) = listOf(
        "canvas" to c.surface_canvas,
        "sunken" to c.surface_sunken,
        "raised" to c.surface_raised,
        "overlay" to c.surface_overlay,
    )

    private fun check(theme: RipsterThemeName, c: RipsterColors, pairs: List<Pair<String, Color>>) {
        for ((what, fg) in pairs) {
            for ((onName, bg) in surfaces(c)) {
                val ratio = contrastRatio(fg, bg)
                assertTrue(
                    "$theme: $what на $onName = ${"%.2f".format(ratio)}:1, нужно ≥ $bodyThreshold",
                    ratio >= bodyThreshold,
                )
            }
        }
    }

    @Test
    fun `body text passes 4_5 on every surface of every palette`() {
        for (t in RipsterThemeName.entries) {
            val c = colorsFor(t)
            check(t, c, listOf(
                "text_primary" to c.text_primary,
                "text_secondary" to c.text_secondary,
                "text_tertiary" to c.text_tertiary,
            ))
        }
    }

    @Test
    fun `status text passes 4_5 on canvas and raised of every palette`() {
        for (t in RipsterThemeName.entries) {
            val c = colorsFor(t)
            val pairs = listOf(
                "success_text" to c.success_text,
                "warning_text" to c.warning_text,
                "danger_text" to c.danger_text,
                "progress_text" to c.progress_text,
                "info_text" to c.info_text,
                "accent_text" to c.accent_text,
            )
            for ((what, fg) in pairs) {
                for ((onName, bg) in listOf("canvas" to c.surface_canvas, "raised" to c.surface_raised)) {
                    val ratio = contrastRatio(fg, bg)
                    assertTrue(
                        "$t: $what на $onName = ${"%.2f".format(ratio)}:1, нужно ≥ $bodyThreshold",
                        ratio >= bodyThreshold,
                    )
                }
            }
        }
    }

    @Test
    fun `text on filled buttons passes 4_5 in every palette`() {
        for (t in RipsterThemeName.entries) {
            val c = colorsFor(t)
            val fills = listOf(
                "accent_fill" to c.accent_fill,
                "success_fill" to c.success_fill,
                "warning_fill" to c.warning_fill,
                "danger_fill" to c.danger_fill,
                "progress_fill" to c.progress_fill,
                "info_fill" to c.info_fill,
            )
            for ((onName, bg) in fills) {
                val ratio = contrastRatio(c.text_on_fill, bg)
                assertTrue(
                    "$t: text_on_fill на $onName = ${"%.2f".format(ratio)}:1, нужно ≥ $bodyThreshold",
                    ratio >= bodyThreshold,
                )
            }
        }
    }

    @Test
    fun `light palettes ask for dark status-bar icons and dark palettes for light`() {
        // Единственный резолвер полярности баров обязан сходиться с тем,
        // что реально лежит в values/values-night: Light и Sepia — светлые,
        // остальные пять — тёмные.
        val light = setOf(RipsterThemeName.Light, RipsterThemeName.Sepia)
        for (t in RipsterThemeName.entries) {
            assertTrue(
                t.name,
                isLightSurface(colorsFor(t).surface_canvas) == light.contains(t),
            )
        }
    }
}
