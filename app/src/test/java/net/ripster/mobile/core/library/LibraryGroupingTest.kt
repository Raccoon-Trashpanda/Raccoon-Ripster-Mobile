package net.ripster.mobile.core.library

import net.ripster.mobile.core.db.LibraryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скачанный релиз должен играть собой, а не вперемешку со всей библиотекой.
 *
 * Владелец: «нужно внедрить понятия альбом, сингл, чтобы можно было проигрывать
 * тот самый релиз, который скачал, а не вразброс». Библиотека была плоским
 * списком треков, и тап ставил в очередь ВСЁ, начиная с выбранного места.
 *
 * Проверяется именно правило группировки — на устройстве этого не показать,
 * пока в библиотеке лежат одни синглы.
 */
class LibraryGroupingTest {

    private var seq = 0

    private fun e(title: String, artist: String, album: String?) = LibraryEntity(
        id = "id${seq++}",
        title = title,
        artist = artist,
        album = album,
        serviceId = "deezer",
        container = "flac",
        bitrateKbps = null,
        filePath = "/music/$title.flac",
        sizeBytes = 1,
        artworkUrl = null,
        addedAt = seq.toLong(),
    )

    @Test
    fun tracksOfOneReleaseFormOneGroup() {
        val g = LibraryGrouping.group(listOf(
            e("Angel", "Massive Attack", "Mezzanine"),
            e("Risingson", "Massive Attack", "Mezzanine"),
            e("Teardrop", "Massive Attack", "Mezzanine"),
        ))
        assertEquals(1, g.size)
        assertEquals(3, g.values.first().size)
    }

    @Test
    fun sameAlbumNameByDifferentArtistsStaysApart() {
        """«Greatest Hits» есть у десятка артистов — склеивать нельзя."""
        val g = LibraryGrouping.group(listOf(
            e("A", "Queen", "Greatest Hits"),
            e("B", "ABBA", "Greatest Hits"),
        ))
        assertEquals(2, g.size)
    }

    @Test
    fun singlesDoNotCollapseIntoOneNamelessPile() {
        """Записи без альбома — разные синглы, а не один «безымянный релиз»."""
        val g = LibraryGrouping.group(listOf(
            e("moths", "Navjaxx", null),
            e("Teardrop", "Das Kabinett", ""),
            e("Sour Times", "Portishead", "   "),
        ))
        assertEquals(3, g.size)
        assertTrue(g.values.all { it.size == 1 })
    }

    @Test
    fun orderOfGroupsAndTracksIsPreserved() {
        """Номера трека в записи нет, поэтому порядок библиотеки — это и есть
        порядок альбома у того, что скачали пачкой."""
        val g = LibraryGrouping.group(listOf(
            e("One", "A", "First"),
            e("Two", "A", "First"),
            e("Solo", "B", null),
            e("Three", "A", "First"),
        ))
        assertEquals(listOf("First", "Solo"), g.values.map { LibraryGrouping.titleOf(it) })
        assertEquals(listOf("One", "Two", "Three"), g.values.first().map { it.title })
    }

    @Test
    fun caseAndSpacesDoNotSplitARelease() {
        val g = LibraryGrouping.group(listOf(
            e("A", "Massive Attack", "Mezzanine"),
            e("B", "massive attack", "  MEZZANINE "),
        ))
        assertEquals(1, g.size)
    }

    @Test
    fun compilationIsSignedVariousArtists() {
        val group = listOf(
            e("A", "Artist One", "Now 47"),
            e("B", "Artist Two", "Now 47"),
        )
        assertEquals("Various Artists", LibraryGrouping.artistOf(group))
    }

    @Test
    fun singleShowsItsOwnTitle() {
        val group = listOf(e("moths", "Navjaxx", null))
        assertEquals("moths", LibraryGrouping.titleOf(group))
        assertEquals("Navjaxx", LibraryGrouping.artistOf(group))
    }

    @Test
    fun eachSingleGetsItsOwnKey() {
        val a = e("X", "A", null)
        val b = e("X", "A", null)
        assertNotEquals(LibraryGrouping.keyOf(a), LibraryGrouping.keyOf(b))
    }

    @Test
    fun emptyLibraryIsEmpty() {
        assertTrue(LibraryGrouping.group(emptyList()).isEmpty())
    }
}
