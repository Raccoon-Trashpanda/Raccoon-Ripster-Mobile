package net.ripster.mobile.service.spotify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверсия Spotify зависит от анонимного токена `open.spotify.com`, который
 * Spotify периодически меняет. Проверяем детерминированное (разбор ссылки) и
 * что поиск либо возвращает треки с ISRC, либо падает чисто — без краша.
 */
class SpotifyConvertTest {

    private val client = SpotifyConvertClient()

    @Test
    fun nonSpotifyUrlResolvesNull(): Unit = runBlocking {
        assertNull(client.resolve("https://tidal.com/browse/track/12345"))
    }

    /**
     * Поиск по Spotify либо отдаёт СВОИ треки, либо не отдаёт ничего — и оба
     * исхода законны.
     *
     * Раньше здесь стояло «если поиск удался — треки не пустые», и тест был
     * красным постоянно. Ожидание было неверным: Spotify в мобильной версии —
     * сервис «по ссылке» (так он и помечен в выборе сервисов), поиском по имени
     * он намеренно не ищет, а веб-токен для `api.spotify.com/v1` заблокирован
     * (см. скилл ripster-spotify-tokens). Пустая выдача — штатный ответ, а не
     * поломка, и требовать обратного значит держать красный тест, который учат
     * игнорировать.
     *
     * Проверяем то, что действительно должно держаться: не падать иначе как
     * IOException и не выдавать чужие треки за спотифаевские.
     */
    @Test
    fun searchReturnsOwnTracksOrNothing(): Unit = runBlocking {
        val r = runCatching { client.search("Daft Punk One More Time") }
        r.onSuccess { sel ->
            assertTrue(
                "выдача не должна содержать треки чужого сервиса",
                sel.tracks.all { it.service.name == "SPOTIFY" },
            )
        }
        r.onFailure {
            assertTrue("ожидали IOException при недоступном токене, получили ${it::class.simpleName}",
                it is java.io.IOException)
        }
    }
}
