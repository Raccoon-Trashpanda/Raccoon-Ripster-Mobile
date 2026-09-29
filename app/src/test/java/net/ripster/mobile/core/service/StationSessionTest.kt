package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сессионный слой станции: жёсткие оконные квоты, свежесть по полугодичному
 * полупериоду и разнос по последним сыгранным.
 *
 * Кейсы 2, 3, 6–11, 15, 17, 18 задачи 2 из `ops/qwen/gpt-sol/002-answer.md`, с
 * поправкой Claude: полупериод свежести 180 дней (не 45) и пол 0,15.
 *
 * Проверяются свойства, а не «красиво ли вышло»: квоту нельзя обойти сильным
 * весом, «не знаю» не имеет права превращаться в «свежее всех» или в «похоже
 * наверняка», а отсутствие альбома не должно сливать разные вещи в одну квоту.
 */
class StationSessionTest {

    private val MIN = 60 * 1000L
    private val NOW = 1_000_000_000_000L

    private fun tr(
        id: String,
        artist: String = "Артист",
        album: String? = null,
        label: String? = null,
        genre: String? = null,
        year: Int? = null,
        pop: Double? = null,
    ) = Track(
        id = id, title = id, artist = artist, service = Service.DEEZER,
        albumTitle = album, label = label, genre = genre, year = year, popularity = pop,
    )

    private fun play(at: Long, artist: String = "Артист", album: String? = null) =
        StationRanker.Played(artist = artist, at = at, album = album)

    private fun session(vararg offsetsMin: Long, artist: String = "Артист", album: String? = null) =
        StationRanker.Session(offsetsMin.map { play(NOW - it * MIN, artist, album) })

    // ── свежесть: кейсы 2 и 3 ───────────────────────────────────────────────

    @Test
    fun freshnessHalvesAfterOneHalfLife() {
        assertEquals(1.0, StationRanker.freshness(0.0), 1e-9)
        assertEquals(0.5, StationRanker.freshness(StationRanker.FRESH_HALF_LIFE_DAYS), 1e-9)
    }

    @Test
    fun unknownDateIsTheMiddleOfTheScale() {
        """Кейс 3. «Сервис не сказал дату» не читается как «вышло сегодня»."""
        assertEquals(StationRanker.FRESH_UNKNOWN, StationRanker.freshness(null), 1e-9)
        assertEquals(null, StationRanker.ageDays(StationRanker.Signals(), 2026))
    }

    @Test
    fun oldAgeSitsOnTheFloorAndNotBelowIt() {
        assertEquals(StationRanker.FRESH_FLOOR, StationRanker.freshness(3650.0), 1e-9)
    }

    @Test
    fun aReleaseDateIsMeasuredInDaysNotInYears() {
        val age = StationRanker.ageDays(StationRanker.Signals(releaseDate = "2026-06-20"), 2026)
        assertTrue("возраст должен быть в днях, получилось: $age", age != null && age in 0.0..60.0)
    }

    @Test
    fun aFutureReleaseIsNotAncient() {
        val age = StationRanker.ageDays(StationRanker.Signals(releaseDate = "2026-12-31"), 2026)
        assertEquals(0.0, age ?: -1.0, 1e-9)
    }

    @Test
    fun freshnessIsOneTermNotTheMainSignal() {
        """Требование 2: свежесть остаётся ОДНИМ слагаемым. Доказанная классика
        с сильными прочими признаками обязана бить свежую пустышку."""
        val classic = StationRanker.weight(
            tr("c", pop = 0.95),
            StationRanker.Signals(vetted = true, popularity = 0.95, year = 1998),
            StationRanker.Taste.EMPTY, 2026,
        )
        val fresh = StationRanker.weight(
            tr("f", pop = 0.05),
            StationRanker.Signals(popularity = 0.05, year = 2026),
            StationRanker.Taste.EMPTY, 2026,
        )
        assertTrue("старьё с весом не должно выпадать: $classic против $fresh", classic > fresh)
    }

    // ── квоты сессии: кейсы 6–10 ────────────────────────────────────────────

    @Test
    fun fourPlaysInsideTheWindowBanTheArtist() {
        """Кейс 6. Четыре записи артиста в скользящих трёх часах — пятая
        недопустима, каким бы сильным ни был её вес."""
        val s = session(10, 20, 30, 40)
        assertEquals(4, s.artistCount("Артист", NOW))
        assertFalse(s.eligible(tr("new"), NOW))
    }

    @Test
    fun theQuotaIsSlidingNotPerBatch() {
        """Кейс 7. Как только первая запись вышла за границу окна, артист
        возвращается: квота считает ОКНО, а не «сколько раз за пачку».

        Тут же видно, что правило «≤3 подряд» живёт отдельно и в этой сессии не
        мешает: четыре записи артиста разделены чужими, и в окне их три."""
        val outside = NOW - StationRanker.SESSION_WINDOW_MS - MIN
        val s = StationRanker.Session(
            listOf(
                StationRanker.Played(artist = "Артист", at = outside),
                StationRanker.Played(artist = "Артист", at = NOW - 4 * MIN),
                StationRanker.Played(artist = "Другой", at = NOW - 3 * MIN),
                StationRanker.Played(artist = "Артист", at = NOW - 2 * MIN),
                StationRanker.Played(artist = "Артист", at = NOW - MIN),
            ),
        )
        assertEquals("записи за окном считать нельзя", 3, s.artistCount("Артист", NOW))
        assertEquals("подряд только две", 2, s.artistRun("Артист"))
        assertTrue(s.eligible(tr("new"), NOW))
    }

    @Test
    fun threeInARowBanTheFourthEvenBelowTheCap() {
        """Кейс 8. Три подряд — уже стоп, хотя счётчик окна ещё не дошёл до 4."""
        val s = session(1, 2, 3)
        assertEquals(3, s.artistCount("Артист", NOW))
        assertEquals(3, s.artistRun("Артист"))
        assertFalse(s.eligible(tr("new"), NOW))
    }

    @Test
    fun albumQuotaCountsAcrossDifferentArtists() {
        """Кейс 9. Три записи с одного альбома за окно — при РАЗНЫХ артистах."""
        val s = StationRanker.Session(
            (1..3).map { play(NOW - it * MIN, artist = "Разные$it", album = "Disc One") },
        )
        assertEquals(3, s.albumCount("disc one", NOW))
        assertFalse(s.eligible(tr("y", artist = "Совершенно новый", album = "Disc One"), NOW))
    }

    @Test
    fun twoAlbumTracksInARowAreEnoughToStopTheThird() {
        """Кейс 10. Две записи альбома подряд — третья подряд недопустима."""
        val s = StationRanker.Session(
            listOf(
                play(NOW - 2 * MIN, artist = "Один", album = "Disc One"),
                play(NOW - MIN, artist = "Другой", album = "Disc One"),
            ),
        )
        assertEquals(2, s.albumRun("disc one"))
        assertFalse(s.eligible(tr("z", artist = "Третий", album = "Disc One"), NOW))
    }

    @Test
    fun recordsWithoutAnAlbumDoNotShareOneQuota() {
        """У половины выдачи альбома нет. Если бы пустое значение считалось
        одним альбомом, три таких вещи закрыли бы четвёртую.

        Артисты РАЗНЫЕ: иначе здесь ловился бы не альбомный лимит, а запрет
        на три записи одного артиста подряд."""
        val s = StationRanker.Session(
            (1..3).map { StationRanker.Played(artist = "Разные$it", at = NOW - it * MIN, album = null) },
        )
        assertEquals(0, s.albumCount("disc one", NOW))
        assertTrue(s.eligible(tr("new", album = null), NOW))
    }

    @Test
    fun anEmptySessionChecksNothing() {
        assertTrue(StationRanker.Session.EMPTY.eligible(tr("x"), NOW))
        assertEquals(0.0, StationRanker.Session.EMPTY.redundancy(tr("x")), 1e-9)
    }

    @Test
    fun aStrongWeightDoesNotBuyItsWayPastAQuota() {
        """Кейс 6 в отборе: самый сильный кандидат мешка не попадает в эфир,
        если квота запрещает его (ни `R`, ни MMR не обходят жёсткий фильтр)."""
        val banned = (1..4).map {
            tr("b$it", artist = "Артист", pop = 1.0) to
                StationRanker.Signals(vetted = true, charting = true, popularity = 1.0)
        }
        val allowed = listOf(
            tr("ok", artist = "Другой", pop = 0.1) to
                StationRanker.Signals(popularity = 0.1),
        )
        val out = StationRanker.rank(
            banned + allowed, seed = 5, size = 10,
            nowMs = NOW, session = session(10, 20, 30, 40),
        )
        assertTrue("запрещённый артист пролез в эфир: ${out.map { it.id }}",
                   out.none { it.artist == "Артист" })
    }

    @Test
    fun anEmptyAirAfterHardFiltersIsHonest() {
        """Кейс 17. После жёстких фильтров нечего ставить — станция получает
        пустой эфир и честный статус, а не молча нарушенную квоту."""
        val items = (1..5).map {
            tr("x$it", artist = "Артист") to StationRanker.Signals(vetted = true)
        }
        val out = StationRanker.rank(
            items, seed = 3, size = 10, nowMs = NOW, session = session(1, 2, 3, 4),
        )
        assertTrue("квота обойдена, эфир: ${out.map { it.id }}", out.isEmpty())
    }

    // ── похожесть и разнос: кейсы 11 и 18 ───────────────────────────────────

    @Test
    fun theMmrPenaltyIsExactlyWhatTheSpecPromises() {
        """Кейс 11 при R′=0,8: похожий на сыгранное получает 0,35, а несхожий —
        0,60. Числа из спецификации, а не «примерно»."""
        assertEquals(0.60, StationRanker.mmrScore(0.8, 1.0, 0.0), 1e-9)
        assertEquals(0.35, StationRanker.mmrScore(0.8, 1.0, 1.0), 1e-9)
    }

    @Test
    fun similarityIsTheSumOfItsFields() {
        val a = tr("a", artist = "Один", album = "X", label = "L", genre = "techno", year = 2025)
        val b = tr("b", artist = "Один", album = "X", label = "L", genre = "techno", year = 2026)
        assertEquals(1.0, StationRanker.similarity(a, b), 1e-9)
    }

    @Test
    fun missingFieldsContributeNothing() {
        """Кейс 18. Нет лейбла, альбома, жанра и года — считается только
        артист (0,50); молчание сервиса не объявляется ни сходством, ни
        несходством."""
        val a = tr("a", artist = "Один")
        val b = tr("b", artist = "Один")
        assertEquals(0.50, StationRanker.similarity(a, b), 1e-9)
    }

    @Test
    fun redundancyLooksOnlyAtTheLastTenPlayed() {
        """Окно разноса — последние ДЕСЯТЬ сыгранных: одиннадцатая назад запись
        уже не тянет кандидата вниз.

        Похожесть при этом ровно та, что обещана кейсом 18: у истории есть
        артист, но нет лейбла, альбома, жанра и года, поэтому совпавший артист
        даёт 0,50, а не 1,0 — молчание данных не добирается до «похожи
        наверняка»."""
        val s = StationRanker.Session(
            (1..11).map {
                StationRanker.Played(artist = if (it == 11) "Дальний" else "Артист", at = NOW - it * MIN)
            },
        )
        assertEquals(0.50, s.redundancy(tr("t", artist = "Артист")), 1e-9)
        assertEquals("одиннадцатая назад уже выпала из окна", 0.0, s.redundancy(tr("d", artist = "Дальний")), 1e-9)
        assertEquals(11, s.played.size)
    }

    @Test
    fun aDuplicateOfTheLastPlayedLosesShareToAnUnrelatedTrack() {
        """Кейс 11 в отборе: при равных весах кандидат, похожий на сыгранный
        последним, должен появляться в эфире РЕЖЕ, чем без истории. Сравнение по
        фиксированным seed, то есть повторимо, а не «в среднем повезло»."""
        val items = listOf(
            tr("dupe", artist = "Артист") to StationRanker.Signals(vetted = true, popularity = 0.5),
            tr("away", artist = "Другой") to StationRanker.Signals(vetted = true, popularity = 0.5),
        )
        val s = StationRanker.Session(listOf(StationRanker.Played(artist = "Артист", at = NOW - MIN)))
        val seeds = (1L..400L)
        val withHistory = seeds.count {
            StationRanker.rank(items, seed = it, size = 1, nowMs = NOW, session = s)
                .firstOrNull()?.id == "dupe"
        }
        val noHistory = seeds.count {
            StationRanker.rank(items, seed = it, size = 1).firstOrNull()?.id == "dupe"
        }
        assertTrue(
            "разнос не сдвинул выбор: без истории dupe выиграл $noHistory раз, с историей $withHistory",
            withHistory < noHistory,
        )
    }

    // ── решения из 020 п.2: нейтраль и крутилка характера ───────────────────

    @Test
    fun silenceAboutPopularityIsExactlyTheMiddleOfTheScale() {
        """020 п.2.3. Было UNKNOWN = 0,45 при правиле «неизвестное получает
        середину шкалы». Множитель популярности в весе — (0,55 + P), поэтому
        середина шкалы P — это ровно 0,5: при 0,45 молчание сервиса весило чуть
        МЕНЬШЕ реальной средней популярности, то есть наказывалось за то, чего
        сервис не сказал. Тест держит равенство, а не «примерно»."""
        assertEquals(
            StationRanker.weight(tr("x"), StationRanker.Signals(popularity = 0.5),
                StationRanker.Taste.EMPTY, 2026),
            StationRanker.weight(tr("x"), StationRanker.Signals(popularity = null),
                StationRanker.Taste.EMPTY, 2026),
            1e-9,
        )
        assertEquals(0.5, StationRanker.UNKNOWN, 1e-9)
    }

    @Test
    fun theHalfLifeKnobChangesWeightsAndIsNotDecoration() {
        """020 п.2.2. Полупериод свежести — параметр того же слоя (90 —
        «незнакомое», 180 — общее, 365 — «популярное»); настройка без провода
        запрещена AGENTS.md, поэтому проверяем сам провод: вес одной и той же
        вещи должен зависеть от полупериода, причём в правильную сторону — чем
        длиннее полупериод, тем слабее наказано старьё.

        Возраст взят такой, где не мешает пол свежести (0,15): двухлетняя вещь
        при 90 днях уже на полу, при 365 — ещё нет."""
        val twoYears = StationRanker.Signals(vetted = true, popularity = 0.5, year = 2024)
        val track = tr("t", artist = "Артист", year = 2024)
        val w90 = StationRanker.weight(track, twoYears, StationRanker.Taste.EMPTY, 2026, 90.0)
        val w180 = StationRanker.weight(track, twoYears, StationRanker.Taste.EMPTY, 2026, 180.0)
        val w365 = StationRanker.weight(track, twoYears, StationRanker.Taste.EMPTY, 2026, 365.0)
        assertTrue("при 365 днях старьё должно весить больше, чем при 90: $w90 против $w365",
                   w365 > w90)
        assertTrue("порядок по полупериодам обязан быть монотонным: $w90 $w180 $w365",
                   w180 >= w90 && w365 >= w180)
        // сама формула: половина ровно на полупериоде, и на любом из трёх значений
        assertEquals(0.5, StationRanker.freshness(90.0, 90.0), 1e-9)
        assertEquals(0.5, StationRanker.freshness(180.0, 180.0), 1e-9)
        assertEquals(0.5, StationRanker.freshness(365.0, 365.0), 1e-9)
    }

    @Test
    fun aHardQuotaOutranksASoftPenalty() {
        """Мягкий разнос не имеет права «разжать» жёсткую квоту: даже если весь
        мешок похож на сыгранное, запрещённый артист в эфир не попадает."""
        val s = StationRanker.Session(
            (1..4).map { StationRanker.Played(artist = "Артист", at = NOW - it * MIN) },
        )
        val items = listOf(
            tr("a", artist = "Артист") to StationRanker.Signals(vetted = true),
            tr("b", artist = "Артист") to StationRanker.Signals(vetted = true),
        )
        assertTrue(
            StationRanker.rank(items, seed = 1, size = 5, nowMs = NOW, session = s).isEmpty(),
        )
    }
}
