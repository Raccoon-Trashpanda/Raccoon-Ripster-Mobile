package net.ripster.mobile.ui.premium

import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import net.ripster.mobile.ui.theme.Motion
import net.ripster.mobile.ui.theme.relativeLuminance

/**
 * Что получает панель этого плеера: стекло какого слоя и чей цвет оно берёт.
 *
 * Раньше этот файл решал, какой ФОН нарисовать под панелями; теперь фона как
 * решения режима нет (вердикт владельца: «стекло это не фон, это стиль самого
 * плеера»), и решать осталось одно — из чего состоит сама панель. Ошибиться
 * здесь значит получить «стекло есть, но оно не размывает то, что видно»: на
 * приборе это неотличимо от «телефон не тянет».
 */
class PremiumPlayerFxTest {

    private fun fx(
        tier: GlassTier,
        tints: Boolean = true,
        springs: Boolean = true,
        haze: dev.chrisbanes.haze.HazeState? = null,
    ) = PremiumPlayerFx(
        PremiumPlan(
            glass = tier,
            tintsFromContent = tints,
            springMotion = springs,
            lyricsDepth = true,
        ),
        haze,
    )

    private val warm = Color(0xFF8A5A20)
    private val cool = Color(0xFF203A8A)

    private fun surface(plan: PremiumPlayerFx, behind: Color, dark: Boolean = true) =
        plan.surfaceFor(behind, dark)

    // ── слой стекла ─────────────────────────────────────────────────────────

    @Test
    fun `disabled plan hands panels no surface at all`() {
        // Проверка «выключено = ровно как было» начинается здесь: у Plain
        // поверхности нет, и панель обязана остаться своей заливкой темы.
        assertNull(fx(GlassTier.Plain).surfaceFor(warm, dark = true))
        assertNull(PremiumPlayerFx(PremiumPlan.Disabled, null).surfaceFor(warm, dark = true))
    }

    @Test
    fun `every enabled tier gives a surface with a rim and a highlight`() {
        for (tier in listOf(GlassTier.Tinted, GlassTier.Blurred, GlassTier.Refractive)) {
            val s = surface(fx(tier), warm)
            assertNotNull("у $tier поверхности нет", s)
            assertTrue("$tier", s!!.rim > 0f && s.highlight > 0f && s.innerShadow > 0f)
        }
    }

    @Test
    fun `richer glass is clearer glass`() {
        // Дорогое стекло прозрачнее дешёвого: за ним должно быть видно картинку,
        // а не серая краска — именно за серую краску режим и выставили.
        val a = listOf(
            surface(fx(GlassTier.Tinted), warm)!!.tint.alpha,
            surface(fx(GlassTier.Blurred), warm)!!.tint.alpha,
            surface(fx(GlassTier.Refractive), warm)!!.tint.alpha,
        )
        assertTrue("$a", a[0] > a[1] && a[1] > a[2])
        for (t in a) assertTrue("$t", t in 0f..LiquidGlass.MAX_TINT_ALPHA)
    }

    // ── чей цвет подмешивает панель ─────────────────────────────────────────

    @Test
    fun `glass takes the hue of what is behind it`() {
        val t = fx(GlassTier.Refractive, tints = true)
        val warmTint = surface(t, warm)!!.tint
        val coolTint = surface(t, cool)!!.tint
        assertTrue("тёплое краснее холодного", warmTint.red > coolTint.red)
        assertTrue("холодное синее тёплого", coolTint.blue > warmTint.blue)
    }

    @Test
    fun `adaptive colors off keeps the glass but neutralises its hue`() {
        // «Адаптивные цвета» сняты → ни обложка, ни что-либо ещё не красит
        // панели. Стекло при этом остаётся: у него есть кромка, блик и толщина.
        val t = fx(GlassTier.Refractive, tints = false)
        assertEquals(surface(t, warm)!!.tint, surface(t, cool)!!.tint)
    }

    @Test
    fun `light theme glass is mixed from the other paint`() {
        // В светлой теме базовая краска тёмная, иначе на светлом холсте стекло
        // не читается как объём. Проверяем, что тема действительно меняет вердикт.
        val t = fx(GlassTier.Blurred)
        val d = relativeLuminance(surface(t, cool, dark = true)!!.tint)
        val l = relativeLuminance(surface(t, cool, dark = false)!!.tint)
        assertTrue("d=$d l=$l", l < d)
    }

    // ── нужно ли вообще заводить слой-источник ──────────────────────────────

    @Test
    fun `only blurred tiers ask for a haze source`() {
        // Лишний HazeState — лишняя перерисовка слоя каждый кадр, поэтому
        // телефон без RenderEffect (или в экономии заряда) обязан остаться с
        // null: панель тогда рисуется ровно своей заливкой.
        assertEquals(false, GlassTier.Plain.hasBlur)
        assertEquals(false, GlassTier.Tinted.hasBlur)
        assertEquals(true, GlassTier.Blurred.hasBlur)
        assertEquals(true, GlassTier.Refractive.hasBlur)
    }

    @Test
    fun `springs follow the plan, not the tier`() {
        // Пружины и стекло — разные вердикты: на Android 9 (Tinted) пружина
        // положена, а на телефоне с «убрать анимацию» — нет, даже со стеклом.
        assertSame(Motion.pressScale, fx(GlassTier.Refractive, springs = true).pressSpec)
        assertTrue(
            "пружина нажатия должна быть с недобоем",
            (fx(GlassTier.Refractive, springs = true).pressSpec as SpringSpec<Float>).dampingRatio < 1f,
        )
        assertTrue(
            "без пружин — обычная отработка",
            fx(GlassTier.Refractive, springs = false).pressSpec !== Motion.pressScale,
        )
    }
}
