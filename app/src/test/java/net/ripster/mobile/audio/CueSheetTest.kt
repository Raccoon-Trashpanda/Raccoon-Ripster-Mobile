package net.ripster.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор CUE — порт десктопного `parse_cue()`, и обещания из шапки файла
 * проверяются здесь дословно: кадры это 1/75 секунды, TITLE/PERFORMER до
 * первого TRACK относятся к альбому, у трека без своего PERFORMER артистом
 * становится альбомный, учитывается только INDEX 01, и кавычки снимаются
 * только парные.
 *
 * Каждый пункт — то, на чём две независимые реализации одного формата
 * расходятся не сразу, «а на чьём-нибудь конкретном миксе через полгода».
 */
class CueSheetTest {

    private fun cue(text: String) = parseCue(text.trimIndent())

    // ── кадры ───────────────────────────────────────────────────────────────

    @Test
    fun framesAre75PerSecond() {
        assertEquals(2.0 + 74 / 75.0, parseCueTime("00:02:74"), 1e-9)
        assertEquals(60.0, parseCueTime("01:00:00"), 1e-9)
        assertEquals(0.0, parseCueTime("00:00:00"), 1e-9)
    }

    @Test
    fun framesAreNotHundredths() {
        // Типичная подмена: 1/100 дала бы 0.01, а кадр — 1/75.
        assertEquals(1.0 / 75.0, parseCueTime("00:00:01"), 1e-9)
    }

    @Test
    fun malformedTimeIsZeroNotThrow() {
        assertEquals(0.0, parseCueTime("1:2"), 1e-9)
        assertEquals(0.0, parseCueTime("a:b:c"), 1e-9)
        assertEquals(0.0, parseCueTime(""), 1e-9)
        assertEquals(0.0, parseCueTime("00:00:00:00"), 1e-9)
    }

    @Test
    fun timeToleratesSurroundingSpaces() {
        assertEquals(90.0, parseCueTime("  01:30:00  "), 1e-9)
    }

    // ── кавычки ─────────────────────────────────────────────────────────────

    @Test
    fun onlyPairedQuotesAreRemoved() {
        assertEquals("x", cueUnquote("\"x\""))
        assertEquals("\"x", cueUnquote("\"x"))
        assertEquals("x'", cueUnquote("x'"))
        assertEquals("", cueUnquote("\"\""))
        assertEquals("\"", cueUnquote("\""))
    }

    @Test
    fun apostropheInsideTitleSurvives() {
        val s = cue("""
            FILE "mix.flac" WAVE
            TRACK 01 AUDIO
              TITLE "It's a Kind of Magic"
              INDEX 01 00:00:00
        """)
        assertEquals("It's a Kind of Magic", s.tracks.single().title)
    }

    // ── альбом против трека ─────────────────────────────────────────────────

    @Test
    fun metadataBeforeFirstTrackBelongsToAlbum() {
        val s = cue("""
            TITLE "Mixed Live"
            PERFORMER "DJ A"
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
              TITLE "Set One"
              PERFORMER "DJ B"
              INDEX 01 00:00:00
        """)
        assertEquals("Mixed Live", s.album)
        assertEquals("DJ A", s.albumArtist)
        assertEquals(listOf("Set One"), s.tracks.map { it.title })
        assertEquals(listOf("DJ B"), s.tracks.map { it.artist })
    }

    @Test
    fun trackWithoutPerformerInheritsAlbumArtist() {
        val s = cue("""
            PERFORMER "VA - Mixed Live"
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
              TITLE "One"
              INDEX 01 00:00:00
            TRACK 02 AUDIO
              TITLE "Two"
              PERFORMER "Guest"
              INDEX 01 36:00:00
        """)
        assertEquals("VA - Mixed Live", s.tracks[0].artist)
        assertEquals("Guest", s.tracks[1].artist)
    }

    @Test
    fun albumWithoutTitleOrPerformerIsEmptyStrings() {
        val s = cue("""
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
              TITLE "One"
              INDEX 01 00:00:00
        """)
        assertEquals("", s.album)
        assertEquals("", s.albumArtist)
        assertEquals("", s.tracks.single().artist)
    }

    // ── INDEX ───────────────────────────────────────────────────────────────

    @Test
    fun index00IsPregapNotTrackStart() {
        val s = cue("""
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
              TITLE "One"
              INDEX 00 74:59:50
              INDEX 01 00:02:00
        """)
        assertEquals(2.0, s.tracks.single().start, 1e-9)
    }

    @Test
    fun index02IsIgnored() {
        val s = cue("""
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
              INDEX 01 01:00:00
              INDEX 02 05:00:00
        """)
        assertEquals(60.0, s.tracks.single().start, 1e-9)
    }

    @Test
    fun trackWithoutIndexStartsAtZero() {
        val s = cue("""
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
              TITLE "One"
        """)
        assertEquals(0.0, s.tracks.single().start, 1e-9)
    }

    // ── нумерация и структура ───────────────────────────────────────────────

    @Test
    fun trackNumbersComeFromTheFileNotFromOrder() {
        val s = cue("""
            FILE "a.flac" WAVE
            TRACK 07 AUDIO
              INDEX 01 00:00:00
            TRACK 12 AUDIO
              INDEX 01 10:00:00
        """)
        assertEquals(listOf(7, 12), s.tracks.map { it.num })
    }

    @Test
    fun unparseableTrackNumberFallsBackToPosition() {
        val s = cue("""
            FILE "a.flac" WAVE
            TRACK A AUDIO
              INDEX 01 00:00:00
            TRACK B AUDIO
              INDEX 01 10:00:00
        """)
        assertEquals(listOf(1, 2), s.tracks.map { it.num })
    }

    @Test
    fun lowerCaseTrackKeywordsAndIndentationAreFine() {
        val s = cue("""
            file "a.flac" wave
              track 01 audio
                title "One"
                index 01 00:03:00
        """)
        assertEquals("One", s.tracks.single().title)
        assertEquals(3.0, s.tracks.single().start, 1e-9)
    }

    @Test
    fun remCommentsDoNotLeakIntoMetadata() {
        val s = cue("""
            REM GENRE Mix
            REM DATE 2026
            TITLE "Real"
            FILE "a.flac" WAVE
            REM TITLE fake
            REM PERFORMER fake
            TRACK 01 AUDIO
              REM PERFORMER fake
              TITLE "One"
              INDEX 01 00:00:00
        """)
        assertEquals("Real", s.album)
        assertEquals("", s.albumArtist)
        assertEquals("One", s.tracks.single().title)
        assertEquals("", s.tracks.single().artist)
    }

    @Test
    fun emptyTextIsAnEmptySheet() {
        val s = parseCue("")
        assertNull(s.audio)
        assertEquals("", s.album)
        assertEquals(emptyList<CueTrack>(), s.tracks)
    }

    @Test
    fun crlfLineEndsParseLikeLf() {
        val crlf = "FILE \"a.flac\" WAVE\r\nTRACK 01 AUDIO\r\n  TITLE \"One\"\r\n  INDEX 01 01:00:00\r\n"
        val s = parseCue(crlf)
        assertEquals("a.flac", s.audio)
        assertEquals(60.0, s.tracks.single().start, 1e-9)
    }

    // ── FILE ────────────────────────────────────────────────────────────────

    @Test
    fun fileNameIsTakenWithoutQuotes() {
        assertEquals("mix.flac", cue("""FILE "mix.flac" WAVE""").audio)
        assertEquals("mix.flac", cue("""FILE mix.flac FLAC""").audio)
        assertEquals("VA - Live (2026).flac", cue("""FILE "VA - Live (2026).flac" WAVE""").audio)
    }

    @Test
    fun lastFileWinsWhenImageIsMultiDisk() {
        val s = cue("""
            FILE "disc1.flac" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            FILE "disc2.flac" WAVE
            TRACK 02 AUDIO
              INDEX 01 00:00:00
        """)
        assertEquals("disc2.flac", s.audio)
        assertEquals(2, s.tracks.size)
    }

    @Test
    fun lowerCaseFileNameIsStillRecognised() {
        assertEquals("a.flac", cue("""file "a.flac" WAVE""").audio)
    }

    // ── выбор трека по времени ──────────────────────────────────────────────

    private val sheet = cue("""
        FILE "mix.flac" WAVE
        TRACK 01 AUDIO
          INDEX 01 00:00:00
        TRACK 02 AUDIO
          INDEX 01 10:00:00
        TRACK 03 AUDIO
          INDEX 01 20:00:00
    """)

    @Test
    fun indexAtPicksThePlayingTrack() {
        assertEquals(0, sheet.indexAt(0.0))
        assertEquals(0, sheet.indexAt(599.9))
        assertEquals(1, sheet.indexAt(600.0))
        assertEquals(1, sheet.indexAt(900.0))
        assertEquals(2, sheet.indexAt(1200.0))
        assertEquals(2, sheet.indexAt(3600.0))
    }

    @Test
    fun indexAtOnEmptySheetIsMinusOne() {
        assertEquals(-1, parseCue("FILE \"a.flac\" WAVE").indexAt(1.0))
    }

    /**
     * Не заявленное в KDoc, но важное для читателя: строки CUE читаются как есть,
     * поиск по времени идёт до первого нарушения монотонности. Десктоп пишет
     * индексы по порядку, поэтому порядок строк и есть порядок треков —
     * сортировки здесь нет намеренно (порт обязан совпадать с оригиналом).
     */
    @Test
    fun outOfOrderIndicesStopTheScanAtFirstRegression() {
        val weird = cue("""
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            TRACK 02 AUDIO
              INDEX 01 100:00:00
            TRACK 03 AUDIO
              INDEX 01 50:00:00
        """)
        assertEquals(0, weird.indexAt(60.0))
    }
}
