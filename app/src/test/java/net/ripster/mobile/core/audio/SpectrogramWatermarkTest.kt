package net.ripster.mobile.core.audio

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Водяной знак спектра не должен ложиться поверх подписей оси времени
 * (e2e 23.09.2026, docs/e2e_spectrum_after.png): на треке в ~55 секунд
 * «R I P S T E R» встал ровно на «50s» — обе метки прижались к правому
 * краю ряда, и читалась каша.
 *
 * [watermarkSlotX] — чистая геометрия ряда: позиция знака считается по
 * фактическим границам меток, а не «на глаз от правого края». Правило,
 * которое проверяется здесь и которое обязан держать экран: знак либо
 * вписан в свободный зазор (с полями), либо не рисуется в этом ряду вовсе
 * — рисующий уводит его строкой ниже.
 */
class SpectrogramWatermarkTest {

    private val left = 64f
    private val right = 886f
    private val wmW = 180f

    /** Пустой ряд — знак у правого края, но с полем, не впритык. */
    @Test
    fun emptyRowPutsMarkAtTheRightEdge() {
        val x = watermarkSlotX(emptyList(), wmW, left, right)!!
        assertTrue(x + wmW <= right)
        assertTrue(x + wmW >= right - 1f - 8f)
    }

    /**
     * Тот самый случай прогона: последняя метка прижата к правому краю,
     * зазора под знак справа нет — знак уезжает в зазор левее, а не в метку.
     */
    @Test
    fun markJumpsLeftOfAnOccupiedRightEdge() {
        val spans = listOf(
            70f to 100f,    // 0s
            230f to 260f,   // 10s
            390f to 420f,   // 20s
            500f to 540f,   // 30s
            800f to 886f,   // 40s … 50s: правый край ряда занят метками
        )
        val x = watermarkSlotX(spans, 120f, left, right)!!
        // Вписан в зазор 540..800 с полями и ни с кем не пересекается.
        assertTrue("знак [$x..${x + 120f}] залез на метку", x >= 540f + 8f && x + 120f <= 800f - 8f)
    }

    /** Меткам негде встать, знак влезает целиком — ряд пуст, право за знаком. */
    @Test
    fun singleNarrowLabelLeavesRoomOnTheRight() {
        val x = watermarkSlotX(listOf(70f to 100f), wmW, left, right)!!
        assertTrue(x + wmW <= right)
        assertTrue(x >= 100f + 8f)
    }

    /**
     * Короткий трек из скриншота: метки через каждые ~130px, знак 180px —
     * ни в один зазор не влезает. Ответ — `null`, то есть ряд ниже, а не
     * наложение.
     */
    @Test
    fun crowdedRowRejectsTheMarkEntirely() {
        val spans = (0..5).map { i ->
            (left + i * 150f + 4f) to (left + i * 150f + 40f)
        }
        assertNull(watermarkSlotX(spans, wmW, left, right))
    }

    /** Перепутанные концы отрезка и несортированный вход не ломают поиск. */
    @Test
    fun messySpansStillWork() {
        val spans = listOf(400f to 350f, 100f to 120f)
        val x = watermarkSlotX(spans, 100f, left, right)!!
        assertTrue(x + 100f <= right)
        assertTrue("пересёк 350..400", x >= 400f + 8f || x + 100f <= 350f - 8f)
    }

    /** Ряд уже самого знака: слота не бывает, и это не деление на ноль. */
    @Test
    fun rowNarrowerThanMarkReturnsNull() {
        assertNull(watermarkSlotX(emptyList(), 180f, 0f, 100f))
    }
}
