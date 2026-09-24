package net.ripster.mobile.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-8 (этап 8 E2E-кампании): ландшафт + открытый мини-плеер — выдачи поиска
 * не было вовсе, ни строк, ни «ничего не найдено», ни строки ошибки (и на
 * Deezer тоже, то есть дело не в сервисе, а в разметке).
 *
 * Стекло [net.ripster.mobile.ui.AppShell] отдаёт экрану поиска остаток после
 * шапки-бара, полосы загрузок, мини-плеера и нижней навигации. Внутри этого
 * остатка Column делит высоту между шапкой (немаранный ребёнок) и списком. До
 * фикса шапка измерялась сколько хочет и список получал maxHeight = 0;
 * `Modifier.weight(1f)` без потолка над шапкой дал бы то же самое — остаток
 * прижимается к нулю.
 *
 * [SearchLayout] — чистая арифметика этого дележа, поэтому проверяется без
 * телефона ровно то утверждение, которое нарушалось: «шапка уступает ровно
 * столько, чтобы строка выдачи осталась видимой».
 */
class SearchLayoutTest {

    /** Выдача, сколько её ни считай, не обязана быть меньше одной строки. */
    private fun resultsOf(contentDp: Float, errorShown: Boolean): Float {
        val pinned = SearchLayout.pinnedAboveResultsDp(errorShown)
        return contentDp - SearchLayout.headerMaxHeightDp(contentDp, pinned) - pinned
    }

    /**
     * Тот самый прогон: ландшафт с мини-плеером. На экране поиска остаётся
     * ~180dp, шапка же хотела ~244dp — до фикса список терялся целиком.
     */
    @Test
    fun landscapeWithMiniPlayerStillShowsAResultRow() {
        val content = SearchLayout.contentHeightDp(180f)
        assertEquals(148f, content, 0.01f)
        assertTrue(
            "в ландшафте с мини-плеером выдача получила ${resultsOf(content, false)}dp, нужно >= ${SearchLayout.ResultsMinDp}",
            resultsOf(content, errorShown = false) >= SearchLayout.ResultsMinDp,
        )
        // Шапка не исчезает: она сжимается, но свою квоту держит.
        assertTrue(SearchLayout.headerMaxHeightDp(content, SearchLayout.pinnedAboveResultsDp(false)) > 0f)
    }

    /** Строка ошибки закреплена НАД списком и высоты не уступает — её место вычитается из шапки. */
    @Test
    fun pinnedErrorLineComesOutOfTheHeaderNotOutOfResults() {
        val content = SearchLayout.contentHeightDp(180f)
        val withError = resultsOf(content, errorShown = true)
        assertEquals(SearchLayout.ResultsMinDp, withError, 0.01f)
        // Ошибка не «съедает» выдачу молча: платит шапка ровно на ErrorLineDp меньше.
        val shrink = SearchLayout.headerMaxHeightDp(content, SearchLayout.pinnedAboveResultsDp(false)) -
            SearchLayout.headerMaxHeightDp(content, SearchLayout.pinnedAboveResultsDp(true))
        assertEquals(SearchLayout.ErrorLineDp, shrink, 0.01f)
    }

    /**
     * Портрет — нетронутый: когда места вдоволь, потолок шапки задаёт ideal, а
     * не бюджет выдачи, и нижний отступ списка остаётся прежними 120dp.
     */
    @Test
    fun portraitBudgetIsUnchangedByTheFix() {
        val content = SearchLayout.contentHeightDp(560f)
        assertEquals(
            SearchLayout.HeaderIdealDp,
            SearchLayout.headerMaxHeightDp(content, SearchLayout.pinnedAboveResultsDp(false)),
            0.01f,
        )
        assertEquals(SearchLayout.ResultsBottomIdealDp, SearchLayout.resultsBottomPaddingDp(content), 0.01f)
        // В портрете есть и полная шапка, и запас под плашку исхода.
        assertTrue(resultsOf(content, errorShown = true) > SearchLayout.ResultsMinDp)
    }

    /**
     * Второй вор той же находки: портретный `contentPadding(bottom = 120.dp)`
     * в ландшафте был БОЛЬШЕ всего списка — прокрутка упиралась в пустоту там,
     * где должны быть строки ( swipe по полосе между чипами и мини-плеером не
     * выводил ни строки).
     */
    @Test
    fun bottomPaddingNeverSwallowsTheList() {
        val content = SearchLayout.contentHeightDp(180f)
        val pad = SearchLayout.resultsBottomPaddingDp(content)
        val results = resultsOf(content, errorShown = true)
        assertTrue("отступ ${pad}dp против списка ${results}dp", pad < results)
        // Никогда не больше портретного запаса и никогда не отрицательно.
        assertTrue(SearchLayout.resultsBottomPaddingDp(2000f) <= SearchLayout.ResultsBottomIdealDp)
        assertEquals(0f, SearchLayout.resultsBottomPaddingDp(-5f), 0.01f)
    }

    /** Экран короче минимума выдачи: шапка сжимается в ноль, а не уходит в минус. */
    @Test
    fun impossiblyShortScreenCollapsesHeaderButStaysPositive() {
        val content = SearchLayout.contentHeightDp(60f)
        assertEquals(28f, content, 0.01f)
        assertEquals(0f, SearchLayout.headerMaxHeightDp(content, SearchLayout.pinnedAboveResultsDp(false)), 0.01f)
        assertTrue(resultsOf(content, errorShown = false) > 0f)
        // Отрицательной высоты не бывает и на нулевом окне.
        assertEquals(0f, SearchLayout.contentHeightDp(0f), 0.01f)
        assertEquals(0f, SearchLayout.headerMaxHeightDp(0f), 0.01f)
    }
}
