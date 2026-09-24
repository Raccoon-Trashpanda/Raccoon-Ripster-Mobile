package net.ripster.mobile.ui.premium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Табличный прогон выбора «что дать этому устройству».
 *
 * Почему тестом, а не глазами: ветка «Android 12» не запустится на приборе
 * владельца (у него Android 9 и эмулятор API 30), а именно там стекло ломается
 * в первую очередь. Единственная защита от «на 33 работает, на 31 — чёрный
 * квадрат» — перебрать границы здесь.
 *
 * Второе, что сторожит этот файл: у плана БОЛЬШЕ НЕТ фона. Владелец verdict'ом
 * потребовал, чтобы режим менял стиль органов управления, а не картинку за
 * ними, — любая ветка, которая вернёт «а ещё залируем экран», считается
 * вернувшимся багом.
 */
class PremiumVisualsTest {

    private fun plan(
        enabled: Boolean = true,
        sdk: Int = 34,
        anim: Boolean = false,
        saver: Boolean = false,
        adaptive: Boolean = true,
    ) = PremiumVisuals.resolve(enabled, sdk, anim, saver, adaptive)

    // ── границы версий ──────────────────────────────────────────────────────

    @Test
    fun `android 13 plus gets glass that refracts at its edges`() {
        for (sdk in 33..36) {
            val p = plan(sdk = sdk)
            assertEquals("SDK $sdk", GlassTier.Refractive, p.glass)
            assertTrue("SDK $sdk", p.glass.hasBlur)
            assertTrue("SDK $sdk", p.lyricsDepth)
        }
    }

    @Test
    fun `android 12 to 12L get real blur, no refraction`() {
        for (sdk in 31..32) {
            val p = plan(sdk = sdk)
            assertEquals("SDK $sdk", GlassTier.Blurred, p.glass)
            assertTrue("SDK $sdk", p.glass.hasBlur)
            assertTrue("SDK $sdk", p.lyricsDepth)
        }
    }

    @Test
    fun `older android keeps cheap tinted glass`() {
        for (sdk in 26..30) {
            val p = plan(sdk = sdk)
            assertEquals("SDK $sdk", GlassTier.Tinted, p.glass)
            assertFalse("SDK $sdk", p.glass.hasBlur)
            assertFalse("SDK $sdk", p.lyricsDepth)
            // Движение пружинами дешёвое и не требует новых API — оно остаётся.
            assertTrue("SDK $sdk", p.springMotion)
        }
    }

    @Test
    fun `min sdk of the app is still served`() {
        // minSdk 26 не двигался ради режима — нижняя граница обязана получать
        // осмысленный план, а не падать в неизвестную ветку.
        assertEquals(GlassTier.Tinted, plan(sdk = 26).glass)
        assertEquals(GlassTier.Tinted, plan(sdk = 21).glass)
    }

    // ── выключатель и настройки пользователя ────────────────────────────────

    @Test
    fun `off by default - nothing changes`() {
        val p = plan(enabled = false, sdk = 36)
        assertEquals(PremiumPlan.Disabled, p)
        assertEquals(GlassTier.Plain, p.glass)
        assertFalse(p.glass.hasBlur)
    }

    @Test
    fun `adaptive colors off keeps the glass and drops only its hue`() {
        val p = plan(adaptive = false, sdk = 34)
        assertFalse(p.tintsFromContent)
        // Стекло — не цвет обложки: оно остаётся стеклом, просто нейтральным.
        assertEquals(GlassTier.Refractive, p.glass)
        assertTrue(p.springMotion)
    }

    // ── системный отказ от анимации и экономия заряда ───────────────────────

    @Test
    fun `reduce motion stops the springs but keeps the glass`() {
        val p = plan(anim = true, sdk = 34)
        assertFalse(p.springMotion)
        // Нулевая шкала анимаций не делает стекло дороже и не требует кадров:
        // снимать его за это — значит наказать человека за чужую настройку.
        assertEquals(GlassTier.Refractive, p.glass)
    }

    @Test
    fun `battery saver downgrades everything expensive`() {
        val p = plan(saver = true, sdk = 34)
        assertEquals(GlassTier.Tinted, p.glass)
        assertFalse(p.springMotion)
        assertFalse(p.lyricsDepth)
    }

    @Test
    fun `battery saver keeps cheap glass on every version`() {
        for (sdk in 26..36) {
            val p = plan(sdk = sdk, saver = true)
            assertEquals("SDK $sdk", GlassTier.Tinted, p.glass)
            assertFalse("SDK $sdk", p.glass.hasBlur)
        }
    }

    @Test
    fun `version constants match the real platform APIs`() {
        // RuntimeShader — API 33 (Android 13), RenderEffect — API 31 (Android 12).
        // Если правки подвинут константу, план начнёт обещать то, чего в системе
        // нет, и плеер упадёт на первом же кадре.
        assertEquals(33, PremiumVisuals.MIN_SDK_REFRACTION)
        assertEquals(31, PremiumVisuals.MIN_SDK_REAL_BLUR)
        assertEquals(LiquidGlass.MIN_SDK_REFRACTIVE, PremiumVisuals.MIN_SDK_REFRACTION)
        assertEquals(LiquidGlass.MIN_SDK_BLURRED, PremiumVisuals.MIN_SDK_REAL_BLUR)
    }

    @Test
    fun `settings summary names the same glass the player will draw`() {
        // Строка «на этом телефоне» обязана соответствовать вердикту resolve для
        // тех же входах — иначе настройка обещает одно, а экран рисует другое.
        assertEquals("glass.liquid", PremiumVisuals.glassSummaryKey(plan(sdk = 34).glass))
        assertEquals("glass.blur", PremiumVisuals.glassSummaryKey(plan(sdk = 31).glass))
        assertEquals("glass.tint", PremiumVisuals.glassSummaryKey(plan(sdk = 28).glass))
        assertEquals("glass.tint", PremiumVisuals.glassSummaryKey(plan(sdk = 34, saver = true).glass))
        assertEquals("glass.off", PremiumVisuals.glassSummaryKey(PremiumPlan.Disabled.glass))
    }

    @Test
    fun `the plan has no background knob - glass is a style, not a wash`() {
        // Владелец: «стекло это не фон, это не навес на весь экран, это стиль
        // самого плеера». План, у которого снова появится ручка фона, значит,
        // что пелена вернулась, — не даём этому пройти молча.
        val fields = PremiumPlan::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.toString(), fields.none { "backdrop" in it || "wash" in it || "background" in it })
    }
}
