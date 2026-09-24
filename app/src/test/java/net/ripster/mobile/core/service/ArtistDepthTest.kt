package net.ripster.mobile.core.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import net.ripster.mobile.core.model.DownloadEvent
import net.ripster.mobile.core.model.DownloadRequest
import net.ripster.mobile.core.model.MediaKind
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.StreamInfo
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Бюджет догрузки: «спрашиваем только то, что на экране» на измеримом языке.
 *
 * Deezer и Qobuz отдают полный состав отдельным запросом к карточке трека. Если
 * этот запрос начнётся с каждой строкой выдачи, поиск превратится в скрейп
 * каталога — поэтому у [ArtistDepth] есть кэш, очередь и потолок. Здесь
 * считается ровно это: сколько вызовов дошло до клиента.
 */
class ArtistDepthTest {

    /** Клиент-заглушка: отвечает что скажут, и считает свои вызовы. */
    private class Fake(
        override val service: Service,
        private val answer: (Track) -> Contributors?,
    ) : ServiceClient {
        var calls = 0
        override suspend fun isConfigured(): Boolean = true
        override suspend fun search(query: String): MediaSelection = MediaSelection(kind = MediaKind.TRACK)
        override suspend fun resolve(url: String): MediaSelection? = null
        override suspend fun qualities(): List<QualityTier> = emptyList()
        override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo =
            throw UnsupportedOperationException("тест не стримит")
        override fun download(request: DownloadRequest): Flow<DownloadEvent> = emptyFlow()
        override suspend fun contributorsFor(track: Track): Contributors? {
            calls++
            return answer(track)
        }
    }

    private fun track(service: Service, id: String, artist: String, title: String = "Song") =
        Track(id = id, title = title, artist = artist, service = service)

    private val collab = { t: Track -> Contributors(main = listOf(t.artist), featured = listOf("Foxes")) }

    @Test
    fun oneTrackIsAskedExactlyOncePerSession() = runBlocking {
        val client = Fake(Service.BBC, collab)
        val t = track(Service.BBC, "once-1", "Zedd")
        // Прокрутка списка гоняет ряд по кругу: второй раз в сеть идти нельзя.
        assertEquals("Zedd feat. Foxes", ArtistDepth.deeper(client, t))
        assertEquals("Zedd feat. Foxes", ArtistDepth.deeper(client, t))
        assertEquals(1, client.calls)
    }

    @Test
    fun alreadyJoinedLineIsNeverAsked() = runBlocking {
        // «A, B» — состав уже на строке; второй запрос ради «может, ещё кто-то»
        // бюджет съест, а человеку ничего не добавит.
        val client = Fake(Service.BBC, collab)
        assertNull(ArtistDepth.deeper(client, track(Service.BBC, "joined-1", "Art Department, Lane 8")))
        assertEquals(0, client.calls)
    }

    @Test
    fun poorerAnswerIsNeverShown() = runBlocking {
        // Клиент вернул то же одно имя (или пусто) — строка остаётся как была.
        val poor = Fake(Service.BBC) { t -> Contributors(main = listOf(t.artist)) }
        val t = track(Service.BBC, "poor-1", "Bicep")
        assertNull(ArtistDepth.deeper(poor, t))
        assertNull("бедный ответ не должен просочиться в кэш", ArtistDepth.cached(t))

        val empty = Fake(Service.BBC) { null }
        assertNull(ArtistDepth.deeper(empty, track(Service.BBC, "poor-2", "Bicep")))
    }

    @Test
    fun failureIsRememberedAndNotRetried() = runBlocking {
        val boom = Fake(Service.BBC) { throw IOException("Deezer API -> HTTP 429") }
        val t = track(Service.BBC, "boom-1", "Zedd")
        assertNull(ArtistDepth.deeper(boom, t))
        assertNull(ArtistDepth.deeper(boom, t))
        // Один трек = одна попытка: серию 429 догонянием не раздуть.
        assertEquals(1, boom.calls)
    }

    @Test
    fun sessionBudgetStopsAtTheCap() = runBlocking {
        val client = Fake(Service.SPOTIFY, collab)
        val shown = (1..60).map { track(Service.SPOTIFY, "budget-$it", "Solo Artist $it") }
        shown.forEach { ArtistDepth.deeper(client, it) }
        assertTrue(
            "потолок запросов на сервис должен держать прокрутку всего поиска: ${client.calls}",
            client.calls <= 40,
        )
        // Кто после потолка не спрошен — тот показан как есть, без вранья.
        assertNull(ArtistDepth.cached(shown.last()))
    }

    @Test
    fun cachedKnowsOnlyWhatWasAlreadyLoaded() = runBlocking {
        val client = Fake(Service.BBC, collab)
        val t = track(Service.BBC, "cache-1", "Zedd")
        assertNull(ArtistDepth.cached(t))
        ArtistDepth.deeper(client, t)
        assertEquals("Zedd feat. Foxes", ArtistDepth.cached(t))
    }

    @Test
    fun enrichRewritesOnlyTheArtistLine() = runBlocking {
        val client = Fake(Service.BBC, collab)
        val t = track(Service.BBC, "enrich-1", "Zedd", title = "Clarity")
        val deep = ArtistDepth.enrich(client, t)
        assertEquals("Zedd feat. Foxes", deep.artist)
        assertEquals("Clarity", deep.title)
        assertEquals(Service.BBC, deep.service)
    }
}

