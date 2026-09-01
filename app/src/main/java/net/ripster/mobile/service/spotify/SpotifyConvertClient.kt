package net.ripster.mobile.service.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.ripster.mobile.core.model.DownloadEvent
import net.ripster.mobile.core.model.DownloadRequest
import net.ripster.mobile.core.model.MediaKind
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.StreamInfo
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.core.service.ServiceClient
import net.ripster.mobile.core.service.ServiceRegistry
import okhttp3.Request
import java.io.IOException

/**
 * Spotify — только КОНВЕРСИЯ, и БЕЗ хождения в `api.spotify.com`.
 *
 * Урок ПК-версии (spotify.py, 2026-07-19): `api.spotify.com/v1` для наших
 * веб-токенов **перманентно 429-банит**. Безопасный путь — не трогать API
 * вообще: метаданные трека берём из страницы эмбеда
 * (`open.spotify.com/embed/track/<id>`, блок `__NEXT_DATA__`) — это обычный
 * HTML, не rate-limited эндпоинт. На 429/403 от Spotify — короткий кулдаун
 * (≤2 мин, НИКОГДА не часы), запросы просто замолкают.
 *
 * Поиска нет: Spotify без API не ищет ban-safe. Работает по ссылке, как BBC.
 * Загрузка: ISRC (из эмбеда) → Deezer `track/isrc:<ISRC>`, иначе поиск в
 * Deezer/Qobuz по «артист — название».
 */
class SpotifyConvertClient : ServiceClient {

    override val service = Service.SPOTIFY
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun isConfigured(): Boolean =
        ServiceRegistry.get(Service.DEEZER) != null || ServiceRegistry.get(Service.QOBUZ) != null

    override suspend fun qualities(): List<QualityTier> =
        (ServiceRegistry.get(Service.DEEZER) ?: ServiceRegistry.get(Service.QOBUZ))?.qualities()
            ?: listOf(QualityTier("flac", "FLAC", true, "flac"))

    // Spotify без API не ищет ban-safe — только по ссылке.
    override suspend fun search(query: String): MediaSelection =
        MediaSelection(kind = MediaKind.TRACK, tracks = emptyList())

    override suspend fun resolve(url: String): MediaSelection? {
        val m = Regex("""open\.spotify\.com/(?:embed/)?(track|album)/([A-Za-z0-9]+)""").find(url) ?: return null
        val (kind, id) = m.destructured
        if (cooldownActive()) throw IOException("Spotify: пауза после лимита, попробуй позже")
        return when (kind) {
            "track" -> {
                val t = embedTrack(id) ?: return null
                MediaSelection(kind = MediaKind.TRACK, tracks = listOf(t))
            }
            "album" -> {
                val tracks = embedAlbum(id)
                if (tracks.isEmpty()) null
                else MediaSelection(kind = MediaKind.ALBUM, tracks = tracks)
            }
            else -> null
        }
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val (client, mapped) = convert(track) ?: throw IOException("Spotify: no match on a downloading service")
        return client.streamInfo(mapped, preference)
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val (client, mapped) = convert(request.track)
            ?: run {
                emit(DownloadEvent.Error("Spotify: не нашёл этот трек на Deezer/Qobuz по ISRC/имени"))
                return@flow
            }
        emit(DownloadEvent.Log("Spotify → ${mapped.service.label} (ISRC ${request.track.isrc ?: "?"})"))
        emitAll(client.download(request.copy(track = mapped)))
    }

    // ── конверсия в реально качающий сервис ──────────────────────────────

    private suspend fun convert(sp: Track): Pair<ServiceClient, Track>? {
        val isrc = sp.isrc?.trim()?.takeIf { it.isNotBlank() }
        ServiceRegistry.get(Service.DEEZER)?.let { dz ->
            if (isrc != null) {
                runCatching { deezerByIsrc(isrc) }.getOrNull()?.let { return dz to it }
            }
            runCatching { dz.search("${sp.artist} ${sp.title}").tracks.firstOrNull() }.getOrNull()
                ?.let { return dz to it }
        }
        ServiceRegistry.get(Service.QOBUZ)?.let { qb ->
            val hits = runCatching { qb.search("${sp.artist} ${sp.title}").tracks }.getOrDefault(emptyList())
            (hits.firstOrNull { isrc != null && it.isrc == isrc } ?: hits.firstOrNull())?.let { return qb to it }
        }
        return null
    }

    private suspend fun deezerByIsrc(isrc: String): Track? = withContext(Dispatchers.IO) {
        val raw = httpGet("https://api.deezer.com/2.0/track/isrc:$isrc")
        val o = json.parseToJsonElement(raw).jsonObject
        val dzId = o["id"]?.jsonPrimitive?.longOrNull ?: return@withContext null
        Track(
            id = dzId.toString(),
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@withContext null,
            artist = o["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            service = Service.DEEZER,
            isrc = isrc,
            raw = mapOf("dzId" to dzId.toString()),
        )
    }

    // ── эмбед-страница Spotify (не API, не банится) ──────────────────────

    private suspend fun embedTrack(id: String): Track? = withContext(Dispatchers.IO) {
        val data = nextData("https://open.spotify.com/embed/track/$id") ?: return@withContext null
        val e = data["entity"]?.jsonObject ?: locateEntity(data) ?: return@withContext null
        e.toTrack(id)
    }

    private suspend fun embedAlbum(id: String): List<Track> = withContext(Dispatchers.IO) {
        val data = nextData("https://open.spotify.com/embed/album/$id") ?: return@withContext emptyList()
        val ent = data["entity"]?.jsonObject ?: locateEntity(data) ?: return@withContext emptyList()
        val list = ent["trackList"]?.jsonArray ?: ent["tracks"]?.jsonObject?.get("items")?.jsonArray
            ?: return@withContext emptyList()
        list.mapNotNull { it.jsonObject.toTrack(it.jsonObject["uri"]?.jsonPrimitive?.contentOrNull?.substringAfterLast(':') ?: "") }
    }

    /** `__NEXT_DATA__` JSON из HTML эмбеда → props.pageProps.state.data (или .entity). */
    private suspend fun nextData(url: String): kotlinx.serialization.json.JsonObject? = runCatching {
        val html = httpGet(url)
        val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val end = html.indexOf("</script>", start).takeIf { it >= 0 } ?: return null
        val root = json.parseToJsonElement(html.substring(start, end)).jsonObject
        var node: kotlinx.serialization.json.JsonObject? = root
        for (k in listOf("props", "pageProps", "state", "data")) {
            node = node?.get(k)?.jsonObject ?: node
        }
        node
    }.getOrNull()

    private fun locateEntity(o: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject? {
        o["entity"]?.jsonObject?.let { return it }
        for ((_, v) in o) {
            (v as? kotlinx.serialization.json.JsonObject)?.let { locateEntity(it)?.let { e -> return e } }
        }
        return null
    }

    private fun kotlinx.serialization.json.JsonObject.toTrack(fallbackId: String): Track? {
        val title = this["name"]?.jsonPrimitive?.contentOrNull
            ?: this["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = this["artists"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }?.joinToString(", ")
            ?: this["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val isrc = this["isrc"]?.jsonPrimitive?.contentOrNull
            ?: this["externalIds"]?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull
        val art = this["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.lastOrNull()
            ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: this["visualIdentity"]?.jsonObject?.get("image")?.jsonArray?.lastOrNull()
                ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val durMs = this["duration"]?.jsonPrimitive?.longOrNull
            ?: this["durationMs"]?.jsonPrimitive?.longOrNull
        val id = this["id"]?.jsonPrimitive?.contentOrNull
            ?: this["uri"]?.jsonPrimitive?.contentOrNull?.substringAfterLast(':')
            ?: fallbackId
        return Track(
            id = id,
            title = title,
            artist = artist,
            service = Service.SPOTIFY,
            durationMs = durMs,
            artworkUrl = art,
            isrc = isrc,
            raw = buildMap {
                put("spotifyUrl", "https://open.spotify.com/track/$id")
                isrc?.let { put("isrc", it) }
            },
        )
    }

    // ── HTTP с кулдауном на 429/403 (ban-safe) ──────────────────────────

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "en")
            .build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (r.code == 429 || r.code == 403) {
                val retry = r.header("Retry-After")?.toLongOrNull()?.coerceAtMost(120L) ?: 60L
                bannedUntil = System.currentTimeMillis() + retry * 1000
                throw IOException("Spotify: лимит (${r.code}), пауза ${retry}s")
            }
            if (!r.isSuccessful) throw IOException("GET ${url.take(60)} -> HTTP ${r.code}")
            r.body?.string() ?: throw IOException("GET -> empty")
        }
    }

    private fun cooldownActive() = System.currentTimeMillis() < bannedUntil

    private companion object {
        // Обычный десктопный Chrome UA — эмбед отдаёт полный __NEXT_DATA__.
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        @Volatile var bannedUntil: Long = 0
    }
}
