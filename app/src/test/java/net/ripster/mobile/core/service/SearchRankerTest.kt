package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Album
import net.ripster.mobile.core.model.Artist
import net.ripster.mobile.core.model.MediaKind
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ранжирование и дедуп поисковой выдачи.
 *
 * `SearchRanker` обещает (шапка файла) детерминированный порядок: текстовое
 * совпадение → популярность → аффинити к библиотеке/истории →service bonus, и
 * дедуп по ISRC/UPC с остатком по «артист | ядро названия | бакет длительности».
 * Всё это чистые функции, и ровно они решают, увидит человек точное совпадение
 * первым или двадцатым. Проверяем порядок, а не абсолютные баллы: баллы —
 * деталь реализации, порядок — обещание.
 */
class SearchRankerTest {

    private fun t(
        title: String,
        artist: String,
        service: Service = Service.DEEZER,
        dur: Long? = 300_000,
        isrc: String? = null,
        album: String? = null,
        raw: Map<String, String> = emptyMap(),
    ) = Track(
        id = "$artist|$title|$service|$dur", title = title, artist = artist,
        service = service, durationMs = dur, isrc = isrc, albumTitle = album, raw = raw,
    )

    private fun a(
        title: String,
        artist: String,
        service: Service = Service.DEEZER,
        upc: String? = null,
        tracks: Int? = 10,
        year: Int? = null,
    ) = Album(
        id = "$artist|$title|$upc", title = title, artist = artist, service = service,
        upc = upc, trackCount = tracks, year = year,
    )

    private fun ranked(vararg ts: Track) = SearchRanker.rankTracks("q", ts.toList(), SearchRanker.Ctx.EMPTY)

    // ── нормализация ────────────────────────────────────────────────────────

    @Test
    fun normStripsDiacritics() {
        assertEquals("bjork", SearchRanker.norm("Björk"))
        assertEquals("damon", SearchRanker.norm("Dämon"))
    }

    @Test
    fun normKeepsCyrillic() = assertEquals("земфира", SearchRanker.norm("Земфира"))

    @Test
    fun normPunctuationBecomesOneSpace() {
        assertEquals("hello world", SearchRanker.norm("Hello,  World!"))
        // апостроф — не буква: «Guns N' Roses» это «guns n roses», не «guns roses»
        assertEquals("guns n roses", SearchRanker.norm("Guns N' Roses"))
    }

    @Test
    fun normKeepsDigits() = assertEquals("99 luftballons", SearchRanker.norm("99 Luftballons"))

    @Test
    fun normOfNothingIsEmpty() {
        assertEquals("", SearchRanker.norm(null))
        assertEquals("", SearchRanker.norm("   "))
    }

    @Test
    fun normTrims() {
        assertEquals("madworld", SearchRanker.norm("  MadWorld  "))
    }

    // ── текстовое совпадение ────────────────────────────────────────────────

    @Test
    fun exactTitleBeatsPrefixedTitle() {
        val r = SearchRanker.rankTracks(
            "teardrop",
            listOf(t("Teardrop Live", "Massive Attack"), t("Teardrop", "Massive Attack")),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals("Teardrop", r.first().title)
    }

    @Test
    fun artistDashTitleQueryUsesBothHalves() {
        val r = SearchRanker.rankTracks(
            "Massive Attack - Teardrop",
            listOf(t("Teardrop", "Someone Else"), t("Teardrop", "Massive Attack")),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals("Massive Attack", r.first().artist)
    }

    @Test
    fun featTailDoesNotHideTheMatch() {
        // «Teardrop (feat. …)» — та же запись, только с приставкой: и дедуп, и
        // приоритет чистого названия.
        val r = SearchRanker.rankTracks(
            "teardrop",
            listOf(t("Teardrop (feat. Kalae)", "Massive Attack", dur = 240_000), t("Teardrop", "Massive Attack", dur = 240_001)),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals("Teardrop", r.first().title)
    }

    @Test
    fun queryWithoutDashScoresArtistAndTitleTogether() {
        val r = SearchRanker.rankTracks(
            "massive attack teardrop",
            listOf(t("Teardrop", "Massive Attack"), t("Unforgiven", "Massive Attack")),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals("Teardrop", r.first().title)
    }

    // ── версии и откровенный мусор ──────────────────────────────────────────

    @Test
    fun karaokeVersionSinks() {
        val r = SearchRanker.rankTracks(
            "bang bang",
            listOf(
                t("Bang Bang (Made Famous By Willke)", "Willke", dur = 200_000),
                t("Bang Bang", "Willke", dur = 201_000),
            ),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals("Bang Bang", r.first().title)
    }

    @Test
    fun remixIsNotTheOriginal() = assertEquals(
        2,
        ranked(
            t("Teardrop", "MA"),
            t("Teardrop (Mad Professor Remix)", "MA", dur = 340_000),
        ).size,
    )

    @Test
    fun liveVersionLosesToStudioOne() {
        val r = SearchRanker.rankTracks(
            "teardrop",
            listOf(t("Teardrop (Live)", "MA", dur = 200_000), t("Teardrop", "MA", dur = 201_000)),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals("Teardrop", r.first().title)
    }

    @Test
    fun requestedVersionIsNotPunished() {
        // Человек СПРОСИЛ лайв — наказывать его за слово «live» нельзя.
        val r = SearchRanker.rankTracks(
            "teardrop live",
            listOf(t("Teardrop (Live)", "MA", dur = 200_000), t("Teardrop", "MA", dur = 201_000)),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals("Teardrop (Live)", r.first().title)
    }

    @Test
    fun interludeShorterThan45sLoses() {
        val r = SearchRanker.rankTracks(
            "teardrop",
            listOf(t("Teardrop", "MA", dur = 30_000), t("Teardrop", "MA", dur = 300_000)),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals(300_000L, r.first().durationMs)
    }

    // ── дедуп ───────────────────────────────────────────────────────────────

    @Test
    fun sameIsrcIsOneRowRegardlessOfTitles() {
        val r = SearchRanker.rankTracks(
            "teardrop",
            listOf(
                t("Teardrop", "MA", Service.QOBUZ, isrc = "QMS271700090"),
                t("Тear Drop (Reworked)", "MA", Service.DEEZER, dur = 340_000, isrc = "qms271700090"),
            ),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals(1, r.size)
        assertEquals(Service.QOBUZ, r.first().service)
    }

    @Test
    fun tooShortIsrcIsNotAnIdentity() {
        val r = ranked(
            t("Teardrop", "MA", isrc = "ABC123"),
            t("Other Song", "BB", Service.QOBUZ, isrc = "ABC123"),
        )
        assertEquals(2, r.size)
    }

    @Test
    fun tenCharIsrcIsAnIdentity() {
        val r = ranked(
            t("Teardrop", "MA", isrc = "ABC1234567"),
            t("Other Song", "BB", Service.QOBUZ, isrc = "abc1234567"),
        )
        assertEquals(1, r.size)
    }

    @Test
    fun durationBucketSplitsDedup() {
        // Бакет — 4 с: 300 000 и 304 000 — разные корзины, две записи одного
        // названия остаются двумя строками.
        assertEquals(2, ranked(t("X", "A", dur = 300_000), t("X", "A", dur = 304_000)).size)
        assertEquals(1, ranked(t("X", "A", dur = 300_000), t("X", "A", dur = 301_000)).size)
    }

    @Test
    fun dedupKeepsTheBestScored() {
        val r = ranked(
            t("X", "A", Service.SOUNDCLOUD),
            t("X", "A", Service.QOBUZ),
        )
        assertEquals(1, r.size)
        assertEquals(Service.QOBUZ, r.first().service)
    }

    @Test
    fun emptyAndSingletonListsPassThroughUntouched() {
        val one = listOf(t("X", "A"))
        assertEquals(one, SearchRanker.rankTracks("q", one, SearchRanker.Ctx.EMPTY))
        assertEquals(emptyList<Track>(), SearchRanker.rankTracks("q", emptyList(), SearchRanker.Ctx.EMPTY))
    }

    // ── предпочтение сервиса и аффинити ────────────────────────────────────

    @Test
    fun losslessServiceWinsAnEqualMatch() {
        val r = ranked(
            t("Same", "Artist", Service.SOUNDCLOUD, dur = 200_000),
            t("Same", "Artist", Service.DEEZER, dur = 300_000),
            t("Same", "Artist", Service.QOBUZ, dur = 400_000),
        )
        assertEquals(listOf(Service.QOBUZ, Service.DEEZER, Service.SOUNDCLOUD), r.map { it.service })
    }

    @Test
    fun emptyQueryOrdersByServicePreferenceOnly() {
        val r = ranked(
            t("Anything", "One", Service.SOUNDCLOUD),
            t("Else", "Two", Service.TIDAL),
        )
        assertEquals(Service.TIDAL, r.first().service)
    }

    @Test
    fun libraryArtistBeatsForeignOne() {
        val ctx = SearchRanker.Ctx(libArtists = setOf(SearchRanker.norm("Виталик")))
        val r = SearchRanker.rankTracks(
            "midnight",
            listOf(t("Midnight", "Виталик", Service.SOUNDCLOUD), t("Midnight", "Bob", Service.QOBUZ)),
            ctx,
        )
        assertEquals("Виталик", r.first().artist)
    }

    @Test
    fun historyArtistRanksAboveStrangerButBelowLibrary() {
        val ctx = SearchRanker.Ctx(histArtists = setOf("old"), libArtists = setOf("fresh"))
        val r = SearchRanker.rankTracks(
            "song",
            listOf(t("Song", "nobody"), t("Song", "old", dur = 200_000), t("Song", "fresh", dur = 201_000)),
            ctx,
        )
        assertEquals(listOf("fresh", "old", "nobody"), r.map { it.artist })
    }

    @Test
    fun libraryAlbumLiftsItsTrack() {
        val ctx = SearchRanker.Ctx(libAlbums = setOf("a|ghost"))
        val r = SearchRanker.rankTracks(
            "song",
            listOf(
                t("Song", "a", album = "Ghost", dur = 300_000),
                t("Song", "a", album = "Other", dur = 340_000),
            ),
            ctx,
        )
        assertEquals("Ghost", r.first().albumTitle)
    }

    // ── популярность ────────────────────────────────────────────────────────

    @Test
    fun rawPopularityLiftsRow() {
        val r = ranked(
            t("Song", "a", dur = 200_000, raw = mapOf("popularity" to "5")),
            t("Song", "a", dur = 301_000, raw = mapOf("popularity" to "95")),
        )
        assertEquals("95", r.first().raw["popularity"])
    }

    @Test
    fun rawDeezerRankAndFansLiftRow() {
        val byRank = ranked(
            t("Song", "a", dur = 200_000, raw = mapOf("rank" to "100")),
            t("Song", "a", dur = 301_000, raw = mapOf("rank" to "1000000")),
        )
        assertEquals("1000000", byRank.first().raw["rank"])

        val byFans = ranked(
            t("Song", "a", dur = 200_000, raw = mapOf("fans" to "10")),
            t("Song", "a", dur = 301_000, raw = mapOf("fans" to "1000000")),
        )
        assertEquals("1000000", byFans.first().raw["fans"])
    }

    @Test
    fun modelPopularityFieldIsHonoured() {
        val popular = Track(id = "p", title = "Song", artist = "a", service = Service.DEEZER, durationMs = 200_000, popularity = 1.0)
        val quiet = Track(id = "q", title = "Song", artist = "a", service = Service.DEEZER, durationMs = 300_000, popularity = 0.0)
        val r = SearchRanker.rankTracks("song", listOf(quiet, popular), SearchRanker.Ctx.EMPTY)
        assertEquals(
            "популярность из модели обязана влиять на порядок (шапка: «текстовое совпадение → популярность»)",
            "p", r.first().id,
        )
    }

    // ── альбомы ─────────────────────────────────────────────────────────────

    @Test
    fun albumUpcDedupsAcrossServices() {
        val r = SearchRanker.rankAlbums(
            "reworks",
            listOf(
                a("Reworks", "A", Service.SOUNDCLOUD, upc = "0123456789"),
                a("Reworks", "A", Service.QOBUZ, upc = "0123456789"),
            ),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals(1, r.size)
        assertEquals(Service.QOBUZ, r.first().service)
    }

    @Test
    fun albumUpcShorterThanEightIsNotAnIdentity() {
        assertEquals(
            2,
            SearchRanker.rankAlbums(
                "r",
                listOf(a("Reworks", "A", upc = "1234567"), a("Other", "B", upc = "1234567")),
                SearchRanker.Ctx.EMPTY,
            ).size,
        )
    }

    @Test
    fun albumWithoutUpcDedupsByArtistAndTitle() {
        assertEquals(
            1,
            SearchRanker.rankAlbums(
                "r", listOf(a("Reworks", "A"), a("Reworks", "A", Service.SOUNDCLOUD)), SearchRanker.Ctx.EMPTY,
            ).size,
        )
    }

    @Test
    fun oneTrackAndHugeCompilationsSink() {
        val r = SearchRanker.rankAlbums(
            "reworks",
            listOf(
                a("Reworks", "A", upc = "11111111", tracks = 0),
                a("Reworks", "A", upc = "22222222", tracks = 300),
                a("Reworks", "A", upc = "33333333", tracks = 12),
            ),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals(12, r.first().trackCount)
        assertTrue("подозрительные сборники обязаны быть в хвосте", r.last().trackCount != 12)
    }

    @Test
    fun recentYearWinsAnEqualMatch() {
        val r = SearchRanker.rankAlbums(
            "reworks",
            listOf(a("Reworks", "A", upc = "11111111", year = 1970), a("Reworks", "A", upc = "22222222", year = 2021)),
            SearchRanker.Ctx.EMPTY,
        )
        assertEquals(2021, r.first().year)
    }

    @Test
    fun libraryAlbumContextLiftsAlbum() {
        val ctx = SearchRanker.Ctx(libAlbums = setOf("a|kept"))
        val r = SearchRanker.rankAlbums(
            "kept",
            listOf(a("Kept", "A", upc = "11111111"), a("Kept", "B", upc = "22222222")),
            ctx,
        )
        assertEquals("11111111", r.first().upc)
    }

    // ── публичный вход ──────────────────────────────────────────────────────

    @Test
    fun rankRewritesBothListsAndKeepsTheRest() {
        val sel = MediaSelection(
            kind = MediaKind.ALBUM,
            tracks = listOf(t("X", "A", Service.SOUNDCLOUD), t("X", "A", Service.QOBUZ)),
            albums = listOf(a("Reworks", "A"), a("Reworks", "A", Service.SOUNDCLOUD)),
            artists = listOf(Artist(id = "1", name = "A", service = Service.DEEZER)),
            containerTitle = "kept",
        )
        val r = SearchRanker.rank("x", sel)
        assertEquals(1, r.tracks.size)
        assertEquals(1, r.albums.size)
        assertEquals(sel.artists, r.artists)
        assertEquals("kept", r.containerTitle)
        assertEquals(MediaKind.ALBUM, r.kind)
    }
}
