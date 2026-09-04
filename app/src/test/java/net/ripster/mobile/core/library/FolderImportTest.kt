package net.ripster.mobile.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор пути при импорте своей коллекции.
 *
 * Обойти папку легко; кашу вместо библиотеки делают три вещи, и именно они тут
 * проверяются: многодисковый релиз не должен распасться на два альбома, две
 * версии одного альбома не должны слипнуться в один, а пустые теги не повод
 * выдумывать исполнителя.
 */
class FolderImportTest {

    @Test
    fun artistAndAlbumComeFromTheUsualLayout() {
        val h = FolderImport.hintFromDirs(listOf("Massive Attack", "Mezzanine"))
        assertEquals("Massive Attack", h.artist)
        assertEquals("Mezzanine", h.album)
        assertEquals(null, h.disc)
    }

    @Test
    fun discFolderDoesNotBecomeTheAlbum() {
        """`Альбом/CD2` — это один альбом с двумя дисками, а не альбом «CD2»."""
        for (d in listOf("CD2", "cd 2", "Disc 2", "disk-2", "Диск 2")) {
            val h = FolderImport.hintFromDirs(listOf("Pink Floyd", "The Wall", d))
            assertEquals("подпапка $d принята за альбом", "The Wall", h.album)
            assertEquals("Pink Floyd", h.artist)
            assertEquals(2, h.disc)
        }
    }

    @Test
    fun deluxeEditionStaysASeparateRelease() {
        """У делюкса другой треклист — сливать его с обычным изданием нельзя."""
        val a = FolderImport.hintFromDirs(listOf("Portishead", "Dummy"))
        val b = FolderImport.hintFromDirs(listOf("Portishead", "Dummy (Deluxe)"))
        assertNotEquals(a.album, b.album)
    }

    @Test
    fun aFolderNamedLikeAnAlbumIsNotMistakenForADisc() {
        """«Disclosure» начинается с «disc», но диском не является."""
        val h = FolderImport.hintFromDirs(listOf("Various", "Disclosure"))
        assertEquals("Disclosure", h.album)
        assertEquals(null, h.disc)
    }

    @Test
    fun aFlatFolderStillGivesAnAlbum() {
        val h = FolderImport.hintFromDirs(listOf("Сборник 2019"))
        assertEquals("Сборник 2019", h.album)
        assertEquals("", h.artist)      // выдумывать исполнителя не из чего
    }

    @Test
    fun rootLevelFileHintsNothing() {
        val h = FolderImport.hintFromDirs(emptyList())
        assertEquals("", h.album)
        assertEquals("", h.artist)
    }

    @Test
    fun sameFileAlwaysGetsTheSameId() {
        """Повторный импорт папки не должен плодить дубли."""
        val u = "content://com.android.externalstorage/tree/primary%3AMusic/x.flac"
        assertEquals(FolderImport.idFor(u), FolderImport.idFor(u))
        assertNotEquals(FolderImport.idFor(u), FolderImport.idFor(u + "2"))
    }

    @Test
    fun onlyAudioExtensionsAreConsidered() {
        assertTrue("flac" in FolderImport.AUDIO_EXT)
        assertTrue("m4a" in FolderImport.AUDIO_EXT)
        assertTrue("opus" in FolderImport.AUDIO_EXT)
        assertTrue("jpg" !in FolderImport.AUDIO_EXT)
        assertTrue("cue" !in FolderImport.AUDIO_EXT)
        assertTrue("log" !in FolderImport.AUDIO_EXT)
    }
}
