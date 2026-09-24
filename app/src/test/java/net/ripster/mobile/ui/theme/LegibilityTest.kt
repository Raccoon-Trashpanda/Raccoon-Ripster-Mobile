package net.ripster.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Читаемость текста поверх чужой поверхности: фон плеера, обложка, стекло.
 *
 * Проверяем три обещания из шапки модуля: альфа снимается и мерить контраст
 * надо по результату; обычный (уже читаемый) текст возвращается без изменения,
 * чтобы дизайн не «улучшался» насильно; если порога не хватает — цвет добирается
 * минимальным шагом, а не до упора.
 */
class LegibilityTest {

    /** Тот самый near-black фон «Studio»/«Immersive». */
    private val player = Color(0.04f, 0.04f, 0.05f)
    private val paper = Color(0.97f, 0.96f, 0.94f)

    // ── blendOver ────────────────────────────────────────────────────────────

    @Test
    fun an_opaque_colour_blends_into_nothing() {
        val ink = Color(0.2f, 0.3f, 0.8f)
        assertEquals(ink, blendOver(ink, player))
    }

    @Test
    fun full_transparency_leaves_only_the_background() {
        val flat = blendOver(Color(1f, 1f, 1f, 0f), player)
        assertEquals(player.red, flat.red, 1e-3f)
        assertEquals(player.green, flat.green, 1e-3f)
        assertEquals(player.blue, flat.blue, 1e-3f)
    }

    @Test
    fun half_alpha_is_half_of_the_way_to_the_background() {
        val flat = blendOver(Color.White.copy(alpha = 0.5f), Color.Black)
        assertTrue("между чёрным и белым: ${flat.red}", flat.red > 0f && flat.red < 1f)
        assertTrue(relativeLuminance(flat) > 0f)
        assertTrue(relativeLuminance(flat) < 1f)
    }

    // ── обычный случай: не трогать ───────────────────────────────────────────

    @Test
    fun already_readable_text_comes_back_untouched() {
        val ink = Color(0.12f, 0.12f, 0.14f)
        assertEquals(ink, legibleOn(ink, paper))
    }

    @Test
    fun a_readable_chromatic_accent_is_not_neutralised() {
        val azure = Color(0.35f, 0.62f, 0.95f)
        assertTrue(contrastRatio(azure, player) >= MinTextContrast)
        assertEquals(azure, legibleOn(azure, player))
    }

    @Test
    fun what_legibleOn_returns_is_what_gets_painted() {
        val fg = Color(0.5f, 0.5f, 0.5f, 0.4f)
        val out = legibleOn(fg, paper)
        val painted = blendOver(out, paper) // композитор кладёт результат всё ещё полупрозрачным
        assertTrue(
            "посчитано ${contrastRatio(out, paper)}, на экране ${contrastRatio(painted, paper)}, " +
                "альфа результата ${out.alpha}",
            contrastRatio(painted, paper) >= MinTextContrast,
        )
    }

    // ── добор контраста ──────────────────────────────────────────────────────

    @Test
    fun the_complaint_from_the_screenshot_secondary_text_on_the_player() {
        // Светлая палитра: text_secondary — тёмно-серый, а под ним фон плеера.
        val secondary = Color(0.29f, 0.29f, 0.31f)
        assertTrue("исходник нечитаем", contrastRatio(secondary, player) < MinTextContrast)
        val out = legibleOn(secondary, player)
        assertTrue("стал читаем: ${contrastRatio(out, player)}", contrastRatio(out, player) >= MinTextContrast)
        assertTrue("ушёл вверх, а не вниз", relativeLuminance(out) > relativeLuminance(secondary))
    }

    @Test
    fun on_a_light_background_dark_text_is_pulled_darker() {
        val faint = Color(0.72f, 0.72f, 0.70f)
        assertTrue(contrastRatio(faint, paper) < MinTextContrast)
        val out = legibleOn(faint, paper)
        assertTrue(contrastRatio(out, paper) >= MinTextContrast)
        assertTrue(relativeLuminance(out) < relativeLuminance(faint))
    }

    @Test
    fun text_painted_the_same_colour_as_its_own_background_is_rescued() {
        val out = legibleOn(paper, paper)
        assertTrue(contrastRatio(out, paper) >= MinTextContrast)
    }

    @Test
    fun the_fix_is_the_minimal_step_not_a_dump_to_pure_ink() {
        val faint = Color(0.72f, 0.72f, 0.70f)
        val out = legibleOn(faint, paper)
        assertTrue("не до чёрного", relativeLuminance(out) > 0.05f)
        assertTrue("но порог взят ровно", contrastRatio(out, paper) < MinTextContrast + 0.3f)
    }

    @Test
    fun dimming_is_capped_by_the_threshold_rather_than_by_the_alpha() {
        val caption = Color(0.85f, 0.85f, 0.88f)
        for (dim in listOf(0f, 0.3f, 0.6f, 0.9f, 1f)) {
            val out = dimOn(caption, player, dim)
            assertTrue("dim=$dim → ${contrastRatio(out, player)}", contrastRatio(out, player) >= MinDimmedContrast)
        }
    }

    @Test
    fun dim_on_zero_is_plain_legibility_at_the_dimmed_threshold() {
        val caption = Color(0.85f, 0.85f, 0.88f)
        assertEquals(legibleOn(caption, player, MinDimmedContrast), dimOn(caption, player, 0f))
    }

    @Test
    fun dissolving_the_text_completely_still_leaves_a_readable_line() {
        val out = dimOn(Color(0.85f, 0.85f, 0.88f), player, 1f)
        assertTrue(contrastRatio(out, player) >= MinDimmedContrast)
    }

    @Test
    fun a_dim_value_outside_the_scale_is_clamped_not_trusted() {
        val caption = Color(0.85f, 0.85f, 0.88f)
        assertEquals(dimOn(caption, player, 1f), dimOn(caption, player, 7f))
        assertEquals(dimOn(caption, player, 0f), dimOn(caption, player, -3f))
    }

    @Test
    fun more_dim_moves_the_text_toward_the_surface() {
        val caption = Color(0.85f, 0.85f, 0.88f)
        val soft = dimOn(caption, player, 0.4f)
        val faded = dimOn(caption, player, 0.85f)
        assertTrue(relativeLuminance(faded) < relativeLuminance(soft))
        assertTrue(contrastRatio(faded, player) < contrastRatio(soft, player))
    }

    // ── пороги ───────────────────────────────────────────────────────────────

    @Test
    fun the_thresholds_are_the_ones_the_module_promises() {
        assertEquals(4.5f, MinTextContrast, 0f)
        assertEquals(3.2f, MinDimmedContrast, 0f)
        assertTrue("приглушённый текст — не «крупный» 3:1", MinDimmedContrast > 3f)
    }

    @Test
    fun the_captions_seek_strip_actually_passes_clear_AA_once_lifted() {
        val surfaces = listOf(player, Color(0.09f, 0.11f, 0.13f), Color(0.02f, 0.02f, 0.03f))
        val inks = listOf(
            Color(0.29f, 0.29f, 0.31f), Color(0.55f, 0.55f, 0.58f), Color(0.15f, 0.15f, 0.16f),
            Color(0.8f, 0.8f, 0.85f, 0.5f),
        )
        for (s in surfaces) for (i in inks) {
            val out = legibleOn(i, s)
            assertTrue("фон $s текст $i → ${contrastRatio(out, s)}", contrastRatio(out, s) >= MinTextContrast)
        }
    }

    @Test
    fun an_acid_background_still_yields_AA_text() {
        val acid = Color(0.55f, 0.9f, 0.1f)
        val out = legibleOn(Color(0.5f, 0.5f, 0.5f), acid)
        assertTrue("порог взят: ${contrastRatio(out, acid)}", contrastRatio(out, acid) >= MinTextContrast)
    }

    /**
     * Реальный вызов (ImmersivePlayerScreen.kt:250): подпись времени
     * `Color.White.copy(alpha = 0.6f)` поверх зажатого тона обложки. Тон обложки
     * зажим поднимает до контраста 4.5 против почти чёрного фона — то есть это
     * ровно та средне-серая зона, где белый уже НЕ дотягивает до AA, а чёрный
     * дотягивает.
     */
    @Test
    fun the_time_caption_over_a_cover_tint_reaches_AA() {
        val tint = clampCoverTint(Color(0.2f, 0.45f, 0.8f), player)
        val caption = legibleOn(Color.White.copy(alpha = 0.6f), tint)
        assertTrue(
            "тон обложки L=${relativeLuminance(tint)}, контраст подписи ${contrastRatio(caption, tint)}",
            contrastRatio(caption, tint) >= MinTextContrast,
        )
    }

    @Test
    fun the_polarity_threshold_and_the_equal_margin_point_disagree() {
        // 0.179 — точка равного запаса вверх и вниз (выведена в CoverTint).
        val atEqualMargin = Color(0.5f, 0.5f, 0.5f) // L ≈ 0.216, уже за точкой
        val white = contrastRatio(Color.White, atEqualMargin)
        val black = contrastRatio(Color.Black, atEqualMargin)
        assertTrue("чёрный читается лучше — его `legibleOn` и берёт", black > white)
        assertTrue(
            "порог 0.5 в legibleOn лежит выше точки равного запаса",
            relativeLuminance(atEqualMargin) < 0.5f && relativeLuminance(atEqualMargin) > 0.179f,
        )
    }
}
