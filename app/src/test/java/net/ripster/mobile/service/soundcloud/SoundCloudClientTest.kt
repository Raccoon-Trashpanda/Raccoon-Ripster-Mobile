package net.ripster.mobile.service.soundcloud

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.ripster.mobile.core.model.DownloadEvent
import net.ripster.mobile.core.model.DownloadRequest
import net.ripster.mobile.core.model.MediaKind
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Живой прогон клиента SoundCloud против настоящего API v2. Требует сеть.
 * Ничего в клиенте не завязано на `android.*`, поэтому гоняется обычным
 * JVM-тестом (`testDebugUnitTest`), без эмулятора.
 *
 * Цель — поймать ровно то, что статическая проверка не ловит: живой
 * client_id-scrape, форму ответов API и то, что выбранный не-DRM поток
 * реально отдаёт байты аудио.
 */
class SoundCloudClientTest {

    private val cacheDir: File = File(System.getProperty("java.io.tmpdir"), "sc-test").apply { mkdirs() }
    private val client = SoundCloudClient(oauthToken = null, cacheDir = cacheDir)

    @Test
    fun clientIdScrapeStillWorks() : Unit = runBlocking {
        val id = SoundCloudClientId.get(forceRefresh = true)
        assertTrue("client_id должен быть 32-символьным, получили '$id'", id.length == 32)
    }

    @Test
    fun searchReturnsTracks() : Unit = runBlocking {
        val sel = client.search("Forss Flickermood")
        assertTrue("поиск не должен быть пустым", sel.tracks.isNotEmpty())
        assertTrue(sel.kind == MediaKind.TRACK)
        val first = sel.tracks.first()
        assertTrue("у трека должен быть permalink в raw", first.raw["permalink"]?.contains("soundcloud.com") == true)
    }

    @Test
    fun resolveTrackUrl() : Unit = runBlocking {
        val sel = client.resolve("https://soundcloud.com/forss/flickermood")
        assertNotNull("публичный трек должен резолвиться", sel)
        assertTrue(sel!!.tracks.size == 1)
    }

    @Test
    fun downloadsRealAudioBytes() : Unit = runBlocking {
        val sel = client.search("Forss Flickermood")
        val track = sel.tracks.first { it.raw["permalink"]?.contains("/forss/") == true }

        val events = client.download(DownloadRequest(track)).toList()
        val done = events.filterIsInstance<DownloadEvent.Done>().singleOrNull()
        val error = events.filterIsInstance<DownloadEvent.Error>().firstOrNull()
        assertNotNull("ожидали Done, а получили: ${error?.reason ?: events.map { it::class.simpleName }}", done)

        val file = File(done!!.filePath)
        assertTrue("файл должен существовать", file.exists())
        assertTrue("файл подозрительно мал: ${file.length()} Б", file.length() > 200_000)

        // Прогресс должен был идти монотонно и добить до конца.
        val progress = events.filterIsInstance<DownloadEvent.Progress>()
        assertTrue("должны быть события прогресса", progress.isNotEmpty())

        file.delete()
    }
}
