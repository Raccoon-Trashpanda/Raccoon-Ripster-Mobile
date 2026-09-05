package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кто в жанре на слуху — по чарту Apple.
 *
 * Владелец: «изучи ещё ротации из Эпла, тоже там качественно подборка
 * создаётся». Счётчик каждого сервиса меряет только себя; чарт жанра даёт
 * общий по индустрии ответ, кто сейчас звучит. Сопоставляем ИМЕНАМИ — треки
 * Apple играются только по подписке и через ПК, и подмешивать их в станцию
 * значило бы собрать список, половина которого на телефоне молчит.
 */
class ChartBoostTest {

    private fun t(artist: String, pop: Double? = null) = Track(
        id = artist, title = "song", artist = artist, service = Service.DEEZER, popularity = pop,
    )

    private val chart = setOf("fredagain", "bicep", "royksopp")

    @Test
    fun aChartingArtistIsLifted() {
        val out = ChartBoost.apply(listOf(t("Bicep", 0.1)), chart)
        assertEquals(ChartBoost.CHART_WEIGHT, out.single().popularity!!, 1e-9)
    }

    @Test
    fun anAlreadyStrongerTrackIsNotPulledDown() {
        """Чарт поднимает, но не понижает: у трека может быть свой счётчик выше."""
        val out = ChartBoost.apply(listOf(t("Bicep", 0.97)), chart)
        assertEquals(0.97, out.single().popularity!!, 1e-9)
    }

    @Test
    fun aCollaborationIsRecognised() {
        """В чарте «Fred again..», у трека «Fred again.. & Baby Keem» — как одна
        строка они не совпадут никогда."""
        assertTrue(ChartBoost.isCharting("Fred again.. & Baby Keem", chart))
        assertTrue(ChartBoost.isCharting("Bicep feat. Clara La San", chart))
        assertTrue(ChartBoost.isCharting("Someone, Röyksopp", chart))
    }

    @Test
    fun spellingAndDiacriticsDoNotBreakTheMatch() {
        assertTrue(ChartBoost.isCharting("Röyksopp", chart))
        assertTrue(ChartBoost.isCharting("ROYKSOPP", chart))
    }

    @Test
    fun someoneNotInTheChartLosesNothing() {
        """Отсутствия в чарте — не признак плохой музыки, а отсутствие
        свидетельства: у вещи остаётся её собственный вес, в том числе `null`."""
        val out = ChartBoost.apply(listOf(t("Unknown Artist", 0.3), t("Nobody", null)), chart)
        assertEquals(0.3, out[0].popularity!!, 1e-9)
        assertEquals(null, out[1].popularity)
    }

    @Test
    fun anEmptyChartChangesNothing() {
        """Чарт не ответил — подбор обязан остаться прежним, а не обнулиться."""
        val src = listOf(t("Bicep", 0.4), t("Nobody", null))
        val out = ChartBoost.apply(src, emptySet())
        assertEquals(src, out)
        assertFalse(ChartBoost.isCharting("Bicep", emptySet()))
    }

    @Test
    fun veryShortNamesAreNotMatched() {
        """Двухбуквенные куски совпали бы с чем угодно."""
        assertTrue(ChartBoost.namesOf("AB & Bicep").none { it == "ab" })
    }
}
