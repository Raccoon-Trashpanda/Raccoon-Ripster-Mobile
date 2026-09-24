package net.ripster.mobile.core.storage

import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шаблон имени файла, которое уезжает в библиотеку.
 *
 * Обещания из шапки `NameTemplate`: относительный путь со слешами, КАЖДЫЙ
 * сегмент очищен от символов, недопустимых в именах Android/FAT, расширение
 * описывает РЕАЛЬНО лежащее содержимое, а не пожелание (поймано 05.09.2026,
 * когда Apple отдавал MP4 туда, где просили FLAC). Проверить это без телефона
 * можно целиком — от сюда и 22 кейса вместо «глянем на эмуляторе».
 */
class NameTemplateTest {

    private val flac = QualityTier(id = "flac_16", label = "Lossless", lossless = true, container = "flac")
    private val mp3 = QualityTier(id = "mp3_320", label = "320 kbps", lossless = false, container = "mp3")

    private fun track(
        artist: String = "Massive Attack",
        album: String? = "Mezzanine",
        title: String = "Teardrop",
        albumArtist: String? = null,
        num: Int? = 4,
        disc: Int? = null,
        year: Int? = 1998,
        service: Service = Service.DEEZER,
    ) = Track(
        id = "1", title = title, artist = artist, service = service, albumTitle = album,
        albumArtist = albumArtist, trackNumber = num, discNumber = disc, year = year,
    )

    private fun r(template: String, t: Track, q: QualityTier = flac, actual: String? = null) =
        NameTemplate.render(template, t, q, actual)

    // ── плейсхолдеры ────────────────────────────────────────────────────────

    @Test
    fun defaultTemplateExpandsAllPlaceholders() {
        assertEquals(
            "Massive Attack/Mezzanine/04 - Teardrop.flac",
            r(NameTemplate.DEFAULT, track()),
        )
    }

    @Test
    fun blankTemplateFallsBackToDefault() {
        assertEquals(r(NameTemplate.DEFAULT, track()), r("   ", track()))
    }

    @Test
    fun albumArtistFallsBackToArtist() {
        assertEquals("X/Y/01 - Z.flac", r("{albumartist}/{album}/{track} - {title}", track(artist = "X", album = "Y", title = "Z", num = 1)))
    }

    @Test
    fun albumFallsBackToTitleWhenNoAlbum() {
        assertEquals("Teardrop/04 - Teardrop.flac", r("{album}/{track} - {title}", track(album = null)))
    }

    @Test
    fun trackNumberIsZeroPadded() {
        assertEquals("01 - T.flac", r("{track} - {title}", track(num = 1, title = "T")))
        assertEquals("12 - T.flac", r("{track} - {title}", track(num = 12, title = "T")))
        assertEquals("100 - T.flac", r("{track} - {title}", track(num = 100, title = "T")))
    }

    @Test
    fun emptyTrackDoesNotLeaveADanglingDash() {
        // Пустой {track} раньше оставлял «- Title» с висячим разделителем.
        assertEquals("Teardrop.flac", r("{track} - {title}", track(num = null)))
    }

    @Test
    fun discYearServiceAndQualityExpand() {
        assertEquals(
            "Deezer cd2 1998 Lossless.flac",
            r("{service} cd{disc} {year} {quality}", track(disc = 2)),
        )
    }

    @Test
    fun unknownPlaceholdersAreStrippedNotPrinted() {
        assertEquals("Teardrop.flac", r("{title}{nope}", track()))
        // «без регэкспа» — значит только совершенно строчные имена; `{Title}` остаётся
        // как есть, и это документированное поведение, а не молчаливая подмена.
        assertEquals("{Title}.flac", r("{Title}", track()))
    }

    @Test
    fun everyKeyInOnePath() {
        val p = r("{albumartist}/{album}/{disc}cd{disc}-{track} {title} ({year}) {quality} {service}", track(disc = 1))
        assertEquals("Massive Attack/Mezzanine/1cd1-04 Teardrop (1998) Lossless Deezer.flac", p)
    }

    // ── расширение ──────────────────────────────────────────────────────────

    @Test
    fun sniffedContainerWinsOverRequestedQuality() {
        assertEquals("Massive Attack/Mezzanine/04 - Teardrop.mp4", r(NameTemplate.DEFAULT, track(), flac, "mp4"))
    }

    @Test
    fun qualityContainerIsUsedWhenNothingSniffed() {
        assertTrue(r(NameTemplate.DEFAULT, track(), mp3).endsWith(".mp3"))
    }

    @Test
    fun blankContainerNeverLeavesADanglingDot() {
        // Пустая подсказка = «неизвестно»: честнее `.bin`, чем соврать про `.flac`.
        assertTrue(r(NameTemplate.DEFAULT, track(), flac, "").endsWith(".bin"))
        assertTrue(r(NameTemplate.DEFAULT, track(), mp3.copy(container = ""), null).endsWith(".bin"))
    }

    // ── санитайз сегмента ───────────────────────────────────────────────────

    @Test
    fun illegalCharactersAreRemoved() {
        val t = track(album = "A\\B:C*D?E\"F<G>H|I", artist = "OK")
        assertEquals("OK/ABCDEFGHI/04 - Teardrop.flac", r("{artist}/{album}/{track} - {title}", t))
    }

    @Test
    fun controlCharactersAreRemoved() {
        assertEquals("TabStop.flac", r("{title}", track(title = "Tab\u0007Stop")))
        assertEquals("line.flac", r("{title}", track(title = "l\u0000ine")))
    }

    @Test
    fun whitespaceRunsCollapseButInteriorHyphenSurvives() {
        assertEquals("Two Three - Four.flac", r("{title}", track(title = "Two \t  Three -  Four")))
    }

    @Test
    fun edgesAreTrimmedOfSeparatorsDotsAndUnderscores() {
        assertEquals("Teardrop.flac", r("{title}", track(title = "  - . _ Teardrop _ . -  ")))
    }

    @Test
    fun longSegmentIsCappedAt120() {
        val p = r("{title}", track(title = "x".repeat(400)))
        assertEquals(120, p.substringBeforeLast('.').length)
    }

    @Test
    fun cyrillicAndDiacriticsSurviveThePath() {
        assertEquals("Земфира/Изменения.flac", r("{artist}/{title}", track(artist = "Земфира", title = "Изменения")))
        assertEquals("Björk/Hyperballad.flac", r("{artist}/{title}", track(artist = "Björk", title = "Hyperballad")))
    }

    @Test
    fun emptySegmentsDoNotCreateEmptyFolders() {
        assertEquals("Teardrop.flac", r("{albumartist}/{album}/{title}", track(album = "", artist = "  ")))
    }

    @Test
    fun slashInsideAValueIsNotAFolderSeparator() {
        val p = r("{artist}/{title}", track(artist = "AC/DC", title = "Back"))
        assertFalse(
            "сегмент обязан очищаться от недопустимых символов (шапка NameTemplate), а не разъезжаться по каталогам",
            p.contains("/DC/"),
        )
        assertEquals("ACDC/Back.flac", p)
    }

    @Test
    fun allBlankMetadataStillProducesARealName() {
        val p = r(NameTemplate.DEFAULT, track(artist = "", album = "", title = "", num = null))
        assertTrue("имя не может быть пустым: $p", p.substringBeforeLast('.').isNotBlank())
    }
}
