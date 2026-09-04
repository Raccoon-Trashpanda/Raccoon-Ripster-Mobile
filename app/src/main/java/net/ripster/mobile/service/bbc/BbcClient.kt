package net.ripster.mobile.service.bbc

import net.ripster.mobile.core.errors.EngineErrors
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
        val pl = playlist(pid)
        return MediaSelection(
            kind = MediaKind.TRACK,
            tracks = listOf(
                Track(
                    id = pid,
                    title = pl.title,
                    artist = "BBC",
                    service = Service.BBC,
                    durationMs = pl.durationMs,
                    artworkUrl = pl.artworkUrl,
                    raw = mapOf("vpid" to pl.vpid, "pid" to pid),
                ),
            ),
        )
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val vpid = track.raw["vpid"] ?: throw IOException("BBC: no vpid")
        // HLS-ссылку отдаём как есть: ExoPlayer её играет (media3-exoplayer-hls),
        // и отдельная обработка тут не нужна.
        return StreamInfo(url = mediaUrl(vpid).url, quality = mp3, headers = mapOf("User-Agent" to UA))
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val vpid = request.track.raw["vpid"] ?: throw IOException("BBC: no vpid")
        val media = mediaUrl(vpid)
        emit(DownloadEvent.Log("BBC: $mp3"))
        val out = java.io.File(cacheDir, "bbc_${request.track.id}." + if (media.hls) "aac" else "mp3")
        if (media.hls) {
            // Потоковый набор отдаёт HLS — собираем сегменты тем же сборщиком,
            // что и не-DRM SoundCloud. Прогресс считаем по сегментам: длину
            // целого файла HLS заранее не сообщает.
            net.ripster.mobile.service.soundcloud.HlsAssembler.assemble(media.url, out).collect { p ->
                emit(DownloadEvent.Progress(
                    p.segmentsTotal.takeIf { it > 0 }?.let { p.segmentsDone.toFloat() / it },
                    p.bytesWritten, null))
            }
            emit(DownloadEvent.Done(out.absolutePath, mp3, out.length()))
            return@flow
        }
        val req = Request.Builder().url(media.url).header("User-Agent", UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("BBC: stream -> HTTP ${resp.code}")
            val body = resp.body ?: throw IOException(EngineErrors.EMPTY_STREAM)
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

    private data class Pl(
        val title: String,
        val vpid: String,
        val durationMs: Long?,
        val artworkUrl: String?,
    )

    private suspend fun playlist(pid: String): Pl = withContext(Dispatchers.IO) {
        val raw = get("https://www.bbc.co.uk/programmes/$pid/playlist.json")
        val root = json.parseToJsonElement(raw).jsonObject
        // Названия на верхнем уровне НЕТ — раньше брали root["title"], его там не
        // бывает, и в плеере всегда стояло «BBC <pid>» вместо передачи (владелец
        // 04.09.2026: «у BBC нет метаданных»). Настоящие название и картинка
        // лежат в конфиге плеера версии и в holdingImage.
        val ver = root["defaultAvailableVersion"]?.jsonObject
            ?: root["allAvailableVersions"]?.jsonArray?.firstOrNull()?.jsonObject
        val title = ver?.get("smpConfig")?.jsonObject
            ?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "BBC $pid"
        // holdingImage приходит без схемы («//ichef.bbci.co.uk/...»): без неё
        // загрузчик обложек молча ничего не получит.
        val art = root["holdingImage"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        val versions = root["allAvailableVersions"]?.jsonArray
            ?: throw IOException(EngineErrors.EXPIRED_OR_GEO)
        val item = versions.firstNotNullOfOrNull { v ->
            v.jsonObject["smpConfig"]?.jsonObject?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
        } ?: throw IOException("BBC: no playable item")
        val vpid = item["vpid"]?.jsonPrimitive?.contentOrNull ?: throw IOException("BBC: no vpid in playlist")
        val dur = item["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.times(1000)
        Pl(title, vpid, dur, art)
    }

    /** Что отдала BBC: прямой файл или HLS-плейлист — качать их надо по-разному. */
    private data class Media(val url: String, val hls: Boolean)

    /**
     * Ссылка на звук передачи.
     *
     * Порядок mediaset'ов не случаен. Сначала спрашиваем «скачивательные»
     * (`audio-nondrm-download`) — они дают готовый mp3 одним файлом, это самый
     * дешёвый путь. Но за пределами Великобритании BBC отвечает на них 404, и
     * раньше на этом всё заканчивалось: передача не скачивалась И не игралась,
     * причём кнопка ▶ молчала — жалоба владельца 04.09.2026.
     *
     * При этом сам звук BBC отдаёт: те же выпуски доступны через потоковые
     * mediaset'ы (`audio-syndication` и `iptv-all`) — AAC 320 по HLS, проверено
     * на живом эпизоде из того же места, где download отвечал 404. Их и берём
     * вторым заходом: играет ExoPlayer штатно, скачивание собирает [HlsAssembler].
     *
     * `geolocation` в ответе по-прежнему прерывает перебор сразу — это отказ по
     * региону целиком, и следующий mediaset ответит тем же.
     */
    private suspend fun mediaUrl(vpid: String): Media = withContext(Dispatchers.IO) {
        val direct = listOf("audio-nondrm-download", "audio-nondrm-download-low")
        // `pc` — тот же набор, что годами использует ПК-версия
        // (ripster/routes/bbc.py `_MSEL`), поэтому он первый; остальные два
        // оставлены запасными, все три проверены живьём 04.09.2026.
        val streaming = listOf("pc", "audio-syndication", "iptv-all")
        for (mediaset in direct + streaming) {
            val wantHls = mediaset in streaming
            val raw = runCatching {
                get("https://open.live.bbc.co.uk/mediaselector/6/select/version/2.0/mediaset/$mediaset/vpid/$vpid/format/json")
            }.getOrNull() ?: continue
            val root = json.parseToJsonElement(raw).jsonObject
            root["result"]?.jsonPrimitive?.contentOrNull?.let { r ->
                if (r == "geolocation") throw IOException(EngineErrors.GEO_UK)
                if (r == "selectionunavailable") return@let
            }
            val media = root["media"]?.jsonArray ?: continue
            val audio = media.map { it.jsonObject }
                .filter {
                    // У потоковых наборов `kind` может отсутствовать — там всё
                    // и так аудио; фильтруем только когда поле есть.
                    val k = it["kind"]?.jsonPrimitive?.contentOrNull
                    k == null || k == "audio"
                }
                .maxByOrNull { it["bitrate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
                ?: continue
            val conns = audio["connection"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            // https предпочтительнее http: часть Android-сборок запрещает
            // открытый текст, и такая ссылка молча не откроется.
            // https раньше http (часть сборок запрещает открытый текст), а среди
            // равных — cloudfront раньше akamai: тот же порядок предпочтения, что
            // в ПК-версии.
            val href = conns.sortedWith(
                compareByDescending<kotlinx.serialization.json.JsonObject> {
                    it["protocol"]?.jsonPrimitive?.contentOrNull == "https"
                }.thenByDescending {
                    (it["supplier"]?.jsonPrimitive?.contentOrNull ?: "").contains("cloudfront")
                },
            )
                .firstOrNull { c ->
                    val p = c["protocol"]?.jsonPrimitive?.contentOrNull
                    val h = c["href"]?.jsonPrimitive?.contentOrNull ?: ""
                    val fmt = c["transferFormat"]?.jsonPrimitive?.contentOrNull
                    (p == "https" || p == "http") &&
                        if (wantHls) fmt == "hls" || h.contains(".m3u8") else h.contains(".mp3")
                }
                ?.get("href")?.jsonPrimitive?.contentOrNull
            if (href != null) return@withContext Media(href, wantHls)
        }
        throw IOException(EngineErrors.NO_AUDIO)
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
