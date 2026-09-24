package net.ripster.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шестнадцать акцентов меряются здесь так же, как семь палитр меряются в
 * PaletteContrastTest: замкнутое множество, порог один, исключений нет.
 *
 * Проверяются обе роли акцента, потому что это разные задачи. Как ПОДПИСЬ он
 * лежит на поверхностях темы и обязан читаться сам; как ЗАЛИВКА под текстом
 * темы (text_on_fill) он обязан держать текст, для которого контраст уже
 * доказан создателем палитры. Акцент, прошедший только одну проверку, на
 * экране выглядит то бледным пятном, то нечитаемой кнопкой.
 */
class AccentContrastTest {

    private val threshold = MinTextContrast

    @Test
    fun `every accent stays legible as a label where labels actually sit`() {
        // Совпадает с контрактом PaletteContrastTest для accent_text: canvas и
        // raised. На sunken/overlay подписей акцентным цветом не рисуют.
        for (theme in RipsterThemeName.entries) {
            val base = colorsFor(theme)
            for (swatch in AccentPalette.all) {
                val c = applyAccent(base, swatch.hue)
                for ((onName, bg) in listOf(
                    "canvas" to c.surface_canvas,
                    "raised" to c.surface_raised,
                )) {
                    val ratio = contrastRatio(c.accent_text, bg)
                    assertTrue(
                        "${theme.name}/${swatch.id}: accent_text на $onName = " +
                            "${"%.2f".format(ratio)}:1, нужно ≥ $threshold",
                        ratio >= threshold,
                    )
                }
            }
        }
    }

    @Test
    fun `every accent keeps the palette's own text-on-fill above threshold`() {
        // text_on_fill намеренно НЕ перекрашивается (см. applyAccent), поэтому
        // тест меряет именно пару «текст темы × чужая заливка».
        for (theme in RipsterThemeName.entries) {
            val base = colorsFor(theme)
            for (swatch in AccentPalette.all) {
                val c = applyAccent(base, swatch.hue)
                for (fill in listOf(c.accent_fill, c.accent_hover, c.accent_active)) {
                    val ratio = contrastRatio(c.text_on_fill, fill)
                    assertTrue(
                        "${theme.name}/${swatch.id}: text_on_fill на акценте = " +
                            "${"%.2f".format(ratio)}:1, нужно ≥ $threshold",
                        ratio >= threshold,
                    )
                }
            }
        }
    }

    @Test
    fun `the palette is a closed set of distinct choices`() {
        // Выбор держится за id: он ложится в настройку как строка. Дубликат
        // молча перекрыл бы один из шестнадцати оттенков, а опечатка в id на
        // экране настроек выглядела бы как «сбросилось».
        val all = AccentPalette.all
        assertEquals(16, all.size)
        assertEquals("id уникальны", all.size, all.map { it.id }.toSet().size)
        assertEquals("цвета уникальны", all.size, all.map { it.hue }.toSet().size)
        for (swatch in all) {
            assertEquals(swatch.id, swatch, AccentPalette.byId(swatch.id))
        }
    }

    @Test
    fun `legibleAccent fixes only what is broken`() {
        val darkCanvas = colorsFor(RipsterThemeName.Dark).surface_canvas
        val lightCanvas = colorsFor(RipsterThemeName.Light).surface_canvas

        // Уже читаемый оттенок сохраняем как есть (иначе выбор человека
        // сплющивается к порогу и «ярко-синий» становится грязно-серым).
        val azure = Color(0xFF4DA3FF)
        assertEquals(
            "azure уже проходит по тёмному холсту",
            azure.toOklch().l.toDouble(),
            legibleAccent(azure, listOf(darkCanvas)).toOklch().l.toDouble(),
            0.02,
        )

        // Тёмно-синий на почти чёрном — невидимка: светлота уходит ОТ холста.
        val navy = Color(0xFF141E38)
        val lifted = legibleAccent(navy, listOf(darkCanvas))
        assertTrue("по тёмному холсту акцент светлеет", lifted.toOklch().l > navy.toOklch().l)
        assertTrue(contrastRatio(lifted, darkCanvas) >= threshold)

        // Молочный на белом — та же история в другую сторону.
        val milk = Color(0xFFF2EFE6)
        val dropped = legibleAccent(milk, listOf(lightCanvas))
        assertTrue("по светлому холсту акцент темнеет", dropped.toOklch().l < milk.toOklch().l)
        assertTrue(contrastRatio(dropped, lightCanvas) >= threshold)
    }

    @Test
    fun `an impossible hue degrades instead of vanishing`() {
        // Чистый жёлтый на белом: по всем каналам он темнее порога быть не
        // может. «Оставить как есть» означало бы невидимую подпись, поэтому
        // хроматика режется, а на последнем рубеже остаётся ахроматика.
        val light = colorsFor(RipsterThemeName.Light)
        val surfaces = listOf(light.surface_canvas, light.surface_sunken)
        val out = legibleAccent(Color(0xFFFFF000), surfaces)
        for (bg in surfaces) {
            val ratio = contrastRatio(out, bg)
            assertTrue("${"%.2f".format(ratio)}:1", ratio >= threshold)
        }
        assertTrue(
            "критична читаемость, хроматика уходит первой",
            out.toOklch().c < Color(0xFFFFF000).toOklch().c,
        )
    }

    @Test
    fun `no accent means no repaint - the palette comes back untouched`() {
        for (theme in RipsterThemeName.entries) {
            val base = colorsFor(theme)
            assertEquals(base, applyAccent(base, null))
            // Неизвестный id (уехал в даунгрейд приложения, например) — это
            // «по теме», а не чёрный квадрат вместо акцента.
            assertEquals(base, applyAccent(base, AccentPalette.byId("нет-такого")?.hue))
            assertEquals(null, AccentPalette.byId(ACCENT_FROM_THEME))
        }
    }

    @Test
    fun `material you is gated on the real API floor, with a reason`() {
        assertTrue(materialYouSupported(31))
        assertTrue(materialYouSupported(36))
        assertTrue(!materialYouSupported(30))
        assertEquals(MaterialYouStatus.Available, materialYouStatus(31))
        assertEquals(MaterialYouStatus.RequiresAndroid12, materialYouStatus(30))
        assertEquals(MaterialYouStatus.RequiresAndroid12, materialYouStatus(21))
        // Порог обязан совпадать с тем, что говорит система: ниже 31 у
        // Android просто нет динамической палитры.
        assertEquals(31, MaterialYouMinSdk)
    }
}
