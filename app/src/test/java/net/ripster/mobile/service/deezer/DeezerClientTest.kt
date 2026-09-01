package net.ripster.mobile.service.deezer

import kotlinx.coroutines.runBlocking
import net.ripster.mobile.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Что можно проверить без реального ARL: публичный поиск (`api.deezer.com`,
 * без авторизации), детерминизм ключа Blowfish, и что мёртвый ARL не роняет
 * `isConfigured()`. Полный скачивание+расшифровку проверяет владелец со
 * своим ARL — здесь этой ветки нет.
 */
class DeezerClientTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "dz-test").apply { mkdirs() }

    @Test
    fun blowfishKeyIsDeterministic16Bytes(): Unit {
        val a = DeezerCrypto.blowfishKey("3135556")
        val b = DeezerCrypto.blowfishKey("3135556")
        val c = DeezerCrypto.blowfishKey("999")
        assertEquals(16, a.size)
        assertArrayEqualsMsg(a, b)
        assertFalse("разные id → разные ключи", a.contentEquals(c))
    }

    @Test
    fun stripeLeavesShortTailAndNonThirdChunksUntouched(): Unit = runBlocking {
        // Кусок < 2048 Б целиком проходит как есть (не расшифровывается).
        val payload = ByteArray(1500) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        DeezerCrypto.decryptStream(
            ByteArrayInputStream(payload),
            out,
            DeezerCrypto.blowfishKey("42"),
            payload.size.toLong(),
        ) { _, _ -> }
        assertArrayEqualsMsg(payload, out.toByteArray())
    }

    @Test
    fun publicSearchWorksWithoutArl(): Unit = runBlocking {
        val client = DeezerClient(arl = "", cacheDir = cacheDir)
        val sel = client.search("Daft Punk Get Lucky")
        assertTrue("публичный поиск не должен быть пустым", sel.tracks.isNotEmpty())
        assertEquals(MediaKind.TRACK, sel.kind)
        val t = sel.tracks.first()
        assertTrue("у трека должен быть dzId", t.raw["dzId"]?.toLongOrNull() != null)
        assertTrue("артист заполнен", t.artist.isNotBlank())
    }

    @Test
    fun badArlDoesNotCrashIsConfigured(): Unit = runBlocking {
        val client = DeezerClient(arl = "deadbeef", cacheDir = cacheDir)
        // не бросает, просто false
        assertFalse(client.isConfigured())
    }

    private fun assertArrayEqualsMsg(a: ByteArray, b: ByteArray) =
        assertTrue("массивы должны совпадать", a.contentEquals(b))
}
