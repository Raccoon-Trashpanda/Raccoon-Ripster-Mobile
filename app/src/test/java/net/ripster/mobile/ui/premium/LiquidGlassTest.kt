package net.ripster.mobile.ui.premium

import androidx.compose.ui.graphics.Color
import net.ripster.mobile.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Числа стекла: какой слой положен телефону, из чего складывается поверхность и
 * читается ли глиф поверх неё.
 *
 * Здесь нет ни эмулятора, ни глаз — и это ровно тот случай, где их заменить
 * нечем: «стекло получилось серой пеленой» на приборе видно, а «телефон 32
 * получил линзу, которой у него нет» или «иконка на просвете стекла провалилась
 * до 3.1» — нет. Поэтому границы перебираются таблицей, а не надеждой.
 */
class LiquidGlassTest {

    private val dark = true

    // ── какой слой стекла дать телефону ────────────────────────────────────

    @Test
    fun `mode off leaves every control exactly as it was`() {
        for (sdk in intArrayOf(26, 30, 31, 32, 33, 34, 35)) {
            assertEquals("sdk=$sdk", GlassTier.Plain, LiquidGlass.tierFor(enabled = false, sdkInt = sdk, batterySaver = false))
            assertEquals("sdk=$sdk saver", GlassTier.Plain, LiquidGlass.tierFor(enabled = false, sdkInt = sdk, batterySaver = true))
        }
    }

    @Test
    fun `lens needs RuntimeShader, blur needs RenderEffect`() {
        // Ровно границы API, а не «примерно с одиннадцатой».
        assertEquals(GlassTier.Refractive, LiquidGlass.tierFor(true, LiquidGlass.MIN_SDK_REFRACTIVE, false))
        assertEquals(GlassTier.Blurred, LiquidGlass.tierFor(true, LiquidGlass.MIN_SDK_REFRACTIVE - 1, false))
        assertEquals(GlassTier.Blurred, LiquidGlass.tierFor(true, LiquidGlass.MIN_SDK_BLURRED, false))
        for (sdk in intArrayOf(26, 29, 30)) {
            assertEquals("sdk=$sdk", GlassTier.Tinted, LiquidGlass.tierFor(true, sdk, false))
        }
    }

    @Test
    fun `battery saver drops the per-frame layers but keeps the shape`() {
        // Экономия не имеет права оставить органы без кромки и блика — она
        // обязана снять то, что стоит GPU-прохода каждый кадр.
        for (sdk in intArrayOf(26, 31, 33, 35)) {
            assertEquals("sdk=$sdk", GlassTier.Tinted, LiquidGlass.tierFor(true, sdk, batterySaver = true))
        }
        val saver = LiquidGlass.surfaceFor(GlassTier.Tinted, Color.DarkGray, dark)!!
        assertEquals(0f, saver.lens, 0f)
    }

    // ── параметры поверхности ──────────────────────────────────────────────

    @Test
    fun `Plain has no surface at all`() {
        assertNull(LiquidGlass.surfaceFor(GlassTier.Plain, Color.DarkGray, dark))
    }

    @Test
    fun `every glass layer has a highlight, a rim and an inner shadow`() {
        for (tier in listOf(GlassTier.Tinted, GlassTier.Blurred, GlassTier.Refractive)) {
            val s = LiquidGlass.surfaceFor(tier, Color(0xFF2A2118), dark)!!
            assertTrue("$tier highlight", s.highlight > 0f)
            assertTrue("$tier rim", s.rim > 0f)
            assertTrue("$tier inner shadow", s.innerShadow > 0f)
            assertTrue("$tier elevation", s.elevationDp > 0f)
            // Блик и тень обязаны заканчиваться ДО середины высоты: иначе это
            // не грань стекла, а градиент через всю плашку.
            assertTrue("$tier highlightAt", s.highlightAt in 0f..0.5f)
            assertTrue("$tier innerShadowAt", s.innerShadowAt in 0f..0.5f)
        }
    }

    @Test
    fun `richer glass is clearer glass`() {
        // Против того, за что режим назвали хламом: чем дороже слой, тем МЕНЬШЕ
        // в нём краски. Монолитная плашка — это и есть «серая пелена».
        val a = listOf(GlassTier.Tinted, GlassTier.Blurred, GlassTier.Refractive).map {
            LiquidGlass.surfaceFor(it, Color.DarkGray, dark)!!.tint.alpha
        }
        assertTrue("$a", a.zipWithNext().all { (x, y) -> x > y })
        for (t in a) assertTrue("$t", t in 0f..LiquidGlass.MAX_TINT_ALPHA)
    }

    @Test
    fun `only the top tier refracts`() {
        assertEquals(0f, LiquidGlass.surfaceFor(GlassTier.Tinted, Color.DarkGray, dark)!!.lens, 0f)
        assertEquals(0f, LiquidGlass.surfaceFor(GlassTier.Blurred, Color.DarkGray, dark)!!.lens, 0f)
        assertTrue(LiquidGlass.surfaceFor(GlassTier.Refractive, Color.DarkGray, dark)!!.lens > 0f)
    }

    @Test
    fun `lens stays a lens, not a magnifier`() {
        // 5% от размера органа: на 64-й кнопке это ~3px смещения. Больше —
        // контент за краями стекла начинает «плыть» и читается как баг.
        val lens = LiquidGlass.surfaceFor(GlassTier.Refractive, Color.DarkGray, dark)!!.lens
        assertTrue("$lens", lens in 0f..0.1f)
    }

    @Test
    fun `tint takes the hue of what is behind it, never a flat grey`() {
        val warm = Color(0xFF6B4A22)
        val cool = Color(0xFF1E2C52)
        val tw = LiquidGlass.tintFor(warm, dark)
        val tc = LiquidGlass.tintFor(cool, dark)
        assertTrue("тёплый задник даёт тёплое стекло", tw.red - tw.blue > tc.red - tc.blue)
        // Серая пелена = тон без насыщенности. Стекло обязано оставаться
        // подкрашенным тем, что через него видно.
        fun spread(c: Color) = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
        assertTrue(spread(tw).toString(), spread(tw) > 0.05f)
        assertTrue(spread(tc).toString(), spread(tc) > 0.05f)
    }

    @Test
    fun `tint alpha is capped`() {
        val t = LiquidGlass.tintFor(Color.White, dark, alpha = 1f)
        assertEquals(LiquidGlass.MAX_TINT_ALPHA, t.alpha, 1e-6f)
    }

    // ── читаемость на стекле ───────────────────────────────────────────────

    @Test
    fun `compositing matches what the eye gets`() {
        val bg = Color(0xFF123456)
        assertNear(bg, LiquidGlass.composited(Color.White.copy(alpha = 0f), bg))
        assertNear(Color.Red, LiquidGlass.composited(Color.Red, bg))
        val half = LiquidGlass.composited(Color.White.copy(alpha = 0.5f), bg)
        assertTrue(half.red > bg.red && half.green > bg.green && half.blue > bg.blue)
    }

    private fun assertNear(expected: Color, actual: Color) {
        val d = 1f / 255f
        assertEquals(expected.red, actual.red, d)
        assertEquals(expected.green, actual.green, d)
        assertEquals(expected.blue, actual.blue, d)
        assertEquals(expected.alpha, actual.alpha, d)
    }

    @Test
    fun `surface points include the bright edge and the dark one`() {
        val s = LiquidGlass.surfaceFor(GlassTier.Refractive, Color(0xFF20242A), dark)!!
        val pts = LiquidGlass.surfacePoints(s, Color(0xFF20242A))
        assertEquals(4, pts.size)
        // Худшая точка по белому глифу — не средняя, а та, где стекло светлее
        // всего (блик). Проверка «по тону заливки» пропустила бы именно её.
        val worst = pts.maxByOrNull { net.ripster.mobile.ui.theme.relativeLuminance(it) }!!
        assertTrue(pts.indexOf(worst) == 2)
    }

    @Test
    fun `white glyph on dark-theme glass stays readable over dark content`() {
        val s = LiquidGlass.surfaceFor(GlassTier.Refractive, Color(0xFF17120E), dark)!!
        val kept = LiquidGlass.readableIcon(
            Color.White, s, Color(0xFF17120E), light = Color.White, dark = Color(0xFF0A0A0C),
        )
        assertEquals(Color.White, kept)
        assertTrue(
            "${LiquidGlass.worstIconContrast(kept, s, Color(0xFF17120E))}",
            LiquidGlass.worstIconContrast(kept, s, Color(0xFF17120E)) >= LiquidGlass.MIN_ICON_CONTRAST,
        )
    }

    @Test
    fun `the same glyph is swapped when the glass sits over light content`() {
        // Стекло на белом холсте почти белое: белый глиф на нём исчезает, и
        // режим обязан взять тёмный, а не молча отдать нечитаемую иконку.
        val lightContent = Color(0xFFF2F3F5)
        val s = LiquidGlass.surfaceFor(GlassTier.Blurred, lightContent, dark = false)!!
        val chosen = LiquidGlass.readableIcon(Color.White, s, lightContent, light = Color.White, dark = Color(0xFF0A0A0C))
        assertEquals(Color(0xFF0A0A0C), chosen)
        for (p in LiquidGlass.surfacePoints(s, lightContent)) {
            assertTrue("${p} -> ${contrastRatio(chosen, p)}", contrastRatio(chosen, p) >= LiquidGlass.MIN_ICON_CONTRAST)
        }
    }

    @Test
    fun `icon choice never gets worse than the color it was given`() {
        val s = LiquidGlass.surfaceFor(GlassTier.Refractive, Color(0xFF7A8288), dark)!!
        val backdrop = Color(0xFF7A8288)
        val given = LiquidGlass.worstIconContrast(Color.White, s, backdrop)
        val chosen = LiquidGlass.readableIcon(Color.White, s, backdrop, light = Color.White, dark = Color(0xFF08080A))
        assertTrue("$given -> ${LiquidGlass.worstIconContrast(chosen, s, backdrop)}",
            LiquidGlass.worstIconContrast(chosen, s, backdrop) >= given)
    }

    // ── продавливание под пальцем ──────────────────────────────────────────

    @Test
    fun `no springs means no squish`() {
        assertNull(PressSpring.shapeFor(pressed = true, springsOn = false))
        assertNull(PressSpring.shapeFor(pressed = false, springsOn = false, settle = true))
        val rest = PressSpring.shapeFor(pressed = false, springsOn = true)!!
        assertEquals(1f, rest.scaleX, 0f)
        assertEquals(1f, rest.scaleY, 0f)
    }

    @Test
    fun `press squashes vertically and stretches sideways`() {
        val p = PressSpring.shapeFor(pressed = true, springsOn = true)!!
        assertEquals(PressSpring.PRESS_SQUISH, p.scaleY, 1e-6f)
        assertTrue("$p", p.scaleX > 1f && p.scaleX < PressSpring.RELEASE_OVERSHOOT + 0.05f)
        // «Стекло» не схлопывается в точку: 0.92 — это заметно, но не карикатура.
        assertTrue(PressSpring.PRESS_SQUISH in 0.85f..0.96f)
    }

    @Test
    fun `release overshoots past its own size`() {
        val o = PressSpring.shapeFor(pressed = false, springsOn = true, settle = true)!!
        assertTrue(o.scaleX > 1f && o.scaleY > 1f)
    }

    // ── AGSL-линза: её не проверить глазами на приборе, но можно не сломать ──

    @Test
    fun `the lens shader declares exactly the uniforms the surface feeds it`() {
        // Имя uniform'а — единственная нить между числами Kotlin и шейдером.
        // Опечатка в ней не роняет приложение: Skia просто не соберёт шейдер,
        // стекло на Android 13+ тихо потеряет кромку, и никто никогда не узнает,
        // что оно было хуже, чем могло бы.
        for (u in listOf("uniform float2 uSize", "uniform float uEdge", "uniform float uAmount")) {
            assertTrue("$u", LENS_AGSL.contains(u))
        }
        assertEquals("одна точка входа", 1, Regex("main\\s*\\(").findAll(LENS_AGSL).count())
        for (u in listOf("uSize", "uEdge", "uAmount")) {
            assertTrue("объявлен, но не использован: $u", LENS_AGSL.substringAfter("main").contains(u))
        }
    }

    @Test
    fun `the press squish is a squish, not a collapse`() {
        // 0.92 из претензии владельца к «дорогому» виду: заметно, но глиф ещё
        // читается. Ниже 0.85 — провал, выше 0.97 — не видно вообще.
        assertTrue("${PressSpring.PRESS_SQUISH}", PressSpring.PRESS_SQUISH in 0.85f..0.97f)
    }

    @Test
    fun `no glass layer is opaque enough to read as a paint blob`() {
        // Тот самый провал прошлой сборки: «стекло», через которое ничего не
        // видно, — это серая краска. Пол и для поверхности, и для пружины.
        for (tier in GlassTier.entries) {
            val s = LiquidGlass.surfaceFor(tier, Color(0xFF2A2118), dark) ?: continue
            assertTrue("$tier", s.tint.alpha <= LiquidGlass.MAX_TINT_ALPHA)
        }
    }
}
