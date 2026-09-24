package net.ripster.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BUG-1 (этап 1 LIVE-кампании): открыл артиста из альбома, жмёшь BACK — улетел на
 * «Главную», альбом потерян. Стекло из двух страниц держалось жёстким порядком
 * отрисовки «релиз всегда над артистом», поэтому артиста, открытого ИЗ релиза,
 * приходилось открывать ЦЕНОЙ закрытия релиза.
 *
 * [visibleOverlay] — чистая модель этого стека: верхним видно то, что открыли
 * последним, а второе стекло ждёт под ним и возвращается по BACK. Тестируется без
 * Compose ровно там, где прятался баг.
 */
class OverlayStackTest {

    /** BACK с артиста, открытого из альбома, — альбом на месте (тот самый кейс). */
    @Test
    fun artistOpenedFromAlbumKeepsAlbumUnderneath() {
        // Открыт альбом (top=ALBUM), поверх него артист (openArtist выставил top=ARTIST).
        assertEquals(Overlay.ARTIST, visibleOverlay(artist = true, album = true, top = Overlay.ARTIST))
        // BACK закрывает артиста (artist=false) — остаётся альбом, не «Главная».
        assertEquals(Overlay.ALBUM, visibleOverlay(artist = false, album = true, top = Overlay.ARTIST))
    }

    /** Обратный, уже работавший переход: релиз поверх артиста, BACK — к артисту. */
    @Test
    fun albumOpenedFromArtistReturnsToArtist() {
        assertEquals(Overlay.ALBUM, visibleOverlay(artist = true, album = true, top = Overlay.ALBUM))
        assertEquals(Overlay.ARTIST, visibleOverlay(artist = true, album = false, top = Overlay.ALBUM))
    }

    /** Открыт один оверлей — он и виден, каким бы ни был `top`. */
    @Test
    fun singleOpenOverlayWinsRegardlessOfTop() {
        for (top in Overlay.entries) {
            assertEquals(Overlay.ARTIST, visibleOverlay(artist = true, album = false, top = top))
            assertEquals(Overlay.ALBUM, visibleOverlay(artist = false, album = true, top = top))
        }
    }

    /** Оба закрыты — ни одного стекла (экран вкладки). */
    @Test
    fun nothingOpenShowsNothing() {
        assertNull(visibleOverlay(artist = false, album = false, top = Overlay.ARTIST))
        assertNull(visibleOverlay(artist = false, album = false, top = Overlay.ALBUM))
    }
}
