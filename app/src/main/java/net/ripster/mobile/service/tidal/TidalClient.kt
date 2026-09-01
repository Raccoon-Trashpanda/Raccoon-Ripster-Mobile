package net.ripster.mobile.service.tidal

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.model.Album
import net.ripster.mobile.core.model.Artist
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Клиент Tidal. Аутентификация — device-flow ([TidalAuth]); хранимый
 * credential `TIDAL_OAUTH` = JSON `{refreshToken, countryCode}`.
 *
 * В объёме: потоки `application/vnd.tidal.bts` (LOSSLESS FLAC / HIGH AAC /
 * LOW) — прямые URL, без расшифровки. HI_RES через MPEG-DASH пока НЕ
 * поддержан (нужен парсер SegmentTemplate) — для таких треков откат на
 * LOSSLESS.
 */
class TidalClient(
    storedJson: String,
    private val cacheDir: File,
) : ServiceClient {

    override val service = Service.TIDAL
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val stored = TidalAuth.decodeStored(storedJson)
    private val mutex = Mutex()
    @Volatile private var accessToken: String = ""
    // Страна аккаунта. Дефолт из синка с ПК, НО может быть протухшим/US —
    // а от неё зависит, какой каталог отдаёт Tidal (жалоба: NZ-аккаунт,
    // релиз уже вышел в NZ, поиск его не находит). ensureToken() обновляет
    // её из ответа refresh и из JWT access-токена.
    @Volatile private var cc: String = stored?.countryCode?.takeIf { it.length == 2 } ?: "US"

    private fun ccFromJwt(jwt: String): String? = runCatching {
        val payload = jwt.split(".").getOrNull(1) ?: return null
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        Regex("\"cc\"\\s*:\\s*\"([A-Z]{2})\"").find(String(bytes))?.groupValues?.get(1)
    }.getOrNull()

    private val flac = QualityTier("flac_16", "FLAC (Lossless)", lossless = true, container = "flac", bitDepth = 16, sampleRateHz = 44100)
    private val aac = QualityTier("aac_256", "AAC 320", lossless = false, container = "m4a", bitrateKbps = 320)
    private val low = QualityTier("mp3_128", "AAC 96", lossless = false, container = "m4a", bitrateKbps = 96)

    // Есть сохранённая сессия — считаем готовым; ensureToken() (сеть) уедет в
    // первый search()/resolve(), а не в пробу готовности.
    override suspend fun isConfigured(): Boolean = stored != null

    override suspend fun qualities(): List<QualityTier> = listOf(flac, aac, low)

    override suspend fun search(query: String): MediaSelection {
        ensureToken()
        val raw = api("https://api.tidal.com/v1/search/tracks") {
            it.addQueryParameter("query", query); it.addQueryParameter("limit", "25")
        }
        val items = json.decodeFromString(TdItems.serializer(), raw).items
        return MediaSelection(kind = MediaKind.TRACK, tracks = items.map { it.toTrack() })
    }

    override suspend fun resolve(url: String): MediaSelection? {
        val m = Regex("""tidal\.com/(?:browse/)?(track|album)/(\d+)""").find(url) ?: return null
        ensureToken()
        val (kind, id) = m.destructured
        return when (kind) {
            "track" -> MediaSelection(
                kind = MediaKind.TRACK,
                tracks = listOf(json.decodeFromString(TdTrack.serializer(), api("https://api.tidal.com/v1/tracks/$id") {}).toTrack()),
            )
            "album" -> {
                val a = json.decodeFromString(TdAlbum.serializer(), api("https://api.tidal.com/v1/albums/$id") {})
                val tracks = json.decodeFromString(
                    TdItems.serializer(),
                    api("https://api.tidal.com/v1/albums/$id/tracks") { it.addQueryParameter("limit", "100") },
                ).items
                MediaSelection(
                    kind = MediaKind.ALBUM,
                    containerTitle = a.title,
                    tracks = tracks.map { it.toTrack() },
                    albums = listOf(Album(a.id.toString(), a.title, a.artist?.name ?: "", Service.TIDAL,
                        trackCount = a.numberOfTracks, artworkUrl = coverUrl(a.cover))),
                    artists = listOfNotNull(a.artist?.let { Artist(it.id.toString(), it.name, Service.TIDAL) }),
                )
            }
            else -> null
        }
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val id = track.raw["tdId"] ?: throw IOException("Tidal: no track id")
        var s = resolveStream(id, preference)
        if (s is TdStream.Dash) {
            // Потоковое воспроизведение НЕ тянет DASH (у нас только init-сегмент
            // без media-сегментов → ExoPlayer: «malformed content»). Для стрима
            // берём ПРЯМОЙ URL: LOSSLESS FLAC, иначе HIGH AAC. HI-RES остаётся
            // только для скачивания (там сегменты склеиваются в файл).
            val direct = runCatching { resolveStream(id, listOf("lossless_direct")) }.getOrNull()
            if (direct is TdStream.Direct) s = direct
        }
        return when (s) {
            is TdStream.Direct -> StreamInfo(url = s.url, quality = s.tier)
            is TdStream.Dash -> StreamInfo(url = s.initUrl, quality = s.tier)
        }
    }

    private sealed interface TdStream {
        data class Direct(val tier: QualityTier, val url: String) : TdStream
        data class Dash(val tier: QualityTier, val initUrl: String, val mediaUrls: List<String>) : TdStream
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val id = request.track.raw["tdId"] ?: throw IOException("Tidal: no track id")
        val preference = request.forcedQualityId?.let { listOf(it) } ?: request.qualityPreference
        when (val s = resolveStream(id, preference)) {
            is TdStream.Direct -> {
                emit(DownloadEvent.Log("Tidal: ${s.tier.label}"))
                val out = File(cacheDir, "td_$id.${s.tier.container}")
                streamTo(s.url, out) { got, tot -> emit(DownloadEvent.Progress(tot?.let { got.toFloat() / it }, got, tot)) }
                emit(DownloadEvent.Done(out.absolutePath, s.tier, out.length()))
            }
            is TdStream.Dash -> {
                // HI_RES / lossless-в-DASH: init-сегмент + все media-сегменты
                // склеиваются в один fMP4 (.m4a) — играбельный файл.
                emit(DownloadEvent.Log("Tidal: ${s.tier.label} (DASH, ${s.mediaUrls.size} сегм.)"))
                val out = File(cacheDir, "td_$id.${s.tier.container}")
                out.outputStream().buffered().use { sink ->
                    streamAppend(s.initUrl, sink)
                    s.mediaUrls.forEachIndexed { i, u ->
                        currentCoroutineContext().ensureActive()
                        streamAppend(u, sink)
                        emit(DownloadEvent.Progress((i + 1).toFloat() / s.mediaUrls.size, (i + 1).toLong(), s.mediaUrls.size.toLong()))
                    }
                }
                emit(DownloadEvent.Done(out.absolutePath, s.tier, out.length()))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamTo(url: String, out: File, onProg: suspend (Long, Long?) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "RipsterMobile/0.1").build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Tidal: stream -> HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Tidal: empty stream body")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                out.outputStream().buffered().use { sink ->
                    val buf = ByteArray(64 * 1024); var got = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        sink.write(buf, 0, n); got += n; onProg(got, total)
                    }
                }
            }
        }
    }

    private fun streamAppend(url: String, sink: java.io.OutputStream) {
        val req = Request.Builder().url(url).header("User-Agent", "RipsterMobile/0.1").build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Tidal: segment -> HTTP ${resp.code}")
            resp.body?.byteStream()?.use { it.copyTo(sink) } ?: throw IOException("Tidal: empty segment")
        }
    }

    // --- внутреннее ---

    private suspend fun ensureToken(): Boolean = mutex.withLock {
        if (accessToken.isNotBlank()) return true
        val s = stored ?: return false
        // 1) живой access-токен из синка с ПК — если ещё не истёк, берём как есть
        if (s.accessToken.isNotBlank() && !jwtExpired(s.accessToken)) {
            accessToken = s.accessToken
            ccFromJwt(s.accessToken)?.let { cc = it }
            return true
        }
        // 2) обновить по refresh — сработает только если он от того же client_id.
        //    Заодно берём АКТУАЛЬНУЮ страну аккаунта из ответа (в Stored она
        //    могла остаться US с момента синка).
        val rt = s.refreshToken.takeIf { it.isNotBlank() }
        if (rt != null) {
            val fresh = runCatching { TidalAuth.refresh(rt) }.getOrNull()
            accessToken = fresh?.accessToken.orEmpty()
            fresh?.user?.countryCode?.takeIf { it.length == 2 }?.let { cc = it }
            if (accessToken.isNotBlank()) {
                ccFromJwt(accessToken)?.let { cc = it }
                return true
            }
        }
        // 3) фолбэк на просроченный access — вдруг ещё пустят; иначе честная ошибка
        if (s.accessToken.isNotBlank()) { accessToken = s.accessToken; return true }
        throw IOException("Tidal: токен истёк — открой «Забрать учётки с ПК» в сопряжении")
    }

    /** exp из JWT в прошлом (с запасом 60с)? */
    private fun jwtExpired(jwt: String): Boolean = runCatching {
        val payload = jwt.split(".").getOrNull(1) ?: return true
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val exp = Regex("\"exp\"\\s*:\\s*(\\d+)").find(String(bytes))?.groupValues?.get(1)?.toLongOrNull()
            ?: return true
        exp <= System.currentTimeMillis() / 1000 + 60
    }.getOrDefault(true)

    private val hires = QualityTier("flac_24", "FLAC Hi-Res", lossless = true, container = "m4a", bitDepth = 24)

    private suspend fun resolveStream(id: String, preference: List<String>): TdStream {
        if (!ensureToken()) throw IOException("Tidal: not authorized (re-login in Accounts)")
        val order = buildList {
            for (p in preference) when {
                // спец-режим для стрима: только прямые URL, без HI-RES/DASH
                p == "lossless_direct" -> { add("LOSSLESS" to flac); add("HIGH" to aac) }
                p.startsWith("flac_24") || p.contains("hires") || p.contains("hi_res") -> add("HI_RES_LOSSLESS" to hires)
                p.startsWith("flac") -> { add("HI_RES_LOSSLESS" to hires); add("LOSSLESS" to flac) }
                p == "mp3_320" || p == "aac_256" -> add("HIGH" to aac)
                p == "mp3_128" -> add("LOW" to low)
            }
            if (isEmpty()) { add("HI_RES_LOSSLESS" to hires); add("LOSSLESS" to flac); add("HIGH" to aac) }
        }.distinctBy { it.first }

        for ((q, tier) in order) {
            val pb = runCatching {
                json.decodeFromString(
                    TdPlayback.serializer(),
                    api("https://api.tidal.com/v1/tracks/$id/playbackinfopostpaywall") {
                        it.addQueryParameter("audioquality", q)
                        it.addQueryParameter("playbackmode", "STREAM")
                        it.addQueryParameter("assetpresentation", "FULL")
                    },
                )
            }.getOrNull() ?: continue

            val decoded = String(Base64.decode(pb.manifest, Base64.DEFAULT), Charsets.UTF_8)
            when {
                pb.manifestMimeType == "application/vnd.tidal.bts" -> {
                    val url = json.decodeFromString(TdBts.serializer(), decoded).urls.firstOrNull() ?: continue
                    val realTier = if (decoded.contains("flac", true)) flac else tier
                    return TdStream.Direct(realTier, url)
                }
                pb.manifestMimeType == "application/dash+xml" || decoded.contains("<MPD") -> {
                    val dash = parseDash(decoded) ?: continue
                    val t = when {
                        decoded.contains("flac", true) && q.startsWith("HI_RES") -> hires
                        decoded.contains("flac", true) -> flac
                        else -> tier
                    }
                    return TdStream.Dash(t, dash.first, dash.second)
                }
            }
        }
        throw IOException("Tidal: не удалось получить поток для этого трека")
    }

    /** MPD (SegmentTemplate + SegmentTimeline или duration) → (initUrl, [mediaUrls]). */
    private fun parseDash(mpd: String): Pair<String, List<String>>? = runCatching {
        // одиночный <BaseURL> — цельный файл
        Regex("<BaseURL>(.*?)</BaseURL>", RegexOption.DOT_MATCHES_ALL).find(mpd)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.startsWith("http") }?.let { return@runCatching it to emptyList<String>() }

        val repId = Regex("""<Representation[^>]*\bid="([^"]+)"""").find(mpd)?.groupValues?.get(1) ?: "0"
        val st = Regex("<SegmentTemplate[^>]*>", RegexOption.DOT_MATCHES_ALL).find(mpd)?.value
            ?: Regex("<SegmentTemplate[^/]*/>").find(mpd)?.value ?: return@runCatching null
        fun attr(n: String) = Regex("""\b$n="([^"]+)"""").find(st)?.groupValues?.get(1)
        val initT = (attr("initialization") ?: return@runCatching null).replace("\$RepresentationID\$", repId)
        val mediaT = (attr("media") ?: return@runCatching null)
        val startNumber = attr("startNumber")?.toIntOrNull() ?: 1

        val count: Int = run {
            val tl = Regex("<SegmentTimeline>(.*?)</SegmentTimeline>", RegexOption.DOT_MATCHES_ALL).find(mpd)?.groupValues?.get(1)
            if (tl != null) {
                var n = 0
                Regex("<S\\b[^>]*>").findAll(tl).forEach { s ->
                    val r = Regex("""\br="(\d+)"""").find(s.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    n += 1 + r
                }
                n
            } else {
                val segDur = attr("duration")?.toDoubleOrNull() ?: return@runCatching null
                val timescale = attr("timescale")?.toDoubleOrNull() ?: 1.0
                val totalSec = Regex("""mediaPresentationDuration="PT([\d.]+)S"""").find(mpd)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?: return@runCatching null
                Math.ceil(totalSec / (segDur / timescale)).toInt()
            }
        }
        val media = (startNumber until startNumber + count).map { num ->
            mediaT.replace("\$RepresentationID\$", repId).replace("\$Number\$", num.toString())
        }
        initT to media
    }.getOrNull()

    private suspend fun api(base: String, params: (okhttp3.HttpUrl.Builder) -> Unit): String {
        val url = base.toHttpUrl().newBuilder().apply(params).addQueryParameter("countryCode", cc).build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        return withContext(Dispatchers.IO) {
            RipsterHttp.client.newCall(req).execute().use { r ->
                if (r.code == 401) { accessToken = ""; throw IOException("Tidal: 401 (token expired)") }
                if (!r.isSuccessful) throw IOException("Tidal ${url.encodedPath} -> HTTP ${r.code}")
                r.body?.string() ?: throw IOException("Tidal ${url.encodedPath} -> empty")
            }
        }
    }

    private fun coverUrl(cover: String?): String? =
        cover?.takeIf { it.isNotBlank() }?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }

    private fun TdTrack.toTrack(): Track {
        val art = (artists?.joinToString(", ") { it.name }).orEmpty().ifBlank { artist?.name ?: "" }
        return Track(
            id = id.toString(),
            title = title,
            artist = art,
            service = Service.TIDAL,
            albumTitle = album?.title,
            durationMs = duration.takeIf { it > 0 }?.times(1000),
            trackNumber = trackNumber,
            discNumber = volumeNumber,
            isrc = isrc,
            year = streamStartDate?.take(4)?.toIntOrNull(),
            artworkUrl = coverUrl(album?.cover),
            raw = mapOf("tdId" to id.toString(), "albId" to (album?.id?.toString() ?: "")),
        )
    }

    // --- DTO ---

    @Serializable private data class TdItems(val items: List<TdTrack> = emptyList())
    @Serializable private data class TdArtist(val id: Long = 0, val name: String = "")
    @Serializable private data class TdAlbumRef(val id: Long = 0, val title: String = "", val cover: String? = null)
    @Serializable
    private data class TdTrack(
        val id: Long = 0,
        val title: String = "",
        val duration: Long = 0,
        val isrc: String? = null,
        val trackNumber: Int? = null,
        val volumeNumber: Int? = null,
        @SerialName("streamStartDate") val streamStartDate: String? = null,
        val artist: TdArtist? = null,
        val artists: List<TdArtist>? = null,
        val album: TdAlbumRef? = null,
    )
    @Serializable
    private data class TdAlbum(
        val id: Long = 0,
        val title: String = "",
        val cover: String? = null,
        val numberOfTracks: Int? = null,
        val artist: TdArtist? = null,
    )
    @Serializable
    private data class TdPlayback(
        val trackId: Long = 0,
        val audioQuality: String = "",
        val manifestMimeType: String = "",
        val manifest: String = "",
    )
    @Serializable private data class TdBts(val mimeType: String = "", val urls: List<String> = emptyList())
}
