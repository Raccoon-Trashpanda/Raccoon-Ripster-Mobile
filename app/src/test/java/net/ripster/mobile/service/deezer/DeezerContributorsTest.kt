package net.ripster.mobile.service.deezer

import kotlinx.serialization.json.Json
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.service.Contributors
import net.ripster.mobile.service.deezer.dto.DzApiAlbumFull
import net.ripster.mobile.service.deezer.dto.DzApiContributors
import net.ripster.mobile.service.deezer.dto.DzApiSearch
import net.ripster.mobile.service.deezer.dto.DzApiTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deezer и совместные вещи.
 *
 * Что реально отдаёт сервис (жалоба владельца 13.09.2026 — «вижу 1 артиста, а
 * их несколько»):
 *  • `GET /search/track` — у трека ОДИН `artist`, состава нет;
 *  • `GET /track/{id}` и `GET /album/{id}` — тот же `artist`, а полный состав
 *    лежит в `contributors[]`, где у каждого роль "Main" или "Featured";
 *  • отдельного `GET /track/{id}/contributors` НЕТ — живой ответ 23.09.2026:
 *    `InvalidQueryException 600 Unknown path`; догрузка берёт `/track/{id}`.
 *
 * Поэтому проверка в двух местах: выдача поиска остаётся одноимённой (мы там выдумывать
 * нечего), а кредиты появляются ровно там, где Deezer их прислал. Все ссылки в
 * фейлах — на `.invalid`: такой домен не резолвится никогда.
 */
class DeezerContributorsTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "dz-contrib-" + System.nanoTime())
        .apply { mkdirs() }
    private val client = DeezerClient(arl = "", cacheDir = cacheDir)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun track(raw: String): Track =
        with(client) { json.decodeFromString(DzApiTrack.serializer(), raw).toTrack() }

    private val collab = """
        {
          "id": 123456789, "title": "Turn On the Lights again..", "duration": 218, "rank": 912000,
          "isrc": "XX2221112222",
          "artist": {"id": 987, "name": "Fred again.."},
          "contributors": [
            {"id": 987, "name": "Fred again..", "type": "artist", "role": "Main"},
            {"id": 555, "name": "Swae Lee", "type": "artist", "role": "Featured"},
            {"id": 556, "name": "Angus Stone", "type": "artist", "role": "Featured"},
            {"id": 777, "name": "Some Writer", "type": "artist", "role": "Writer"},
            {"id": 778, "name": "Some Producer", "type": "artist", "role": "Producer"}
          ],
          "album": {"id": 4444, "title": "TEN DAYS", "cover_xl": "https://cdn.example.invalid/cover/4444.jpg"}
        }
    """.trimIndent()

    @Test
    fun contributorsOfTheTrackCardFillTheArtistLine() {
        assertEquals("Fred again.. feat. Swae Lee, Angus Stone", track(collab).artist)
    }

    @Test
    fun rolesOtherThanMainAndFeaturedStayOffTheLine() {
        // Writer и Producer у Deezer в `contributors[]` стоят рядом с артистами.
        // Если бы они попадали в строку, «исполнитель» превратился бы в титры.
        val line = track(collab).artist
        assertTrue("в строку артиста уехали титры: $line", !line.contains("Writer") && !line.contains("Producer"))
    }

    @Test
    fun searchRowStaysWhatDeezerSentIt() {
        // Поиск: `artist` один, `contributors` нет. Догонять состав будем
        // отдельным запросом (ArtistDepth), а не догадкой по названию.
        val raw = """
            {"data": [{
              "id": 1, "title": "Moments", "duration": 200,
              "artist": {"id": 2, "name": "Fred again.."},
              "album": {"id": 3, "title": "ELEVEN", "cover_xl": "https://cdn.example.invalid/c.jpg"}
            }]}
        """.trimIndent()
        val found = json.decodeFromString(DzApiSearch.serializer(), raw).data.single()
        assertEquals("Fred again..", with(client) { found.toTrack() }.artist)
    }

    @Test
    fun trackCardCarriesContributorsLiveShape() {
        // Форма живой карточки `GET /track/482990352` (23.09.2026): оба участника
        // с ролью "Main", поля сверх нужного парсер обязан пропускать.
        val raw = """
            {"id": 482990352, "title": "One Kiss", "duration": 214,
             "artist": {"id": 1, "name": "Calvin Harris"},
             "album": {"id": 9, "title": "One Kiss"},
             "contributors": [
               {"id": 1, "name": "Calvin Harris", "type": "artist", "role": "Main", "link": "https://x.invalid/1"},
               {"id": 2, "name": "Dua Lipa", "type": "artist", "role": "Main", "link": "https://x.invalid/2"}
             ]}
        """.trimIndent()
        val list = json.decodeFromString(DzApiTrack.serializer(), raw).contributors.map { it.name to it.role }
        assertEquals("Calvin Harris, Dua Lipa", Contributors.fromRoles(list, "Calvin Harris").display())
    }

    @Test
    fun albumTracklistKeepsItsOwnCreditsPerTrack() {
        // Состав трека — не состав альбома: приглашённый солист одной песни не
        // должен печататься под всеми остальными.
        val raw = """
            {
              "id": 4444, "title": "TEN DAYS", "nb_tracks": 2,
              "artist": {"id": 987, "name": "Fred again.."},
              "tracks": {"data": [
                {"id": 10, "title": "Turn On the Lights again..",
                 "artist": {"id": 987, "name": "Fred again.."},
                 "contributors": [{"id": 555, "name": "Swae Lee", "role": "Featured"}]},
                {"id": 11, "title": "Delilah",
                 "artist": {"id": 987, "name": "Fred again.."}}
              ]}
            }
        """.trimIndent()
        val albumFull = json.decodeFromString(DzApiAlbumFull.serializer(), raw)
        val lines = with(client) { albumFull.tracks.data.map { it.toTrack(albumFull) } }
        assertEquals("Fred again.. feat. Swae Lee", lines[0].artist)
        assertEquals("Fred again..", lines[1].artist)
        assertEquals("Fred again..", lines[1].albumArtist)
    }

    @Test
    fun emptyContributorsArrayInventsNobody() {
        val raw = """
            {"id": 12, "title": "Ry", "artist": {"id": 3, "name": "Bicep"}, "contributors": []}
        """.trimIndent()
        assertEquals("Bicep", track(raw).artist)
    }
}
