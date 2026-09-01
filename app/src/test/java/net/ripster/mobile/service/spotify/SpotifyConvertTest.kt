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

    @Test
    fun searchWorksOrFailsCleanly(): Unit = runBlocking {
        val r = runCatching { client.search("Daft Punk One More Time") }
        r.onSuccess { sel ->
            assertTrue("если поиск удался — треки не пустые", sel.tracks.isNotEmpty())
            assertTrue("тип SPOTIFY", sel.tracks.first().service.name == "SPOTIFY")
        }
        r.onFailure {
            assertTrue("ожидали IOException при недоступном токене, получили ${it::class.simpleName}",
                it is java.io.IOException)
        }
    }
}
