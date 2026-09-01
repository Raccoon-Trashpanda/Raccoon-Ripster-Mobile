package net.ripster.mobile.service.bbc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import okhttp3.Request
import java.io.IOException

/**
 * BBC Sounds / iPlayer Radio. Поиска нет — работает по ссылке на передачу
 * (`bbc.co.uk/sounds/play/<pid>` или `/programmes/<pid>`).
 *
 *  1. `playlist.json` по pid → `vpid` версии + заголовок.
 *  2. MediaSelector (`open.live.bbc.co.uk`) для vpid, mediaset
 *     `audio-nondrm-download` → прямой MP3, без DRM и без склейки HLS.
 *
 * Гео-ограничение: MediaSelector отдаёт `result=geolocation` вне UK —
 * возвращаем честную ошибку про UK-прокси.
 */
class BbcClient(private val cacheDir: java.io.File) : ServiceClient {

    override val service = Service.BBC
    private val json = Json { ignoreUnknownKeys = true }

    private val mp3 = QualityTier("mp3_128", "MP3 128", lossless = false, container = "mp3", bitrateKbps = 128)

    override suspend fun isConfigured(): Boolean = true  // аккаунт не нужен для nondrm-download

    override suspend fun qualities(): List<QualityTier> = listOf(mp3)

    override suspend fun search(query: String): MediaSelection = MediaSelection(kind = MediaKind.TRACK)

    override suspend fun resolve(url: String): MediaSelection? {
        val pid = PID.find(url)?.groupValues?.get(1) ?: return null
        val (title, vpid, durationMs) = playlist(pid)
        return MediaSelection(
            kind = MediaKind.TRACK,
            tracks = listOf(
                Track(
                    id = pid,
                    title = title,
                    artist = "BBC",
                    service = Service.BBC,
                    durationMs = durationMs,
                    raw = mapOf("vpid" to vpid, "pid" to pid),
                ),
            ),
        )
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val vpid = track.raw["vpid"] ?: throw IOException("BBC: no vpid")
        return StreamInfo(url = mediaUrl(vpid), quality = mp3, headers = mapOf("User-Agent" to UA))
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val vpid = request.track.raw["vpid"] ?: throw IOException("BBC: no vpid")
        val url = mediaUrl(vpid)
        emit(DownloadEvent.Log("BBC: $mp3"))
        val out = java.io.File(cacheDir, "bbc_${request.track.id}.mp3")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("BBC: stream -> HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("BBC: empty stream body")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                out.outputStream().buffered().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        got += n
                        emit(DownloadEvent.Progress(total?.let { got.toFloat() / it }, got, total))
                    }
                }
            }
        }
        emit(DownloadEvent.Done(out.absolutePath, mp3, out.length()))
    }.flowOn(Dispatchers.IO)

    // --- внутреннее ---

    private data class Pl(val title: String, val vpid: String, val durationMs: Long?)

    private suspend fun playlist(pid: String): Pl = withContext(Dispatchers.IO) {
        val raw = get("https://www.bbc.co.uk/programmes/$pid/playlist.json")
        val root = json.parseToJsonElement(raw).jsonObject
        val title = root["title"]?.jsonPrimitive?.contentOrNull ?: "BBC $pid"
        val versions = root["allAvailableVersions"]?.jsonArray
            ?: throw IOException("BBC: no available versions (expired or region-locked)")
        val item = versions.firstNotNullOfOrNull { v ->
            v.jsonObject["smpConfig"]?.jsonObject?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
        } ?: throw IOException("BBC: no playable item")
        val vpid = item["vpid"]?.jsonPrimitive?.contentOrNull ?: throw IOException("BBC: no vpid in playlist")
        val dur = item["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.times(1000)
        Pl(title, vpid, dur)
    }

    private suspend fun mediaUrl(vpid: String): String = withContext(Dispatchers.IO) {
        for (mediaset in listOf("audio-nondrm-download", "audio-nondrm-download-low")) {
            val raw = runCatching {
                get("https://open.live.bbc.co.uk/mediaselector/6/select/version/2.0/mediaset/$mediaset/vpid/$vpid/format/json")
            }.getOrNull() ?: continue
            val root = json.parseToJsonElement(raw).jsonObject
            root["result"]?.jsonPrimitive?.contentOrNull?.let { r ->
                if (r == "geolocation") throw IOException("BBC: доступно только из Великобритании — нужен UK-прокси/VPN")
                if (r == "selectionunavailable") return@let
            }
            val media = root["media"]?.jsonArray ?: continue
            val audio = media.map { it.jsonObject }
                .filter { it["kind"]?.jsonPrimitive?.contentOrNull == "audio" }
                .maxByOrNull { it["bitrate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
                ?: continue
            val href = audio["connection"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { c ->
                    val p = c["protocol"]?.jsonPrimitive?.contentOrNull
                    val h = c["href"]?.jsonPrimitive?.contentOrNull ?: ""
                    (p == "https" || p == "http") && h.contains(".mp3")
                }
                ?.get("href")?.jsonPrimitive?.contentOrNull
            if (href != null) return@withContext href
        }
        throw IOException("BBC: no downloadable audio for this programme")
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("BBC GET $url -> HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("BBC GET $url -> empty")
        }
    }

    companion object {
        private val PID = Regex("""(?:sounds/play|programmes)/([a-z0-9]{8,})""")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
