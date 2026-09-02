package net.ripster.mobile.service.deezer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import net.ripster.mobile.service.deezer.dto.DzApiAlbumFull
import net.ripster.mobile.service.deezer.dto.DzApiSearch
import net.ripster.mobile.service.deezer.dto.DzApiTrack
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Клиент Deezer. Поиск и метаданные — публичный `api.deezer.com` (без ARL);
 * скачивание — приватный путь через [DeezerGw] (ARL → track_token → media
 * URL → Blowfish-stripe расшифровка в [DeezerCrypto]).
 */
class DeezerClient(
    private val arl: String,
    private val cacheDir: File,
) : ServiceClient {

    override val service = Service.DEEZER

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val gw by lazy { DeezerGw(arl) }

    private val flac = QualityTier("flac", "FLAC", lossless = true, container = "flac", bitDepth = 16, sampleRateHz = 44100)
    private val mp3_320 = QualityTier("mp3_320", "MP3 320", lossless = false, container = "mp3", bitrateKbps = 320)
    private val mp3_128 = QualityTier("mp3_128", "MP3 128", lossless = false, container = "mp3", bitrateKbps = 128)

    // Дешёвая проверка: есть ли ARL. Живой вход (gw.ensureSession) НЕ здесь —
    // раньше он держал экран поиска в «Проверяю сервисы…» на сетевом вызове,
    // а протухший ARL и так всплывёт понятной ошибкой при самом search().
    override suspend fun isConfigured(): Boolean = arl.isNotBlank()

    override suspend fun qualities(): List<QualityTier> = listOf(flac, mp3_320, mp3_128)

    override suspend fun search(query: String): MediaSelection {
        val res = apiGet("https://api.deezer.com/search/track") {
            it.addQueryParameter("q", query); it.addQueryParameter("limit", "25")
        }
        val pub = json.decodeFromString(DzApiSearch.serializer(), res).data.map { it.toTrack() }

        // Публичный /search геолоцирует по IP запроса и не находит релиз, если
        // его ещё нет в каталоге этой территории (жалоба: турецкий ARL не видит
        // релиз Maceo Plex, британский видит). Когда есть живой ARL — добираем
        // выдачу из gw-light в стране аккаунта и мержим (дедуп по id).
        val merged = if (arl.isNotBlank()) {
            runCatching {
                if (gw.ensureSession()) parseGwTracks(gw.searchTracksRaw(query, 25)) else emptyList()
            }.getOrDefault(emptyList())
        } else emptyList()

        val byId = LinkedHashMap<String, Track>()
        for (t in pub) byId[t.id] = t
        for (t in merged) byId.putIfAbsent(t.id, t)
        return MediaSelection(kind = MediaKind.TRACK, tracks = byId.values.toList())
    }

    /** `deezer.pageSearch` → results.TRACK.data[] (UPPER_SNAKE-поля). */
    private fun parseGwTracks(raw: String): List<Track> = runCatching {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            .jsonObject["results"]?.jsonObject
            ?.get("TRACK")?.jsonObject?.get("data")?.jsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val id = s("SNG_ID") ?: return@mapNotNull null
            val md5 = s("ALB_PICTURE")
            Track(
                id = id,
                title = s("SNG_TITLE").orEmpty(),
                artist = s("ART_NAME").orEmpty(),
                service = Service.DEEZER,
                albumTitle = s("ALB_TITLE"),
                durationMs = s("DURATION")?.toLongOrNull()?.times(1000),
                isrc = s("ISRC"),
                artworkUrl = md5?.takeIf { it.isNotBlank() }
                    ?.let { "https://e-cdns-images.dzcdn.net/images/cover/$it/500x500-000000-80-0-0.jpg" },
            )
        }
    }.getOrDefault(emptyList())

    override suspend fun resolve(url: String): MediaSelection? {
        val m = Regex("""deezer\.com/(?:[a-z]{2}/)?(track|album|playlist)/(\d+)""").find(url) ?: return null
        val (kind, id) = m.destructured
        return when (kind) {
            "track" -> {
                val t = json.decodeFromString(DzApiTrack.serializer(), apiGet("https://api.deezer.com/track/$id") {})
                MediaSelection(kind = MediaKind.TRACK, tracks = listOf(t.toTrack()))
            }
            "album" -> {
                val a = json.decodeFromString(DzApiAlbumFull.serializer(), apiGet("https://api.deezer.com/album/$id") {})
                MediaSelection(
                    kind = MediaKind.ALBUM,
                    containerTitle = a.title,
                    tracks = a.tracks.data.map { it.toTrack(a) },
                    albums = listOf(
                        Album(a.id.toString(), a.title, a.artist.name, Service.DEEZER,
                            trackCount = a.nbTracks, artworkUrl = a.coverXl, upc = a.upc),
                    ),
                    artists = listOf(Artist(a.artist.id.toString(), a.artist.name, Service.DEEZER)),
                )
            }
            "playlist" -> {
                val raw = apiGet("https://api.deezer.com/playlist/$id/tracks") { it.addQueryParameter("limit", "500") }
                val s = json.decodeFromString(DzApiSearch.serializer(), raw)
                MediaSelection(kind = MediaKind.PLAYLIST, tracks = s.data.map { it.toTrack() })
            }
            else -> null
        }
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val sngId = track.raw["dzId"] ?: throw IOException("Deezer: no track id")
        val (tier, url) = resolveStream(sngId, preference)
        return StreamInfo(
            url = url,
            quality = tier,
            decryption = net.ripster.mobile.core.model.Decryption.DeezerBlowfish(sngId),
        )
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = channelFlow {
        val sngId = request.track.raw["dzId"] ?: throw IOException("Deezer: no track id")
        val preference = request.forcedQualityId?.let { listOf(it) } ?: request.qualityPreference
        val (tier, url) = resolveStream(sngId, preference)
        send(DownloadEvent.Log("Deezer: ${tier.label}"))

        val outFile = File(cacheDir, "dz_$sngId.${tier.container}")
        val key = DeezerCrypto.blowfishKey(sngId)

        val req = Request.Builder().url(url).header("User-Agent", DeezerGw.UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Deezer: stream -> HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Deezer: empty stream body")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                outFile.outputStream().buffered().use { sink ->
                    DeezerCrypto.decryptStream(input, sink, key, total) { written, tot ->
                        trySend(DownloadEvent.Progress(tot?.let { written.toFloat() / it }, written, tot))
                    }
                }
            }
        }
        send(DownloadEvent.Done(outFile.absolutePath, tier, outFile.length()))
    }.flowOn(Dispatchers.IO)

    private suspend fun resolveStream(sngId: String, preference: List<String>): Pair<QualityTier, String> {
        if (!gw.ensureSession()) throw IOException("Deezer: ARL invalid or expired")
        val song = gw.songData(sngId)
        val token = song.results.trackToken.ifBlank { throw IOException("Deezer: no track_token (track unavailable)") }

        val formats = buildFormatList(preference, song.results)
        val (url, gotFormat) = gw.mediaUrl(token, formats)
            ?: throw IOException("Deezer: no playable source for this account/region")
        val tier = when (gotFormat.uppercase()) {
            "FLAC" -> flac
            "MP3_320" -> mp3_320
            else -> mp3_128
        }
        return tier to url
    }

    /** Наши id предпочтения → форматы Deezer, только те, что аккаунт реально отдаёт. */
    private fun buildFormatList(preference: List<String>, r: net.ripster.mobile.service.deezer.dto.DzGwResults): List<String> {
        val out = LinkedHashSet<String>()
        fun sz(s: String) = s.toLongOrNull() ?: 0L
        for (p in preference) when {
            p.startsWith("flac") && sz(r.filesizeFlac) > 0 -> out += "FLAC"
            p == "mp3_320" && sz(r.filesizeMp3_320) > 0 -> out += "MP3_320"
            p == "mp3_128" -> out += "MP3_128"
        }
        if (out.isEmpty()) { out += "MP3_320"; out += "MP3_128" }
        return out.toList()
    }

    // --- маппинг ---

    // suspend + IO: search()/resolve() вызываются из корутины Compose на Main —
    // синхронный execute() там роняет NetworkOnMainThreadException.
    private suspend fun apiGet(base: String, params: (okhttp3.HttpUrl.Builder) -> Unit): String =
        withContext(Dispatchers.IO) {
            val url = base.toHttpUrl().newBuilder().apply(params).build()
            val req = Request.Builder().url(url).header("User-Agent", DeezerGw.UA).build()
            RipsterHttp.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Deezer API ${url.encodedPath} -> HTTP ${r.code}")
                r.body?.string() ?: throw IOException("Deezer API ${url.encodedPath} -> empty")
            }
        }

    private fun DzApiTrack.toTrack(albumFull: DzApiAlbumFull? = null): Track {
        val cover = album?.coverXl ?: album?.coverBig ?: albumFull?.coverXl
        val year = (releaseDate ?: album?.releaseDate)?.take(4)?.toIntOrNull()
        return Track(
            id = id.toString(),
            title = title,
            artist = artist.name,
            service = Service.DEEZER,
            albumTitle = album?.title ?: albumFull?.title,
            albumArtist = albumFull?.artist?.name ?: artist.name,
            durationMs = duration.takeIf { it > 0 }?.times(1000),
            trackNumber = trackPosition,
            discNumber = diskNumber,
            year = year,
            isrc = isrc,
            artworkUrl = cover,
            raw = mapOf("dzId" to id.toString(), "albId" to ((album?.id ?: 0L).toString()), "artId" to artist.id.toString()),
        )
    }
}
