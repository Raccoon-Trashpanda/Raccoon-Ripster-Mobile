package net.ripster.mobile.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Какой хром оболочки показывается в каком классе окна (продолжение BUG-8,
 * e2e 23.09.2026): в ландшафте на 2670×1200 нижняя навигация, полоса «1 готово»
 * и двухстрочный мини-плеер оставляли выдаче поиска одну обрезанную строку.
 *
 * [ChromeBudget] — чистая арифметика этого выбора, поэтому и проверяется без
 * эмулятора ровно то, на чём споткнулся прогон: «в горизонтальном окне нет
 * ни нижней навигации, ни статусной полосы, ни двухстрочного плеера», «в
 * вертикальном — всё на месте», и «экономия ландшафта не съедает бюджет поля
 * и строки выдачи» вместе с [SearchLayout].
 */
class ChromeBudgetTest {

    /** То самое окно прогона: 2670×1200 px при плотности ≈2.625 → ~1017×457 dp. */
    private val landscape = 1017f to 457f

    /** Обычный портрет: 411×892 dp (Pixel-класс). */
    private val portrait = 411f to 892f

    @Test
    fun landscapeWindowTradesBottomChromeForRail() {
        val (w, h) = landscape
        assertTrue("в ландшафте навигация обязана быть на рельсе", ChromeBudget.useRail(w, h))
        assertFalse(ChromeBudget.showsBottomNav(w, h))
        assertFalse("полоса «готово» в ландшафте складывается в шапку", ChromeBudget.showsStatusStrip(w, h))
        assertTrue(ChromeBudget.compactMiniPlayer(w, h))
    }

    @Test
    fun portraitWindowKeepsEveryStripItHasAlwaysHad() {
        val (w, h) = portrait
        assertFalse(ChromeBudget.useRail(w, h))
        assertTrue(ChromeBudget.showsBottomNav(w, h))
        assertTrue(ChromeBudget.showsStatusStrip(w, h))
        assertFalse(ChromeBudget.compactMiniPlayer(w, h))
        // Портретный хром — ровно те же величины, что до ландшафтной ветки.
        assertEquals(
            ChromeBudget.StatusStripHeightDp + ChromeBudget.MiniPlayerHeightDp + ChromeBudget.BottomNavHeightDp,
            ChromeBudget.chromeHeightDp(w, h),
            0.01f,
        )
    }

    /**
     * Квадрат — ещё портрет: перевес должен быть НАСТОЯЩИМ, иначе на узком
     * окне складывания хрома начались бы сами собой.
     */
    @Test
    fun squareIsStillPortrait() {
        assertFalse(ChromeBudget.useRail(400f, 400f))
        assertTrue(ChromeBudget.useRail(401f, 400f))
    }

    /** Ноль и отрицательные окна не рождают ни рельса, ни отрицательной высоты. */
    @Test
    fun degenerateWindowsStaySane() {
        assertFalse(ChromeBudget.useRail(0f, 0f))
        assertFalse(ChromeBudget.useRail(-10f, -20f))
        assertTrue(ChromeBudget.chromeHeightDp(0f, 0f) > 0f)
    }

    /**
     * Главный смысл перестройки: в ландшафте хром (кроме шапки и системных
     * панелей) обязан занимать заметно МЕНЬШЕ, чем в портрете, — иначе рельс
     * ничего и не экономил.
     */
    @Test
    fun landscapeChromeIsMateriallyCheaper() {
        val saved = ChromeBudget.chromeHeightDp(portrait.first, portrait.second) -
            ChromeBudget.chromeHeightDp(landscape.first, landscape.second)
        assertTrue("экономия всего ${saved}dp", saved >= 100f)
        // Компактный плеер корочки портретного ровно на строку с отступами.
        assertTrue(ChromeBudget.CompactMiniPlayerHeightDp < ChromeBudget.MiniPlayerHeightDp)
        // Рельс живёт по ширине, а не по высоте: свою долю окна он не крадёт.
        assertTrue(ChromeBudget.RailWidthDp in 72f..96f)
    }

    /**
     * Связка с бюджетом поиска: окно прогона минус ландшафтный хром — и всё
     * равно остаётся поле в 56dp и одна полная строка выдачи. Это и есть то
     * утверждение, которое нарушение BUG-8 ломало в обе стороны.
     */
    @Test
    fun landscapeBudgetStillFeedsFieldAndOneResultRow() {
        // 457dp окна − системные панели (~24) − шапка (~40) − хром из бюджета.
        val screenDp = landscape.second - 24f - 40f -
            ChromeBudget.chromeHeightDp(landscape.first, landscape.second)
        val content = SearchLayout.contentHeightDp(screenDp)
        val pinned = SearchLayout.pinnedAboveResultsDp(errorShown = true)
        val results = content - SearchLayout.headerMaxHeightDp(content, pinned) - pinned
        assertTrue("поле не влезает: ${SearchLayout.FieldMinDp}dp при контенте ${content}dp",
            content >= SearchLayout.FieldMinDp)
        assertTrue("выдаче не осталось строки: ${results}dp", results >= SearchLayout.ResultsMinDp)
    }
}
