package net.ripster.mobile.service.qobuz

import kotlinx.serialization.json.Json
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.service.qobuz.dto.QbAlbumFull
import net.ripster.mobile.service.qobuz.dto.QbSearch
import net.ripster.mobile.service.qobuz.dto.QbTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Qobuz и совместные вещи.
 *
 * Что отдаёт сервис (жалоба владельца 13.09.2026):
 *  • `track/search` и `track/get` — `performer` (ОДНО имя) плюс строка
 *    `performers`: «Name, MainArtist - Name2, FeaturedArtist, Vocal …» — вот
 *    там весь состав с ролями, и доплачивать за него запросом не нужно;
 *  • `album/get` — `artists[]` с ролями на релиз в целом.
 *
 * Композитора, продюсера и оркестр в строку исполнителя не берём никогда: в
 * той же `performers` они идут первым блоком, и без отбора классический релиз
 * ехал бы списком титров. Хосты обложек — `.invalid`.
 */
class QobuzContributorsTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "qb-contrib-" + System.nanoTime())
        .apply { mkdirs() }
    private val client = QobuzClient(
        email = null, password = null, token = null,
        appId = null, secret = null, cacheDir = cacheDir,
    )
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun track(raw: String): Track =
        with(client) { json.decodeFromString(QbTrack.serializer(), raw).toTrack() }

    @Test
    fun performersStringFillsTheArtistLine() {
        val raw = """
            {
              "id": 41001234, "title": "Turn On the Lights again..", "duration": 218,
              "performer": {"id": 1, "name": "Fred again.."},
              "performers": "Fred again.., MainArtist - Swae Lee, FeaturedArtist - Angus Stone, FeaturedArtist - Fred Gibson, Producer",
              "isrc": "XX2221112222",
              "album": {"id": "pl.41001234", "title": "TEN DAYS",
                        "artist": {"id": 1, "name": "Fred again.."},
                        "image": {"large": "https://cdn.example.invalid/album/41001234.jpg"}}
            }
        """.trimIndent()
        assertEquals("Fred again.. feat. Swae Lee, Angus Stone", track(raw).artist)
    }

    @Test
    fun composersAndProducersStayOffTheArtistLine() {
        val raw = """
            {"id": 7, "title": "Symphony No. 6", "performer": {"id": 8, "name": "Georg Solti"},
             "performers": "Ludwig van Beethoven, Composer - Vienna Philharmonic Orchestra, Orchestra - Georg Solti, Conductor"}
        """.trimIndent()
        assertEquals("Georg Solti", track(raw).artist)
    }

    @Test
    fun trackWithoutPerformersStringKeepsTheSingleName() {
        val raw = """
            {"id": 9, "title": "Aire", "performer": {"id": 3, "name": "Bicep"},
             "album": {"id": "pl.1", "title": "Isles", "artist": {"id": 3, "name": "Bicep"}}}
        """.trimIndent()
        assertEquals("Bicep", track(raw).artist)
    }

    @Test
    fun hyphenatedAndDottedNamesAreNotBlockBoundaries() {
        // Блоки в `performers` разделены пробел-дефис-пробелом: «E-Type» и
        // «Dr. Alban» от этого не распадаются.
        val raw = """
            {"id": 11, "title": "Razzia", "performer": {"id": 12, "name": "E-Type"},
             "performers": "E-Type, MainArtist - Dr. Alban, MainArtist - Someone Else, Lyricist"}
        """.trimIndent()
        assertEquals("E-Type, Dr. Alban", track(raw).artist)
    }

    @Test
    fun searchRowsCarryTheirOwnCredits() {
        // То, что выдаёт `track/search`: несколько треков, у одного состав есть,
        // у другого нет. Никаких запросов поверх поиска — кредиты уже в ответе.
        val raw = """
            {"tracks": {"total": 2, "items": [
              {"id": 21, "title": "Collab", "performer": {"id": 1, "name": "Zedd"},
               "performers": "Zedd, MainArtist - Foxes, FeaturedArtist"},
              {"id": 22, "title": "Solo", "performer": {"id": 1, "name": "Zedd"}}
            ]}}
        """.trimIndent()
        val items = json.decodeFromString(QbSearch.serializer(), raw).tracks.items
        val lines = with(client) { items.map { it.toTrack() } }
        assertEquals("Zedd feat. Foxes", lines[0].artist)
        assertEquals("Zedd", lines[1].artist)
    }

    @Test
    fun albumLineUpNamesTheReleaseNotEveryTrack() {
        // `album.artists[]` — состав РЕЛИЗА. Он уходит в albumArtist; под
        // строкой каждого трека он напечатан быть не может: это было бы
        // утверждением, что все они играют и эту песню.
        val raw = """
            {
              "id": "pl.55", "title": "Ten Days", "artist": {"id": 1, "name": "Fred again.."},
              "artists": [
                {"name": "Fred again..", "roles": ["MainArtist", "Producer"]},
                {"name": "Romy", "roles": ["FeaturedArtist"]}
              ],
              "tracks": {"total": 1, "items": [
                {"id": 31, "title": "Delilah", "performer": {"id": 1, "name": "Fred again.."},
                 "performers": "Fred again.., MainArtist"}
              ]}
            }
        """.trimIndent()
        val album = json.decodeFromString(QbAlbumFull.serializer(), raw)
        val lines = with(client) { album.tracks.items.map { it.toTrack(album) } }
        assertEquals("Fred again..", lines[0].artist)
        assertEquals("Fred again.. feat. Romy", lines[0].albumArtist)
    }

    @Test
    fun nothingInventsWithoutAnyCredit() {
        val raw = """{"id": 33, "title": "Silent", "performer": {"id": 0, "name": ""}}"""
        val t = track(raw)
        assertTrue("пустой performer не должен превратиться в состав", t.artist.isBlank())
    }
}
