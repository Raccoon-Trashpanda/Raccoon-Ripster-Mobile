package net.ripster.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистая геометрия транспорта «Флюида» — без Android и без экрана: только то,
 * что решает вид. Вердикт, из-за которого стиль заведён («у эпл большие
 * кнопочки, мягкие»), выполняется числами: морф обязан заканчиваться ровно
 * двумя фигурами, скругление паузы — быть капсульным, разлёт шашечек —
 * монотонным, а блоб отклика — не иметь промежуточных состояний «ни жив, ни
 * мёртв». На эмуляторе это не проверить; здесь — таблица.
 */
class LiquidTransportLogicTest {

    private fun quadArea(q: FloatArray): Float {
        // Shoelace для четырёх вершин.
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return kotlin.math.abs(s) / 2f
    }

    @Test
    fun `morph target follows playback state`() {
        assertEquals(LiquidGlyphMath.MORPH_PAUSE, LiquidGlyphMath.morphTarget(true), 1e-6f)
        assertEquals(LiquidGlyphMath.MORPH_PLAY, LiquidGlyphMath.morphTarget(false), 1e-6f)
    }

    @Test
    fun `morph ends are the exact figures, not approximations`() {
        val (pl, pr) = LiquidGlyphMath.playPauseQuads(LiquidGlyphMath.MORPH_PLAY)
        // На play правая половина — «стрелка»: её верхняя и нижняя точки
        // совпадают в вершине треугольника, левая половина стыкуется с ней по
        // шву x=0.55 — вместе это ровно один треугольник, без щели.
        assertEquals(pr[2], pr[4], 1e-6f)
        assertEquals(pr[3], pr[5], 1e-6f)
        assertEquals(pl[2], pr[0], 1e-6f)
        assertTrue("левая половина play обязана иметь площадь", quadArea(pl) > 0.05f)

        val (al, ar) = LiquidGlyphMath.playPauseQuads(LiquidGlyphMath.MORPH_PAUSE)
        // Морф идёт к «паузе»: правый клин треугольника (сам — треугольник с
        // повторенной вершиной) обязан стать шашечкой строго больше по площади.
        assertTrue("шашечка паузы не крупнее клина play", quadArea(ar) > quadArea(pr))
        // Пауза — две зеркальные шашечки одинаковой высоты и ширины 0.16.
        assertEquals(0.16f, al[2] - al[0], 1e-6f)
        assertEquals(0.16f, ar[2] - ar[0], 1e-6f)
        assertEquals(al[3], ar[1], 1e-6f)
        assertEquals(al[5], ar[5], 1e-6f)
        // Зеркальность шашечек относительно центра.
        assertEquals(1f - ar[0], al[2], 1e-6f)
    }

    @Test
    fun `play halves tile the exact triangle - shared seam, no protrusion, one centroid`() {
        val (l, r) = LiquidGlyphMath.playPauseQuads(LiquidGlyphMath.MORPH_PLAY)
        // Эталонный треугольник play, из которого разрезаны обе половины.
        val ax = 0.22f; val ay = 0.12f
        val bx = 0.22f; val by = 0.88f
        val cx = 0.88f; val cy = 0.50f

        // 1. ШОВ: половины стыкуются ровно по вертикали x=0.55 — верхняя точка
        //    шва левого куска совпадает с верхней правого, нижняя — с нижней.
        assertEquals(0.55f, l[2], 1e-6f)
        assertEquals(l[2], r[0], 1e-6f)
        assertEquals(l[3], r[1], 1e-6f)
        assertEquals(l[4], r[6], 1e-6f)
        assertEquals(l[5], r[7], 1e-6f)

        // 2. НЕТ НАРОСТА: габарит объединения обеих половин = габариту
        //    треугольника. Ни одна вершина не левее основания и не правее
        //    вершины — иначе «маленький треугольник торчит справа».
        val ink = LiquidGlyphMath.playPauseInk(LiquidGlyphMath.MORPH_PLAY)
        assertEquals(ax, ink.left, 1e-6f)
        assertEquals(cx, ink.right, 1e-6f)
        for (q in listOf(l, r)) {
            for (i in q.indices step 2) {
                assertTrue("вершина вылезла за габарит треугольника", q[i] in ax..cx)
            }
        }

        // 3. ОДИН ЦЕНТР ТЯЖЕСТИ: площади складываются в площадь треугольника
        //    (нет ни щели, ни двойного наложения), а взвешенный центроид
        //    половинок совпадает с центроидом целого — значит это ровно ОНА
        //    фигура, а не два пересекающихся рисунка.
        val tri = floatArrayOf(ax, ay, bx, by, cx, cy)
        assertEquals(LiquidGlyphMath.triangleArea(tri), quadArea(l) + quadArea(r), 1e-4f)
        // Центроид треугольника — среднее его вершин.
        assertEquals((ax + bx + cx) / 3f, ink.cx, 1e-3f)
        assertEquals((ay + by + cy) / 3f, ink.cy, 1e-3f)
        // Оптически глиф сбалансирован по высоте (не кривой вниз/вверх).
        assertEquals(0.5f, ink.cy, 1e-3f)

        // 4. ШОВ ОСТАЁТСЯ ОСТРЫМ на всём play: иначе обе половины срезают по
        //    углу у x=0.55 и на эмуляторе расходятся двумя фигурами (23.09.2026).
        val (wl, wr) = LiquidGlyphMath.playPauseCornerWeights(LiquidGlyphMath.MORPH_PLAY)
        assertEquals(0f, wl[1], 1e-6f)
        assertEquals(0f, wl[2], 1e-6f)
        assertEquals(0f, wr[0], 1e-6f)
        assertEquals(0f, wr[3], 1e-6f)
        // Внешние углы треугольника (основание) — скруглены целиком.
        assertEquals(1f, wl[0], 1e-6f)
        assertEquals(1f, wl[3], 1e-6f)
    }

    @Test
    fun `pause opens the seam corners into full capsules`() {
        val (wl, wr) = LiquidGlyphMath.playPauseCornerWeights(LiquidGlyphMath.MORPH_PAUSE)
        for (w in listOf(wl, wr)) {
            for (i in 0 until 4) assertEquals("шов паузы обязан раскрыться в капсулу", 1f, w[i], 1e-6f)
        }
        // Между краями вес шва меняется плавно и монотонно от 0 к 1.
        var prev = 0f
        var m = 0.05f
        while (m <= 1f + 1e-6f) {
            val (l, _) = LiquidGlyphMath.playPauseCornerWeights(m)
            assertTrue("вес шва скачет при m=$m", l[1] in prev..(prev + 0.1f))
            prev = l[1]
            m += 0.05f
        }
        assertEquals(1f, prev, 1e-3f)
    }

    @Test
    fun `morph is monotone and stays inside the canvas`() {
        var prevGap = -1f
        var steps = 0
        var m = 0f
        while (m <= 1f + 1e-6f) {
            val (l, r) = LiquidGlyphMath.playPauseQuads(m)
            for (q in listOf(l, r)) {
                for (i in q.indices) {
                    assertTrue("точка глифа вне 0..1 при m=$m", q[i] in 0f..1f)
                }
            }
            // Щель между половинами растёт от сшитого треугольника до паузы.
            val gap = minOf(r[0], r[6]) - maxOf(l[2], l[4])
            assertTrue("разлёт шашечек не монотонен при m=$m", gap > prevGap)
            prevGap = gap
            steps++
            m += 0.05f
        }
        assertEquals(21, steps)
        // Концы: шов у треугольника и честная щель паузы 0.64-0.36=0.28.
        assertEquals(0.28f, prevGap, 1e-3f)
    }

    @Test
    fun `input outside the morph range clamps instead of breaking the glyph`() {
        val (a1, a2) = LiquidGlyphMath.playPauseQuads(-3f)
        val (b1, b2) = LiquidGlyphMath.playPauseQuads(LiquidGlyphMath.MORPH_PLAY)
        assertContentEqualsFlat(a1, b1); assertContentEqualsFlat(a2, b2)
        val (c1, c2) = LiquidGlyphMath.playPauseQuads(7f)
        val (d1, d2) = LiquidGlyphMath.playPauseQuads(LiquidGlyphMath.MORPH_PAUSE)
        assertContentEqualsFlat(c1, d1); assertContentEqualsFlat(c2, d2)
    }

    @Test
    fun `pause corners are capsule-soft and get softer through the morph`() {
        val play = LiquidGlyphMath.morphCornerRadius(LiquidGlyphMath.MORPH_PLAY)
        val pause = LiquidGlyphMath.morphCornerRadius(LiquidGlyphMath.MORPH_PAUSE)
        // Капсула: радиус паузы — ровно половина ширины шашечки (0.16/2).
        assertEquals(0.08f, pause, 1e-6f)
        assertTrue("play-угол обязан быть острее паузы", play < pause)
        assertTrue("скругление обязано оставаться в холсте", pause in 0f..0.2f)
    }

    @Test
    fun `press blob has exactly two states - nothing and bloom`() {
        // Покой: блоба нет совсем (нулевая альфа), он не «призрачный всегда».
        assertEquals(0f, LiquidGlyphMath.blobAlphaTarget(false), 1e-6f)
        assertEquals(0.55f, LiquidGlyphMath.blobScaleTarget(false), 1e-6f)
        // Нажатие: расцветает больше и явнее; альфа — полупрозрачная монета,
        // не глухое пятно (иначе блоб сам стал бы «диском», от которого мы ушли).
        assertTrue(LiquidGlyphMath.blobAlphaTarget(true) in 0.05f..0.30f)
        assertEquals(1f, LiquidGlyphMath.blobScaleTarget(true), 1e-6f)
    }

    @Test
    fun `blob is not in the tree at all while it is invisible`() {
        // Вердикт 23.09.2026 с эмулятора: в покое под каждым глифом был виден
        // серый полупрозрачный круг. Альфа-то была нулевая — но у стеклянного
        // органа есть ещё и тень от возвышения, а её `alpha` не гасит (у слоя
        // для тени своя альфа). Значит невидимый блоб обязан НЕ монтироваться.
        assertFalse(LiquidGlyphMath.blobMounted(LiquidGlyphMath.blobAlphaTarget(false)))
        assertTrue(LiquidGlyphMath.blobMounted(LiquidGlyphMath.blobAlphaTarget(true)))
        assertFalse(LiquidGlyphMath.blobMounted(0f))
        // Первый кадр расцвета уже монтирует орган: «ни жив, ни мёртв» нет.
        assertTrue(LiquidGlyphMath.blobMounted(0.001f))
    }

    @Test
    fun `blob is centred on the glyph centroid, not on its bounding box`() {
        val play = LiquidGlyphMath.playPauseInk(LiquidGlyphMath.MORPH_PLAY)
        // play — треугольник 0.22..0.88 по x: середина габарита 0.55, а центр
        // тяжести — в трети от основания. Ставить свет в 0.55 значило бы
        // светить в пустой угол у вершины.
        assertEquals(0.22f, play.left, 1e-6f)
        assertEquals(0.88f, play.right, 1e-6f)
        assertEquals(0.44f, play.cx, 1e-3f)
        assertEquals(0.5f, play.cy, 1e-3f)
        // Глиф при этом отцентрирован по ГАБАРИТУ (см. следующий тест), и
        // свет едет за центроидом: 0.44 - 0.05 = -0.11 от центра слота.
        assertEquals(-0.05f, LiquidGlyphMath.inkCentering(play), 1e-3f)
        assertEquals(-0.11f, LiquidGlyphMath.blobShift(play), 1e-3f)
        // Пауза зеркальна её же центру: смещения блоба нет ни на вершок.
        assertEquals(0f, LiquidGlyphMath.blobShift(LiquidGlyphMath.playPauseInk(LiquidGlyphMath.MORPH_PAUSE)), 1e-6f)
        // Между краями морфа смещение меняется плавно и монотонно (блоб не
        // «перескакивает» под глифом на середине перехода).
        var prev = LiquidGlyphMath.blobShift(play)
        var m = 0.05f
        while (m <= 1f + 1e-6f) {
            val s = LiquidGlyphMath.blobShift(LiquidGlyphMath.playPauseInk(m))
            assertTrue("смещение блоба скачет при m=$m", kotlin.math.abs(s - prev) < 0.02f)
            assertTrue("смещение не затухает к паузе при m=$m", kotlin.math.abs(s) < kotlin.math.abs(prev))
            prev = s
            m += 0.05f
        }
        assertEquals(0f, prev, 1e-6f)
        // «Назад» и «вперёд» — зеркальные близнецы, и свет под ними тоже
        // зеркален: иначе один из переходов выглядит кривым.
        val fwd = LiquidGlyphMath.skipInk(back = false)
        val back = LiquidGlyphMath.skipInk(back = true)
        assertEquals(1f - fwd.cx, back.cx, 1e-6f)
        assertEquals(-LiquidGlyphMath.blobShift(fwd), LiquidGlyphMath.blobShift(back), 1e-6f)
        // двойной треугольник несимметричен сам по себе (два клина жмутся
        // влево) — потому центрировать рамкой было бы видно на глазке.
        assertNotEquals(0.5f, fwd.cx, 1e-3f)
    }

    @Test
    fun `skip glyph is two proper triangles and mirrors cleanly`() {
        val fwd = LiquidGlyphMath.skipTriangles(back = false)
        val back = LiquidGlyphMath.skipTriangles(back = true)
        assertEquals(2, fwd.size)
        for (t in fwd) {
            assertTrue("треугольник перехода вырожден", LiquidGlyphMath.triangleArea(t) > 0.05f)
            for (v in t) assertTrue("вершина перехода вне 0..1", v in 0f..1f)
        }
        // Зеркало сохраняет площади и меняет направление — иначе «назад»
        // выглядело бы кривым близнецом «вперёд».
        for (i in fwd.indices) {
            assertEquals(LiquidGlyphMath.triangleArea(fwd[i]), LiquidGlyphMath.triangleArea(back[i]), 1e-6f)
            assertEquals(1f - fwd[i][0], back[i][0], 1e-6f)
        }
    }

    @Test
    fun `morph ends in two clean bars, with no triangle sliver left`() {
        // Пауза — финальная форма, её глаз видит дольше всего: если к концу
        // морфа от треугольника остаётся хотя бы щепка (совпавшая вершина,
        // игольчатый край, надлом по шву), это видно на эмуляторе сразу.
        val (l, r) = LiquidGlyphMath.playPauseQuads(LiquidGlyphMath.MORPH_PAUSE)
        for ((i, q) in listOf(l, r).withIndex()) {
            val area = quadArea(q)
            assertTrue("шашечка $i схлопнулась в щепку", area > 0.1f * 0.16f)
            // Ровно прямоугольник: стороны параллельны осям.
            assertEquals(q[0], q[6], 1e-6f)
            assertEquals(q[2], q[4], 1e-6f)
            assertEquals(q[1], q[3], 1e-6f)
            assertEquals(q[5], q[7], 1e-6f)
            // Ни одной совпавшей вершины — вырожденный угол play сюда не
            // доезжает, он раскрывается.
            for (v in 0 until 4) {
                val n = (v + 1) % 4
                assertTrue(
                    "шашечка $i: вершины $v и $n совпали",
                    LiquidGlyphMath.dist(q, v, n) > 0.05f,
                )
            }
        }
        // Одинаковые, зеркальные и разнесённые: две палочки, а не одна с наростом.
        assertEquals(quadArea(l), quadArea(r), 1e-6f)
        assertTrue("шашечки паузы слиплись", r[0] - l[2] > 0.2f)
        // Радиус паузы — ровно половина ширины шашечки: капсула, а не
        // прямоугольник со срезанными углами и не мыло вместо палочки.
        val w = l[2] - l[0]
        assertEquals(w / 2f, LiquidGlyphMath.morphCornerRadius(LiquidGlyphMath.MORPH_PAUSE), 1e-6f)
        // Пружина перелетает через 1 — и даже там конец остаётся паузой,
        // а не «почти паузой с хвостом от треугольника».
        val (ol, or) = LiquidGlyphMath.playPauseQuads(1.35f)
        assertContentEqualsFlat(ol, l)
        assertContentEqualsFlat(or, r)
        // И в последних процентах морфа фигуры не тоньше семи десятых баров:
        // глаз не ловит мгновение, где палочка ещё щепка.
        var m = 0.9f
        while (m < 1f) {
            val (al, ar) = LiquidGlyphMath.playPauseQuads(m)
            assertTrue("на подлёте к паузе осталась щепка при m=$m", quadArea(al) > 0.7f * quadArea(l))
            assertTrue("на подлёте к паузе осталась щепка при m=$m", quadArea(ar) > 0.7f * quadArea(r))
            m += 0.02f
        }
    }

    @Test
    fun `transport row is symmetric by ink edges, not by slot frames`() {
        // Вердикт 23.09.2026 с эмулятора: большой треугольник «плея» сидел
        // слишком близко к «вперёд». Ровные `spacedBy` между рамками слотов
        // ничего не обещают, пока рисунок не отцентрирован по своему габариту:
        // play 0.22..0.88 своим остриём лез в правую щель, а двойной треугольник
        // из той же щели вылезал.
        for (m in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val g = LiquidTransportLayout.inkGaps(m)
            assertEquals("зазоры обязаны быть равны при m=$m", g[0], g[1], 0.01f)
            assertTrue("глифы въезжают друг в друга при m=$m", g[0] > 0f)
        }
        // На «плее» по-прежнему есть что центрировать: без сдвига щели снова
        // разъедутся — тест проверяет не подогнанные числа, а работу сдвига.
        val play = LiquidGlyphMath.playPauseInk(LiquidGlyphMath.MORPH_PLAY)
        assertNotEquals(0f, LiquidGlyphMath.inkCentering(play), 1e-3f)
        assertNotEquals(0f, LiquidGlyphMath.inkCentering(LiquidGlyphMath.skipInk(false)), 1e-3f)
        // Рамки симметричны сами по себе, и на паузе симметричен свет под ними.
        val c = LiquidTransportLayout.slotCenters()
        assertEquals(c[1] - c[0], c[2] - c[1], 1e-6f)
        val b = LiquidTransportLayout.blobCenters(LiquidGlyphMath.MORPH_PAUSE)
        assertEquals(b[1] - b[0], b[2] - b[1], 1e-3f)
    }

    @Test
    fun `transport slots fit the touch zone and the screen`() {
        for (s in LiquidTransportLayout.slots()) {
            // Блоб внутри зоны нажатия: ребёнок больше родителя в Box уезжает
            // в отрицательный offset, и центрирование съезжает.
            assertTrue("блоб ${s.blob}dp вылез за зону нажатия ${s.hit}dp", s.blob <= s.hit)
            assertTrue("глиф ${s.glyph}dp не должен перекрывать блоб ${s.blob}dp", s.glyph < s.blob)
        }
        val slots = LiquidTransportLayout.slots()
        assertEquals(slots[0].hit, slots[2].hit, 1e-6f)
        // Строка обязана влезать в узкий телефон: 360dp минус поля экрана.
        assertTrue(
            "строка транспорта ${LiquidTransportLayout.rowWidth()}dp не влезает в 360dp",
            LiquidTransportLayout.rowWidth() <= 360f - 2 * 28f,
        )
    }

    @Test
    fun `scrubber has a thin rest height and a finger-sized bloom`() {
        // Покой — 5–6dp: «нитка», а не полосище. Касание — ~12dp: отклик
        // виден, но ползунка-пузыря нет. Разница обязана быть двукратной —
        // иначе «рост под пальцем» не читается.
        assertTrue(LiquidScrubMath.IDLE_HEIGHT_DP in 5f..6f)
        assertTrue(LiquidScrubMath.TOUCH_HEIGHT_DP in 10f..14f)
        assertTrue(LiquidScrubMath.heightTarget(true) / LiquidScrubMath.heightTarget(false) >= 2f)
    }

    @Test
    fun `scrubber math survives zero width and off-track fingers`() {
        assertEquals(0f, LiquidScrubMath.fraction(12f, 0f), 1e-6f)
        assertEquals(0f, LiquidScrubMath.fraction(-40f, 300f), 1e-6f)
        assertEquals(1f, LiquidScrubMath.fraction(9_999f, 300f), 1e-6f)
        assertEquals(0.5f, LiquidScrubMath.fraction(150f, 300f), 1e-6f)
    }

    @Test
    fun `time labels read as elapsed and negative remainder`() {
        assertEquals("0:00", LiquidScrubMath.clock(0))
        assertEquals("1:01", LiquidScrubMath.clock(61_000))
        assertEquals("1:01:01", LiquidScrubMath.clock(3_661_000))
        // Остаток — с минусом, и никогда не уходит за ноль, даже если
        // позиция почему-то дальше конца трека.
        assertEquals("−2:30", LiquidScrubMath.remaining(1_500_000, 1_650_000))
        assertEquals("−0:00", LiquidScrubMath.remaining(1_650_000, 1_500_000))
    }

    private fun assertContentEqualsFlat(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-6f)
    }
}
