package net.ripster.mobile.service.bbc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * BBC жёстко гео-локирован (UK), полноценно проверить скачивание из РФ/эмулятора
 * нельзя. Проверяем детерминированное: не-BBC ссылка → null, а битый pid
 * даёт чистый IOException, а не падение.
 */
class BbcClientTest {

    private val client = BbcClient(cacheDir = File(System.getProperty("java.io.tmpdir"), "bbc-test").apply { mkdirs() })

    @Test
    fun nonBbcUrlResolvesToNull(): Unit = runBlocking {
        assertNull(client.resolve("https://soundcloud.com/forss/flickermood"))
        assertNull(client.resolve("not a url"))
    }

    @Test
    fun brokenPidThrowsCleanIoException(): Unit = runBlocking {
        val ex = runCatching { client.resolve("https://www.bbc.co.uk/sounds/play/z0000000") }.exceptionOrNull()
        assertTrue("ожидали IOException, получили ${ex?.let { it::class.simpleName }}", ex is IOException)
    }
}
