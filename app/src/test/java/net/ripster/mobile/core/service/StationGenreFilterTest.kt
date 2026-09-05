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

    /** Жанр в модели — так его отдают Qobuz, Apple, SoundCloud, Яндекс. */
    private fun t(title: String, genre: String? = null, service: Service = Service.SOUNDCLOUD) =
        Track(id = title, title = title, artist = "A", service = service, genre = genre)

    /** Жанр, положенный в raw — старый путь, он тоже должен читаться. */
    private fun rawT(title: String, genre: String) = Track(
        id = title, title = title, artist = "A", service = Service.QOBUZ,
        raw = mapOf("genre" to genre),
    )

    /** Тот же отбор, что делает StationBuilder на последнем шаге. */
    private fun filter(found: List<Track>, query: String): List<Track> {
        fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        val words = query.lowercase().split(' ', '-', '/').filter { it.length >= 3 }
        fun declared(x: Track): String? =
            (x.genre ?: x.raw["genre"])?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }
        fun on(x: Track): Boolean {
            val g = norm(declared(x) ?: return false)
            if (g.isEmpty()) return false
            val q = norm(query)
            if (q.isNotEmpty() && (g.contains(q) || q.contains(g))) return true
            return words.any { w -> norm(w).length >= 4 && g.contains(norm(w)) }
        }
        val hits = found.filter { on(it) }
        return when {
            hits.size >= 5 -> hits
            found.none { declared(it) != null } -> found
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
    @Test
    fun theGenreIsReadFromAnyServiceTheSameWay() {
        """Владелец: «должен смотреть жанры в ЛЮБОМ потоковом сервисе». Одно
        поле модели на всех; старый путь через raw тоже понимается."""
        val found = listOf(
            t("a", "Techno", Service.QOBUZ),
            t("b", "Techno", Service.YANDEX),
            t("c", "Techno", Service.SOUNDCLOUD),
            t("d", "Techno", Service.APPLE),
            rawT("e", "Techno"),
            t("z", "Pop", Service.TIDAL),
        )
        val out = filter(found, "techno")
        assertEquals(5, out.size)
        assertTrue(out.none { it.title == "z" })
    }

    @Test
    fun aServiceWithoutGenresDoesNotSpoilTheStation() {
        """Deezer в поиске жанр не отдаёт: его треки — «неизвестно», а не
        «чужое». Пока хватает своих, станция собирается."""
        val found = (1..5).map { t("ok$it", "Ambient") } + (1..3).map { t("dz$it", null, Service.DEEZER) }
        val out = filter(found, "ambient")
        assertEquals(5, out.size)
    }


    @Test
    fun spellingOfTheGenreDoesNotMatter() {
        """SoundCloud пишет «Synth Wave» через пробел, станция — «synthwave».
        Из-за буквального сравнения плитка отказывалась собираться, хотя нужные
        треки были."""
        val found = (1..5).map { t("t$it", "Synth Wave") }
        assertEquals(5, filter(found, "synthwave").size)
        assertEquals(5, filter((1..5).map { t("x$it", "Lo-Fi") }, "lofi hip hop").size)
        assertEquals(5, filter((1..5).map { t("y$it", "Drum & Bass") }, "drum and bass").size)
    }

    @Test
    fun aParentGenreTagStillCountsForItsSubGenre() {
        """Трек, помеченный просто «Techno», станции «Dub Techno» подходит:
        точность даёт запрос, а жанр отсекает чужое."""
        assertEquals(5, filter((1..5).map { t("t$it", "Techno") }, "dub techno").size)
    }

    @Test
    fun popIsStillRejectedForEveryStation() {
        for (q in listOf("synthwave", "dub techno", "idm", "lofi hip hop")) {
            assertTrue(q, filter((1..8).map { t("p$it", "Pop") }, q).isEmpty())
        }
    }
}
