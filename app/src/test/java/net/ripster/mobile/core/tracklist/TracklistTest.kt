package net.ripster.mobile.core.tracklist

import net.ripster.mobile.core.tracklist.Tracklist.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Треклист микса из описания («12:34 Artist — Title») и форматтер метки времени.
 *
 * Rule of the file: «ничего не выдумываем» — строка без таймкода не должна
 * порождать трек, а строка-«простыня» не должна превращаться в название.
 * Разбор описания — единственный источник таймкодов у SoundCloud, поэтому
 * промах здесь = пустая секция на глазах у человека, который явно её оставил
 * в описании своего микса.
 */
class TracklistTest {

    private fun d(text: String) = Tracklist.fromDescription(text)

    // ── базовый формат ──────────────────────────────────────────────────────

    @Test
    fun parsesMinuteSecondWithDashSeparatedArtist() {
        assertEquals(listOf(Entry(754, "Blondish", "Solar Return")), d("12:34 Blondish - Solar Return"))
    }

    @Test
    fun parsesHourStamp() {
        assertEquals(3723, d("1:02:03 Artist - Title").single().offsetSec)
    }

    @Test
    fun leadingZeroHoursAndSingleDigitMinutes() {
        assertEquals(65, d("01:05 Artist - Title").single().offsetSec)
        assertEquals(5, d("0:05 Artist - Title").single().offsetSec)
    }

    @Test
    fun bracketedStampIsAccepted() {
        assertEquals(754, d("[12:34] Blondish - Solar Return").single().offsetSec)
    }

    @Test
    fun otherSeparatorsAfterTheStamp() {
        for (sep in listOf("-", "–", "—", ")", ".", "|")) {
            assertEquals("разделитель '$sep'", 754, d("12:34$sep Blondish - Solar Return").single().offsetSec)
        }
    }

    @Test
    fun emDashAsArtistTitleSeparator() {
        val e = d("12:34 Blondish — Solar Return").single()
        assertEquals("Blondish", e.artist)
        assertEquals("Solar Return", e.title)
    }

    @Test
    fun withoutSeparatorEverythingGoesToTitle() {
        val e = d("12:34 Solar Return").single()
        assertEquals("", e.artist)
        assertEquals("Solar Return", e.title)
    }

    @Test
    fun onlyTheFirstSeparatorSplits() {
        val e = d("12:34 Blondish - Solar Return - Reprise").single()
        assertEquals("Blondish", e.artist)
        assertEquals("Solar Return - Reprise", e.title)
    }

    @Test
    fun hyphenInsideArtistSurvives() {
        val e = d("12:34 AC/DC - Back in Black").single()
        assertEquals("AC/DC", e.artist)
    }

    @Test
    fun tightDashDoesNotSplit() {
        // «Artist-Title» без пробелов — это название, а не пара: иначе
        // «Deadmau5-Strobe» разлетелся бы на пол-артиста.
        val e = d("12:34 Deadmau5-Strobe").single()
        assertEquals("", e.artist)
        assertEquals("Deadmau5-Strobe", e.title)
    }

    // ── порядок и фильтры ───────────────────────────────────────────────────

    @Test
    fun orderFollowsTheDescription() {
        val r = d("00:00 A - One\n12:34 B - Two\n1:02:03 C - Three")
        assertEquals(listOf(0, 754, 3723), r.map { it.offsetSec })
    }

    @Test
    fun linesWithoutTimestampsAreIgnored() {
        val r = d("Tracklist для микса:\n12:34 A - One\nспасибо за поддержку!\n9:9 х - у")
        assertEquals(1, r.size)
    }

    @Test
    fun stampNeedsTwoDigitSeconds() {
        // «12:5» — это скорее дата или опечатка, не таймкод.
        assertTrue(d("12:5 A - B").isEmpty())
    }

    @Test
    fun threeDigitPrefixIsNotATimestamp() {
        assertTrue(d("123:45 A - B").isEmpty())
    }

    @Test
    fun rangeIsNotValidated() {
        // Честно: 75 секунд в минуте — то, что написал автор описания.
        assertEquals(12 * 60 + 75, d("12:75 A - B").single().offsetSec)
    }

    @Test
    fun blankRestIsDropped() {
        assertTrue(d("12:34").isEmpty())
        assertTrue(d("12:34 -").isEmpty())
        assertTrue(d("12:34 |·—").isEmpty())
    }

    @Test
    fun trailingDecorationsAreTrimmed() {
        val e = d("12:34 Blondish - Solar Return |").single()
        assertEquals("Solar Return", e.title)
    }

    @Test
    fun overlongRestIsNotACueLine() {
        assertTrue(d("12:34 " + "x".repeat(301)).isEmpty())
        assertEquals(1, d("12:34 " + "x".repeat(300)).size)
    }

    @Test
    fun blankDescriptionIsEmptyList() {
        assertTrue(d("").isEmpty())
        assertTrue(d("   \n\t ").isEmpty())
    }

    @Test
    fun crlfDescriptionParsesLikeLf() {
        val r = d("00:00 A - One\r\n12:34 B - Two\r\n")
        assertEquals(listOf(0, 754), r.map { it.offsetSec })
        assertEquals("Two", r.last().title)
    }

    @Test
    fun buyLinkNoiseDoesNotBecomeTrack() {
        assertTrue(d("https://blondish.bandcamp.com/track/solar-return").isEmpty())
    }

    // ── метка времени ───────────────────────────────────────────────────────

    @Test
    fun stampIsMinutesSecondsBelowAnHour() {
        assertEquals("0:00", Entry(0, "", "").stamp)
        assertEquals("0:59", Entry(59, "", "").stamp)
        assertEquals("12:34", Entry(754, "", "").stamp)
        assertEquals("59:59", Entry(3599, "", "").stamp)
    }

    @Test
    fun stampGrowsAnHourDigitAboveAnHour() {
        assertEquals("1:00:00", Entry(3600, "", "").stamp)
        assertEquals("1:02:03", Entry(3723, "", "").stamp)
        assertEquals("2:00:45", Entry(7245, "", "").stamp)
    }
}
