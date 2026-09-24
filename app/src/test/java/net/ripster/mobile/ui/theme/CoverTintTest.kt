package net.ripster.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Зажим цвета обложки под поверхность темы: арифметика WCAG, круг
 * sRGB → Oklch → sRGB и обещания из шапки файла — «светлота принадлежит теме,
 * у обложки остаются тон и ограниченная хроматика».
 */
class CoverTintTest {

    private fun rgb(c: Color) = Triple(c.red, c.green, c.blue)

    private fun close(a: Color, b: Color, tol: Float = 0.01f): Boolean =
        abs(a.red - b.red) < tol && abs(a.green - b.green) < tol && abs(a.blue - b.blue) < tol

    // ── гамма и яркость ─────────────────────────────────────────────────────

    @Test
    fun gamma_endpoints_are_exact() {
        assertEquals(0f, srgbToLinear(0f), 1e-7f)
        assertEquals(1f, srgbToLinear(1f), 1e-6f)
        assertEquals(0f, linearToSrgb(0f), 1e-7f)
        assertEquals(1f, linearToSrgb(1f), 1e-6f)
    }

    @Test
    fun below_the_knee_the_curve_is_the_linear_fraction() {
        // Кусочная кривая, а не приближение x^2.2: до 0.04045 деление на 12.92.
        assertEquals(0.04045f / 12.92f, srgbToLinear(0.04045f), 1e-7f)
        assertEquals(0.01f / 12.92f, srgbToLinear(0.01f), 1e-7f)
    }

    @Test
    fun gamma_round_trips_over_the_whole_scale() {
        var worst = 0f
        var i = 0
        while (i <= 255) {
            val v = i / 255f
            worst = maxOf(worst, abs(linearToSrgb(srgbToLinear(v)) - v))
            i++
        }
        assertTrue("максимальная погрешность хода туда-обратно = $worst", worst < 2e-3f)
    }

    @Test
    fun linearToSrgb_clamps_what_the_search_throws_at_it() {
        // Поиск светлоты нарочно заглядывает за гамут, обрезка обязательна.
        assertEquals(0f, linearToSrgb(-0.4f), 1e-6f)
        assertEquals(1f, linearToSrgb(1.6f), 1e-6f)
    }

    @Test
    fun relative_luminance_matches_the_WCAG_table() {
        assertEquals(0f, relativeLuminance(Color.Black), 1e-6f)
        assertEquals(1f, relativeLuminance(Color.White), 1e-5f)
        // Color упакован в 8 бит на канал, поэтому 0.5f — это 128/255, не ровно половина.
        assertEquals(0.2140f, relativeLuminance(Color(0.5f, 0.5f, 0.5f)), 2e-3f)
    }

    @Test
    fun luminance_is_not_the_sRGB_number_which_is_why_HSL_was_rejected() {
        // Жёлтый и синий с одинаковым L в HSL различаются по яркости в разы.
        val yellow = Color(1f, 1f, 0f)
        val blue = Color(0f, 0f, 1f)
        assertTrue(relativeLuminance(yellow) > 10f * relativeLuminance(blue))
    }

    @Test
    fun contrast_ratio_is_symmetric_and_at_least_one() {
        val a = Color(0.2f, 0.6f, 0.9f)
        val b = Color(0.95f, 0.9f, 0.85f)
        assertEquals(contrastRatio(a, b), contrastRatio(b, a), 1e-6f)
        assertEquals(1f, contrastRatio(a, a), 1e-6f)
        assertTrue(contrastRatio(a, b) >= 1f)
    }

    @Test
    fun contrast_ratio_of_the_extremes_is_21() = assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.02f)

    // ── Oklch туда и обратно ─────────────────────────────────────────────────

    @Test
    fun oklch_round_trip_keeps_in_gamut_colours() {
        for (c in listOf(
            Color(0.13f, 0.55f, 0.8f),
            Color(0.9f, 0.85f, 0.6f),
            Color(0.35f, 0.35f, 0.35f),
            Color(0.02f, 0.02f, 0.03f),
        )) {
            assertTrue("искажение для ${rgb(c)}", close(c, c.toOklch().toColor(), 6e-3f))
        }
    }

    @Test
    fun oklch_lightness_spans_the_scale_and_greys_have_no_chroma() {
        assertEquals(0f, Color.Black.toOklch().l, 1e-5f)
        assertEquals(1f, Color.White.toOklch().l, 1e-5f)
        assertEquals(0f, Color(0.5f, 0.5f, 0.5f).toOklch().c, 1e-4f)
        assertTrue(Color(0.9f, 0.2f, 0.15f).toOklch().c > 0.15f)
    }

    @Test
    fun different_hues_stay_different_after_the_round_trip() {
        val red = Color(0.85f, 0.12f, 0.1f).toOklch()
        val teal = Color(0.1f, 0.7f, 0.65f).toOklch()
        assertNotEquals(red.h, teal.h, 0.5f)
    }

    // ── зажим: контраст задаёт тема, тон задаёт обложка ───────────────────────

    private val darkSurface = Color(0.05f, 0.05f, 0.06f)
    private val lightSurface = Color(0.96f, 0.94f, 0.90f)

    @Test
    fun clamped_tint_reaches_the_target_contrast_on_a_dark_surface() {
        val out = clampCoverTint(Color(0.9f, 0.2f, 0.15f), darkSurface)
        assertTrue("контраст ${contrastRatio(out, darkSurface)}", contrastRatio(out, darkSurface) >= 4.5f)
        assertTrue("по тёмному зажим идёт вверх", relativeLuminance(out) > relativeLuminance(darkSurface))
    }

    @Test
    fun clamped_tint_reaches_the_target_contrast_on_a_light_surface() {
        val out = clampCoverTint(Color(0.1f, 0.2f, 0.6f), lightSurface)
        assertTrue("контраст ${contrastRatio(out, lightSurface)}", contrastRatio(out, lightSurface) >= 4.5f)
        assertTrue("по светлому зажим идёт вниз", relativeLuminance(out) < relativeLuminance(lightSurface))
    }

    @Test
    fun lightness_no_longer_belongs_to_the_cover() {
        // Обещание «ни один альбом не может оказаться ярче другого»: два альбома
        // одного тона, разная светлота → одинаковая яркость заливки.
        val dull = clampCoverTint(Color(0.12f, 0.05f, 0.35f), darkSurface)
        val bright = clampCoverTint(Color(0.75f, 0.6f, 0.98f), darkSurface)
        assertEquals(relativeLuminance(dull), relativeLuminance(bright), 0.02f)
    }

    @Test
    fun the_worst_case_cover_is_not_left_invisible() {
        // Светлота обложки совпала со светлотой поверхности — наивная реализация
        // вернула бы невидимую полосу.
        val out = clampCoverTint(Color(0.05f, 0.05f, 0.06f), darkSurface)
        assertTrue(contrastRatio(out, darkSurface) >= 4.5f)
    }

    @Test
    fun the_hue_survives_the_clamp() {
        val cover = Color(0.15f, 0.6f, 0.45f)
        val out = clampCoverTint(cover, darkSurface)
        assertEquals(cover.toOklch().h, out.toOklch().h, 0.06f)
    }

    @Test
    fun chroma_is_capped_but_not_killed() {
        val acid = Color(0.1f, 1f, 0.05f) // C ≈ 0.29
        val out = clampCoverTint(acid, darkSurface).toOklch()
        assertTrue("хроматика зажата: ${out.c}", out.c <= CoverMaxChroma + 1e-3f)
        assertTrue("но тон обложки остался: ${out.c}", out.c >= CoverChromaFloor)
    }

    @Test
    fun a_chroma_just_below_the_floor_is_noise_and_becomes_grey() {
        val almostGrey = Color(0.52f, 0.5f, 0.51f)
        assertTrue(almostGrey.toOklch().c < CoverChromaFloor)
        assertTrue(clampCoverTint(almostGrey, darkSurface).toOklch().c < 1e-3f)
    }

    @Test
    fun the_target_is_honoured_not_the_minimum() {
        val soft = clampCoverTint(Color(0.9f, 0.4f, 0.2f), darkSurface, targetContrast = 3.0f)
        val loud = clampCoverTint(Color(0.9f, 0.4f, 0.2f), darkSurface, targetContrast = 7.0f)
        assertEquals(3.0f, contrastRatio(soft, darkSurface), 0.35f)
        assertEquals(7.0f, contrastRatio(loud, darkSurface), 0.35f)
        assertTrue(relativeLuminance(soft) < relativeLuminance(loud))
    }

    @Test
    fun an_impossible_target_falls_back_to_pure_polarity() {
        // Средне-серая поверхность: максимум контраста ≈ 4.58, цель 6 недостижима,
        // хроматика режется дважды, последний рубеж — белый или чёрный.
        val mid = Color(0.5f, 0.5f, 0.5f)
        val out = clampCoverTint(Color(0.9f, 0.2f, 0.1f), mid, targetContrast = 6f, minContrast = 3f)
        assertTrue("последний рубеж: ${rgb(out)}", close(out, Color.White) || close(out, Color.Black))
        assertTrue(contrastRatio(out, mid) > 3.2f)
    }

    @Test
    fun the_max_chroma_parameter_is_respected() {
        val acid = Color(0.1f, 1f, 0.05f)
        assertTrue(clampCoverTint(acid, darkSurface, maxChroma = 0.03f).toOklch().c <= 0.035f)
    }

    @Test
    fun every_shipped_surface_gets_at_least_AA_from_any_cover() {
        val surfaces = listOf(
            Color(0.05f, 0.05f, 0.06f),
            Color(0.09f, 0.11f, 0.13f),
            Color(0.96f, 0.94f, 0.90f),
            Color(0.99f, 0.99f, 0.99f),
            Color(0.13f, 0.16f, 0.20f),
            Color(0.93f, 0.88f, 0.80f),
        )
        val covers = listOf(
            Color(0.9f, 0.2f, 0.15f), Color(0.1f, 1f, 0.05f), Color(0.02f, 0.02f, 0.03f),
            Color(1f, 1f, 1f), Color(0.5f, 0.5f, 0.5f), Color(0.8f, 0.7f, 0.1f),
        )
        for (s in surfaces) for (c in covers) {
            val out = clampCoverTint(c, s)
            assertTrue(
                "поверх $s обложка $c даёт контраст ${contrastRatio(out, s)}",
                contrastRatio(out, s) >= 4.5f,
            )
        }
    }

    @Test
    fun drift_measures_how_far_the_clamp_had_to_go() {
        assertEquals(0f, coverTintDrift(darkSurface, darkSurface), 1e-6f)
        val out = clampCoverTint(Color(0.02f, 0.02f, 0.02f), darkSurface)
        assertEquals(abs(relativeLuminance(Color(0.02f, 0.02f, 0.02f)) - relativeLuminance(out)), out.let { coverTintDrift(Color(0.02f, 0.02f, 0.02f), it) }, 1e-6f)
        assertTrue(coverTintDrift(Color(0.02f, 0.02f, 0.02f), out) > 0f)
    }

    @Test
    fun an_alpha_less_result_is_not_possible_from_the_clamp() {
        assertEquals(1f, clampCoverTint(Color(0.4f, 0.4f, 0.9f), lightSurface).alpha, 0f)
        assertEquals(1f, Oklch(0.7f, 0.05f, 1.2f).toColor().alpha, 0f)
    }

    @Test
    fun toColor_clamps_the_alpha_it_is_given() {
        assertEquals(1f, Oklch(0.7f, 0f, 0f).toColor(alpha = 3f).alpha, 0f)
        assertEquals(0f, Oklch(0.7f, 0f, 0f).toColor(alpha = -1f).alpha, 0f)
    }
}
