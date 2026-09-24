package net.ripster.mobile.service.soundcloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.service.soundcloud.dto.ScTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SoundCloud: структурных кредитов у сервиса нет вообще.
 *
 * Что реально отдаёт `api-v2.soundcloud.com` (жалоба владельца 13.09.2026 —
 * «вижу 1 артиста, а их несколько»):
 *  • `/search/tracks`, `/tracks?ids=`, `/resolve` — везде ОДНА версия трека:
 *    `user.username` (ник загрузчика) и, если повезло, `publisher_metadata.artist`
 *    (одна строка, издателем);
 *  • списка участников, ролей, «featured» — НЕТ. Догружать запросом нечего:
 *    карточка трека не богаче строки из поиска.
 *
 * Поэтому состав собирается только из того, что человек написал в заголовке, и
 * ровно то, что читается однозначно («feat.», «(X Remix)», «A x B — Трек»).
 * Остальное остаётся как было: догадка не имеет права портить имя.
 */
class SoundCloudArtistsTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "sc-artists-" + System.nanoTime())
        .apply { mkdirs() }
    private val client = SoundCloudClient(oauthToken = null, cacheDir = cacheDir)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Фейловый ответ `/search/tracks`: обложка и пермалинк — на `.invalid`. */
    private fun map(artist: String?, uploader: String, title: String): Track {
        val pm = if (artist == null) "" else """"publisher_metadata": {"artist": "$artist"},"""
        val raw = """
            {"id": 7123456789, "kind": "track", "title": "$title",
             $pm "user": {"id": 42, "username": "$uploader",
                          "permalink_url": "https://soundcloud.example.invalid/track"},
             "artwork_url": "https://i.example.invalid/art-xxxx-large.jpg",
             "duration": 218000, "media": {"transcodings": []}}
        """.trimIndent()
        return with(client) { json.decodeFromString(ScTrack.serializer(), raw).toTrack() }
    }

    @Test
    fun featInTheTitleBecomesAGuest() {
        assertEquals("Zedd feat. Foxes", map("Zedd", "zedd", "Clarity (feat. Foxes)").artist)
    }

    @Test
    fun remixNameInTheTitleBecomesAGuest() {
        val t = map("Dua Lipa", "dualipa", "Levitating (The Blessed Madonna Remix)")
        assertEquals("Dua Lipa feat. The Blessed Madonna", t.artist)
    }

    @Test
    fun collabPrefixBeforeTheDashIsTwoArtists() {
        // Ни `publisher_metadata`, ни ник загрузчика состава не дают — но
        // «A x B — Трек» в заголовке читается однозначно.
        val t = map(null, "tiesto", "Tiësto x Charli XCX - The Business")
        assertEquals("tiesto, Charli XCX", t.artist)
    }

    @Test
    fun singleArtistTrackStaysSingle() {
        for (title in listOf(
            "Delilah (pull me out of this)",          // скобки есть, людей нет
            "Sunflower (Original Mix)",               // «Original Mix» — не имя
            "Rhythm & Blues",                         // «&» внутри названия песни
            "Mabel - I Feel Like Rhythm & Blues",     // «&» после « - » не трогаем
            "Tiësto x - Adagio",                      // висячее «x» никого не зовёт
        )) {
            val t = map("Mabel", "mabel", title)
            assertTrue("из «$title» выдумали состав: ${t.artist}", t.artist == "Mabel")
        }
    }

    @Test
    fun duetNamedWithAmpersandIsNotSplit() {
        // Группа, у которой «&» в названии: распав её, мы показали бы
        // «Simon feat. Garfunkel» вместо одного дуэта.
        val t = map("Simon & Garfunkel", "simonandgarfunkel", "Simon & Garfunkel - Bridge Over Troubled Water")
        assertEquals("Simon & Garfunkel", t.artist)
    }

    @Test
    fun publisherArtistAlreadyJoinedIsLeftAlone() {
        // Издатель записал состав тем же «, »-форматом — вторично резать его
        // нельзя, это тот же самый смысл.
        val t = map("Art Department, Lane 8", "phuturegrooves", "Alternate Reality")
        assertEquals("Art Department, Lane 8", t.artist)
    }

    @Test
    fun creditsCostNoRequestAndSayNothingAboutASoloTrack() = runBlocking {
        val solo = map("Fred again..", "fredagain", "Delilah (pull me out of this)")
        assertNull("на сольном треке догадки быть не должно", client.contributorsFor(solo))

        val collab = map("Zedd", "zedd", "Clarity (feat. Foxes)")
        assertEquals("Zedd feat. Foxes", client.contributorsFor(collab)?.display())
    }

    @Test
    fun uploaderNickSurvivesWherePublisherArtistMissing() {
        // Без `publisher_metadata` остаётся ник загрузчика — он и есть шапка
        // строки, сколько бы сервис ни молчал о составе.
        val t = map(null, "phuturegrooves", "Stranger Things (feat. Makem Payton)")
        assertEquals("phuturegrooves feat. Makem Payton", t.artist)
    }
}
