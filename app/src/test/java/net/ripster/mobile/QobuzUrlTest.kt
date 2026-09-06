package net.ripster.mobile

import net.ripster.mobile.service.qobuz.QobuzUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Идентификатор Qobuz — последний сегмент пути, а не первый после вида.
 *
 * Живой случай 06.09.2026. Владелец прислал ссылку на альбом Nouvelle Vague и
 * сказал, что в мобиле ссылки не работают. Разбор брал `album/([a-z0-9]+)` и
 * останавливался на первом дефисе слага: в идентификатор уходила буква «a».
 * Клиент спрашивал у Qobuz альбом «a», получал пусто, и человек читал
 * «ничего не найдено» — симптом, по которому причину не увидеть.
 *
 * С ПК теми же ключами тот же альбом отдавал 13 треков, то есть сервис был ни
 * при чём.
 */
class QobuzUrlTest {

    @Test
    fun `the id is the last segment, not the slug`() {
        val r = QobuzUrl.parse(
            "https://www.qobuz.com/fr-fr/album/a-date-with-depeche-mode-nouvelle-vague/bfw3j2gdkcmtt",
        )
        assertEquals("album", r?.kind)
        assertEquals("bfw3j2gdkcmtt", r?.id)
    }

    @Test
    fun `the short form without a slug works too`() {
        assertEquals("bfw3j2gdkcmtt", QobuzUrl.parse("https://open.qobuz.com/album/bfw3j2gdkcmtt")?.id)
    }

    @Test
    fun `query and fragment do not become part of the id`() {
        assertEquals("abc123", QobuzUrl.parse("https://www.qobuz.com/fr-fr/album/x/abc123?utm=1")?.id)
        assertEquals("abc123", QobuzUrl.parse("https://www.qobuz.com/fr-fr/album/x/abc123#play")?.id)
    }

    @Test
    fun `a track link keeps its numeric id`() {
        val r = QobuzUrl.parse("https://www.qobuz.com/fr-fr/track/12345678")
        assertEquals("track", r?.kind)
        assertEquals("12345678", r?.id)
    }

    @Test
    fun `anything else is honestly null`() {
        // Не наш адрес и не альбом с треком — лучше вернуть null, чем спросить
        // сервис про мусор и показать «ничего не найдено».
        assertNull(QobuzUrl.parse("https://www.deezer.com/album/123"))
        assertNull(QobuzUrl.parse("https://www.qobuz.com/fr-fr/artist/nouvelle-vague/12345"))
        assertNull(QobuzUrl.parse(null))
    }
}
