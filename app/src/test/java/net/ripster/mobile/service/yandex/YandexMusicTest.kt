package net.ripster.mobile.service.yandex

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Всё в Яндекс.Музыке требует OAuth-токена — без него проверяем только
 * разбор ссылок и что пустой токен не роняет `isConfigured()`.
 */
class YandexMusicTest {

    private val client = YandexMusicClient(
        oauthToken = "",
        cacheDir = File(System.getProperty("java.io.tmpdir"), "ym-test").apply { mkdirs() },
    )

    @Test
    fun nonYandexUrlResolvesNull(): Unit = runBlocking {
        assertNull(client.resolve("https://open.spotify.com/track/123"))
    }

    @Test
    fun emptyTokenNotConfigured(): Unit = runBlocking {
        assertFalse(client.isConfigured())
    }
}
