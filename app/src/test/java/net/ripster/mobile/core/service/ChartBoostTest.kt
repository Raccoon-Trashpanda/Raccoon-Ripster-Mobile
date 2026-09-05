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
        // Отсутствие в чарте — не признак плохой музыки, а отсутствие
        // свидетельства. Раньше это проверялось на `apply`, который правил
        // popularity; теперь правило живёт в StationRanker, а здесь остаётся
        // то, за что ChartBoost отвечает на самом деле: узнавание артиста.
        assertFalse(ChartBoost.isCharting("Unknown Artist", chart))
        assertFalse(ChartBoost.isCharting("Nobody", chart))
    }

    @Test
    fun anEmptyChartChangesNothing() {
        // Чарт не ответил — это «не знаю», а не «никого нет в чарте».
        assertFalse(ChartBoost.isCharting("Bicep", emptySet()))
    }

    @Test
    fun veryShortNamesAreNotMatched() {
        """Двухбуквенные куски совпали бы с чем угодно."""
        assertTrue(ChartBoost.namesOf("AB & Bicep").none { it == "ab" })
    }
}
