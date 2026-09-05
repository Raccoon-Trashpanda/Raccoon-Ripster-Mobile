package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Станция обязана играть свой жанр — или не играть вовсе.
 *
 * Две жалобы одного дня (05.09.2026): «синтвейв нажал… открыл ликвид фанк», а
 * плитка «IDM» на эмуляторе заиграла шведский поп-ремикс. Первое лечится тем,
 * что плитка больше не берёт чужую станцию ([WaveStationsTest]); второе — тем,
 * что текстовый поиск по названию жанра сам по себе станцией не является.
 *
 * Здесь проверяется решающее правило: держим то, чей ОБЪЯВЛЕННЫЙ сервисом жанр
 * совпадает, а когда такого мало — отдаём пусто, чтобы экран сказал правду
 * вместо случайной музыки.
 */
class StationGenreFilterTest {

    private fun t(title: String, genre: String? = null) = Track(
        id = title,
        title = title,
        artist = "A",
        service = Service.SOUNDCLOUD,
        raw = if (genre == null) emptyMap() else mapOf("genre" to genre),
    )

    /** Тот же отбор, что делает StationBuilder на последнем шаге. */
    private fun filter(found: List<Track>, query: String): List<Track> {
        val words = query.lowercase().split(' ', '-', '/').filter { it.length >= 3 }
        fun on(x: Track): Boolean {
            val g = x.raw["genre"]?.lowercase()?.trim().orEmpty()
            return g.isNotEmpty() && words.any { it in g }
        }
        val hits = found.filter { on(it) }
        return when {
            hits.size >= 5 -> hits
            found.none { it.raw["genre"] != null } -> found
            else -> emptyList()
        }
    }

    @Test
    fun offGenreNoiseDoesNotBecomeAStation() {
        """Ровно то, что случилось: на «idm» пришёл поп, и он заиграл."""
        val found = listOf(
            t("Snalla bli min igen (Remix)", "Pop"),
            t("Something else", "Dance & EDM"),
            t("Third", "House"),
        )
        assertTrue("чужой жанр играть нельзя", filter(found, "idm").isEmpty())
    }

    @Test
    fun onGenreTracksSurvive() {
        val found = (1..6).map { t("track $it", "Synthwave") } + t("alien", "Pop")
        val out = filter(found, "synthwave")
        assertEquals(6, out.size)
        assertTrue(out.none { it.title == "alien" })
    }

    @Test
    fun genreMatchIsSubstringAndCaseInsensitive() {
        val found = (1..5).map { t("t$it", "Deep House") }
        assertEquals(5, filter(found, "deep house").size)
    }

    @Test
    fun aServiceThatDeclaresNothingIsNotPunished() {
        """Ни один сервис не сказал жанр — судить не по чему. Это отсутствие
        сведений, а не подмена: отдаём найденное как есть."""
        val found = (1..4).map { t("t$it") }
        assertEquals(4, filter(found, "ambient").size)
    }

    @Test
    fun tooFewOnGenreIsEmptyNotAMixture() {
        """Три своих и семь чужих — это не станция. Лучше честное «не
        собралась», чем плейлист, на три четверти состоящий из чужого."""
        val found = (1..3).map { t("ok$it", "Techno") } + (1..7).map { t("no$it", "Pop") }
        assertTrue(filter(found, "techno").isEmpty())
    }

    @Test
    fun shortWordsDoNotMatchEverything() {
        """Слова короче трёх букв выкидываются: «dnb» ловится целиком, а «и»
        или «of» совпали бы с чем угодно."""
        val found = (1..5).map { t("t$it", "Drum & Bass") }
        assertTrue(filter(found, "drum and bass").isNotEmpty())
    }
}
