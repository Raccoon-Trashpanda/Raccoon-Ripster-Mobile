package net.ripster.mobile

import net.ripster.mobile.core.service.GenreKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ярлык приходит на языке ВИТРИНЫ, а жанр от этого не меняется.
 *
 * 06.09.2026 владелец открыл карточку релиза при полностью английском
 * интерфейсе и увидел жанр «Електро». Строка пришла от Deezer на украинской
 * витрине и печаталась на экране как есть, минуя канонизатор — который в
 * приложении был и написан ровно для этого.
 *
 * Сводим к одному ключу, а показываемое имя берём из словаря по языку
 * приложения.
 */
class GenreCyrillicTest {

    @Test
    fun `the same genre in three alphabets is one genre`() {
        assertEquals(GenreKey.ELECTRONIC, GenreKey.of("Electro"))
        assertEquals(GenreKey.ELECTRONIC, GenreKey.of("Електро"))
        assertEquals(GenreKey.ELECTRONIC, GenreKey.of("Электро"))
    }

    @Test
    fun `common cyrillic labels are recognised`() {
        assertEquals(GenreKey.TECHNO, GenreKey.of("Техно"))
        assertEquals(GenreKey.ROCK, GenreKey.of("Рок"))
        assertEquals(GenreKey.JAZZ, GenreKey.of("Джаз"))
        assertEquals(GenreKey.RAP, GenreKey.of("Хип-хоп"))
        assertEquals(GenreKey.CLASSICAL, GenreKey.of("Классика"))
    }

    @Test
    fun `an unknown label stays unknown`() {
        // Не узнали — значит не узнали. Придумать ключ «поближе» было бы
        // хуже сырой строки: экран показал бы уверенную неправду.
        assertNull(GenreKey.of("Аудиосказки"))
        assertNull(GenreKey.of(""))
        assertNull(GenreKey.of(null))
    }
}
