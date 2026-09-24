package net.ripster.mobile.ui.screens

import kotlin.math.hypot

/**
 * Геометрия транспорта «Флюида» — чистая, без единого андроид-типа.
 *
 * Всё, что рисует крупные мягкие глифы стиля, живёт здесь в нормализованных
 * координатах юнит-полотна (0..1) и поэтому проверяется тестом, а не «на
 * глазок на эмуляторе»: морф play↔pause, форма двойных треугольников перемотки
 * и цели пружины блоба. `Path` из этих точек уже рисует экран.
 *
 * Вердикт владельца 23.09.2026, из-за которого заведён стиль: диски с ободком
 * вокруг каждого глифа выглядят дёшево; нужны «большие, мягкие, очень высокого
 * качества» кнопки. Мягкость здесь — не картинка, а геометрия: огранённые
 * углы путей и разлёт двух четверох из треугольника в шашечки паузы.
 */
object LiquidGlyphMath {

    /** Доля морфа: 0 — play, 1 — пауза. */
    const val MORPH_PLAY = 0f
    const val MORPH_PAUSE = 1f

    /** Куда обязан прийти морф для текущего состояния воспроизведения. */
    fun morphTarget(isPlaying: Boolean): Float = if (isPlaying) MORPH_PAUSE else MORPH_PLAY

    // Пауза — две капсулы; play — тот же холст, разрезанный по вертикали на
    // левую трапецию и правую «стрелку» (верхняя и нижняя её точки совпадают
    // в вершине треугольника). Морф — линейный по этим четырём вершинам:
    // degenerate-угол при m=1 раскрывается в нормальный, без скачков.
    private val PLAY_LEFT = floatArrayOf(
        0.22f, 0.12f, 0.55f, 0.31f, 0.55f, 0.69f, 0.22f, 0.88f,
    )
    private val PLAY_RIGHT = floatArrayOf(
        0.55f, 0.31f, 0.88f, 0.50f, 0.88f, 0.50f, 0.55f, 0.69f,
    )
    private val PAUSE_LEFT = floatArrayOf(
        0.20f, 0.16f, 0.36f, 0.16f, 0.36f, 0.84f, 0.20f, 0.84f,
    )
    private val PAUSE_RIGHT = floatArrayOf(
        0.64f, 0.16f, 0.80f, 0.16f, 0.80f, 0.84f, 0.64f, 0.84f,
    )

    /**
     * Два многоугольника глифа play↔pause при доле морфа [m]
     * (по 4 вершины, плоский массив x,y). На краях интервала — ровно
     * треугольник (разрезанный) и ровно две шашечки паузы.
     */
    fun playPauseQuads(m: Float): Pair<FloatArray, FloatArray> {
        val t = m.coerceIn(MORPH_PLAY, MORPH_PAUSE)
        return lerpPoints(PLAY_LEFT, PAUSE_LEFT, t) to lerpPoints(PLAY_RIGHT, PAUSE_RIGHT, t)
    }

    /**
     * Насколько округлять каждую из 4 вершин каждого куска при доле морфа [m]
     * (1 — полный радиус [morphCornerRadius], 0 — острый угол).
     *
     * Вершины шва (правый край левого куска и левый край правого) при `m=0`
     * обязаны оставаться ОСТРЫМИ: обе половины стыкуются ровно по вертикали
     * x=0.55, и если срезать по углу с каждой стороны шва, по шву возникает
     * вырезанная выемка — на эмуляторе (23.09.2026) это и выглядело как «два
     * пересекающихся треугольника вместо одного»: правый клин отходил от
     * большого влево и торчал острым носом. При `m=1` шов расходится на
     * настоящие внутренние углы двух капсул, и там скругление нужно целиком.
     * Между краями вес шва растёт вместе с морфом — переход остаётся плавным.
     *
     * Порядок вершин — тот же, что у [playPauseQuads]: левый кусок
     * `[верх-слева, шв-верх, шв-низ, низ-слева]`, правый
     * `[шв-верх, вершина-верх, вершина-низ, шв-низ]`.
     */
    fun playPauseCornerWeights(m: Float): Pair<FloatArray, FloatArray> {
        val t = m.coerceIn(MORPH_PLAY, MORPH_PAUSE)
        return floatArrayOf(1f, t, t, 1f) to floatArrayOf(t, 1f, 1f, t)
    }

    /**
     * Радиус скругления вершин того же глифа (доля юнит-полотна): капсульная
     * шашечка паузы мягче, чем угол треугольника, — и радиус растёт вместе
     * с морфом, поэтому «мягчение» видно в самом переходе, а не только в
     * покое.
     */
    fun morphCornerRadius(m: Float): Float {
        val t = m.coerceIn(MORPH_PLAY, MORPH_PAUSE)
        return 0.055f + (0.08f - 0.055f) * t
    }

    /**
     * Цели стеклянного блоба под глифом: в покое его нет (нулевая альфа и
     * малый масштаб), при нажатии он расцветает — вырастает и проступает.
     * Доводчик (пружина или мгновенная смена при выключенных анимациях)
     * выбирает экран; здесь только сами цели.
     */
    fun blobScaleTarget(pressed: Boolean): Float = if (pressed) 1f else 0.55f
    fun blobAlphaTarget(pressed: Boolean): Float = if (pressed) 0.18f else 0f

    /**
     * Монтируется ли блоб вообще при такой альфе.
     *
     * Нельзя положиться на один только `Modifier.alpha(0f)`: блоб — стеклянный
     * орган, а орган несёт ещё и тень от возвышения. Тень рисуется не краской
     * содержимого, а свойством слоя (`RenderNode` держит для неё отдельную
     * `shadowAlpha`, независимую от `alpha`), поэтому альфа её не гасит: в
     * предыдущей сборке в покое под каждым глифом стоял серый полупрозрачный
     * диск ровно там, где блобу быть нельзя. Значит при нулевой альфе блоб не
     * существует в дереве — и отбрасывать тень уже нечему.
     */
    fun blobMounted(alpha: Float): Boolean = alpha > 0f

    /**
     * На сколько сдвинуть рисунок (в долях полотна), чтобы его ГАБАРИТ встал
     * по центру слота.
     *
     * Нужна ровно эта величина, а не центрирование по центроиду: только
     * габарит отвечает на вопрос «сколько воздуха до соседей». Треугольник
     * play 0.22..0.88 в полотне 0..1 своими краями на 5% ближе к правой
     * границе, чем к левой, — и ровно на столько же его остриё въезжало в
     * зазор к «вперёд», пока соседний двойной треугольник из такого же зазора
     * вылезал. Симметрия строки считается по краям рисунков — в
     * [LiquidTransportLayout.inkGaps] — и держится она именно на этом сдвиге.
     */
    fun inkCentering(ink: LiquidInk): Float = 0.5f - (ink.left + ink.right) / 2f

    /**
     * Оптический центр блоба — доля глифа ОТ центра слота до центроида
     * нарисованного рисунка, при том что сам рисунок центрирован по габариту
     * ([inkCentering]). Ставить свет в центр рамки — значит светить мимо:
     * у треугольника центр тяжести не там, где у него середина габарита
     * (и тем более не там, где вершина).
     */
    fun blobShift(ink: LiquidInk): Float = ink.cx + inkCentering(ink) - 0.5f

    /** Габарит и оптический центр ink-а одного глифа, доли юнит-полотна. */
    data class LiquidInk(val left: Float, val right: Float, val cx: Float, val cy: Float)

    /** Ink глифа play↔pause при доле морфа [m]. */
    fun playPauseInk(m: Float): LiquidInk {
        val (l, r) = playPauseQuads(m)
        return inkOf(listOf(l, r))
    }

    /** Ink перехода (двойной треугольник); [back] — зеркало. */
    fun skipInk(back: Boolean): LiquidInk = inkOf(skipTriangles(back))

    private fun inkOf(parts: List<FloatArray>): LiquidInk {
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var cx = 0f
        var cy = 0f
        var mass = 0f
        for (p in parts) {
            val k = centroid(p)
            for (i in p.indices step 2) {
                if (p[i] < left) left = p[i]
                if (p[i] > right) right = p[i]
            }
            cx += k[0] * k[2]; cy += k[1] * k[2]; mass += k[2]
        }
        // Вес — площадь: два куска складываются в один рисунок именно так,
        // как их видит глаз (маленький клин не тянет центр на себя сильнее
        // большой трапеции).
        return if (mass == 0f) LiquidInk(left, right, 0.5f, 0.5f)
        else LiquidInk(left, right, cx / mass, cy / mass)
    }

    /** Центроид многоугольника + его площадь (знак отбрасываем). */
    private fun centroid(p: FloatArray): FloatArray {
        val n = p.size / 2
        var a = 0f
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val cross = p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
            a += cross
            cx += (p[i * 2] + p[j * 2]) * cross
            cy += (p[i * 2 + 1] + p[j * 2 + 1]) * cross
        }
        a /= 2f
        if (a == 0f) {
            // Вырожденный кусок (игла) центроидом не обладает — берём середину
            // вершин и нулевой вес: на сумму он всё равно не повлияет.
            var sx = 0f
            var sy = 0f
            for (i in 0 until n) {
                sx += p[i * 2]; sy += p[i * 2 + 1]
            }
            return floatArrayOf(sx / n, sy / n, 0f)
        }
        return floatArrayOf(cx / (6f * a), cy / (6f * a), kotlin.math.abs(a))
    }

    /**
     * Переход: ДВА скруглённых треугольника (наша векторная геометрия, не
     * иконка из Material). [back] — зеркало по x. Каждый треугольник —
     * 3 вершины, плоский массив.
     */
    fun skipTriangles(back: Boolean): List<FloatArray> {
        val t1 = floatArrayOf(0.03f, 0.18f, 0.42f, 0.50f, 0.03f, 0.82f)
        val t2 = floatArrayOf(0.50f, 0.18f, 0.89f, 0.50f, 0.50f, 0.82f)
        if (!back) return listOf(t1, t2)
        return listOf(t1, t2).map { tri ->
            FloatArray(tri.size) { i -> if (i % 2 == 0) 1f - tri[i] else tri[i] }
        }
    }

    /** Площадь треугольника нормализованного холста — тесту проверять вырожденность. */
    fun triangleArea(tri: FloatArray): Float {
        val (ax, ay) = tri[0] to tri[1]
        val (bx, by) = tri[2] to tri[3]
        val (cx, cy) = tri[4] to tri[5]
        return kotlin.math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2f
    }

    /** Расстояние между двумя вершинами юнит-полотна — тесту для скруглений. */
    fun dist(pts: FloatArray, i: Int, j: Int): Float =
        hypot(pts[i * 2] - pts[j * 2], pts[i * 2 + 1] - pts[j * 2 + 1])

    private fun lerpPoints(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }
}

/**
 * Строка транспорта «Флюида» в dp — тоже начисто и тоже отсюда экран берёт
 * размеры, иначе тест проверял бы таблицу, которой никто не рисует.
 *
 * Симметрия строки считается НЕ по рамкам слотов, а по краям нарисованных
 * глифов ([inkGaps]): равные `spacedBy` между рамками дают равные щели только
 * тогда, когда рисунок каждого глифа стоит в своей рамке по центру габарита
 * ([LiquidGlyphMath.inkCentering]). Без этого остриё большого треугольника
 * лезло к «вперёд», а у двойного треугольника из той же щели вылезало — на
 * эмуляторе (23.09.2026) это и выглядело как «плей прижался к-next».
 */
object LiquidTransportLayout {

    /** Один слот строки: зона пальца, монета блоба, сам рисунок — всё в dp. */
    class Slot(val hit: Float, val blob: Float, val glyph: Float)

    /** Щель между рамками соседних слотов, dp. */
    const val GAP_DP = 26f

    val SKIP = Slot(hit = 64f, blob = 60f, glyph = 42f)
    val PLAY = Slot(hit = 96f, blob = 88f, glyph = 68f)

    /** Порядок в строке: «назад», «плей/пауза», «вперёд». */
    fun slots(): List<Slot> = listOf(SKIP, PLAY, SKIP)

    fun rowWidth(): Float {
        val s = slots()
        return s.fold(0f) { acc, slot -> acc + slot.hit } + GAP_DP * (s.size - 1)
    }

    /** Центры рамок слотов, dp от начала строки (строка центрирована). */
    fun slotCenters(): FloatArray {
        val s = slots()
        return FloatArray(s.size) { i ->
            s.take(i).fold(0f) { acc, slot -> acc + slot.hit + GAP_DP } + s[i].hit / 2f
        }
    }

    /** Ink i-го слота при доле морфа [m]. */
    private fun ink(i: Int, m: Float): LiquidGlyphMath.LiquidInk = when (i) {
        0 -> LiquidGlyphMath.skipInk(back = true)
        1 -> LiquidGlyphMath.playPauseInk(m)
        else -> LiquidGlyphMath.skipInk(back = false)
    }

    /**
     * Края нарисованных глифов после оптического центрирования:
     * [left, right] каждого слота, dp от начала строки.
     */
    fun inkEdges(m: Float): FloatArray {
        val c = slotCenters()
        val s = slots()
        return FloatArray(c.size * 2).also { e ->
            for (i in c.indices) {
                val k = ink(i, m)
                // центрирование по габариту = габарит ровно посередине слота
                val half = (k.right - k.left) / 2f * s[i].glyph
                e[i * 2] = c[i] - half
                e[i * 2 + 1] = c[i] + half
            }
        }
    }

    /** Воздух между рисунками: (назад→плей, плей→вперёд), dp. */
    fun inkGaps(m: Float): FloatArray {
        val e = inkEdges(m)
        return floatArrayOf(e[2] - e[1], e[4] - e[3])
    }

    /** Куда ставится центр блоба i-го слота, dp от начала строки. */
    fun blobCenters(m: Float): FloatArray {
        val c = slotCenters()
        val s = slots()
        return FloatArray(c.size) { i ->
            c[i] + LiquidGlyphMath.blobShift(ink(i, m)) * s[i].glyph
        }
    }
}

/**
 * Числа сик-бара «Флюида» — тоже начисто. Полоса без ползунка: тонкая
 * капсула (5–6dp), которая на касании раздувается до ~12dp пружиной и
 * сдувается обратно; подписи по концам — прошедшее время и ОСТАТОК со
 * знаком минус, как в эталоне, а не два «прогресса».
 */
object LiquidScrubMath {

    /** Толщина полосы в покое, dp: тоньше — не попадёшь, толще — не капсула. */
    const val IDLE_HEIGHT_DP = 5.5f

    /** Толщина под пальцем, dp: заметный «отклик полоски», но не ползунок. */
    const val TOUCH_HEIGHT_DP = 12f

    /** Куда анимируется толщина. */
    fun heightTarget(active: Boolean): Float = if (active) TOUCH_HEIGHT_DP else IDLE_HEIGHT_DP

    /** Доля перемотки по x-координате пальца; ноль ширины не делим. */
    fun fraction(x: Float, width: Float): Float =
        if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    /** Метка позиции: m:ss (h:mm:ss для длинных). */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Остаток «−m:ss»: сколько ЕЩЁ играть. Хвост за пределами трека — ноль, не минус. */
    fun remaining(positionMs: Long, durationMs: Long): String =
        "−" + clock(durationMs - positionMs)
}
