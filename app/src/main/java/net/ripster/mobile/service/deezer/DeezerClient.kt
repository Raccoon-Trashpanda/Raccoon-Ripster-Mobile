package net.ripster.mobile.service.deezer

import net.ripster.mobile.core.errors.EngineErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import net.ripster.mobile.service.deezer.dto.DzApiAlbumSearch
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
    /**
     * Готовность = ARL ПОХОЖ на ARL, а не просто «поле не пустое».
     *
     * Раньше сюда проходила любая строка, и в «Учётных записях» горело
     * «Подключён» на мусоре и на обрезанном при вставке токене — человек видел
     * зелёный статус и не понимал, почему всё падает. Настоящий ARL — ровно 192
     * шестнадцатеричных символа; такой проверки хватает, чтобы отсечь опечатки
     * и обрезки, и она не требует сети (метод зовут при отрисовке экрана).
     * Живость самого ARL всё равно выясняется на первом запросе — и ошибка
     * оттуда честная («ARL invalid or expired»).
     */
    override suspend fun isConfigured(): Boolean = looksLikeArl(arl)

    private fun looksLikeArl(v: String): Boolean {
        val s = v.trim()
        return s.length >= 128 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

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

        // Альбомы — отдельным вызовом: /search/track их не возвращает, поэтому
        // фильтры «Альбомы»/«Синглы/EP» в поиске были всегда пустыми.
        val albums = runCatching {
            val ar = apiGet("https://api.deezer.com/search/album") {
                it.addQueryParameter("q", query); it.addQueryParameter("limit", "25")
            }
            json.decodeFromString(DzApiAlbumSearch.serializer(), ar).data.map { a ->
                Album(
                    id = a.id.toString(),
                    title = a.title,
                    artist = a.artist.name,
                    service = Service.DEEZER,
                    year = a.releaseDate?.take(4)?.toIntOrNull(),
                    trackCount = a.nbTracks,
                    artworkUrl = a.coverXl ?: a.coverBig,
                    upc = a.upc,
                )
            }
        }.getOrDefault(emptyList())

        return MediaSelection(
            kind = MediaKind.TRACK,
            tracks = byId.values.toList(),
            albums = albums,
        )
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
            val body = resp.body ?: throw IOException(EngineErrors.EMPTY_STREAM)
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
        if (!gw.ensureSession()) throw IOException(EngineErrors.TOKEN_INVALID)
        val song = gw.songData(sngId)
        val token = song.results.trackToken.ifBlank { throw IOException(EngineErrors.TRACK_UNAVAILABLE) }

        val formats = buildFormatList(preference, song.results)
        val (url, gotFormat) = gw.mediaUrl(token, formats)
            ?: throw IOException(EngineErrors.NO_SOURCE_REGION)
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
    override suspend fun getArtist(artistId: String): net.ripster.mobile.core.pair.PcBridge.ArtistPage? {
        if (artistId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val info = json.parseToJsonElement(apiGet("https://api.deezer.com/artist/$artistId") {}).jsonObject
                if (info["error"] != null) return@runCatching null
                val aName = info["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val aLow = aName.lowercase()
                val pic = (info["picture_xl"] ?: info["picture_big"] ?: info["picture_medium"])
                    ?.jsonPrimitive?.contentOrNull

                val raw = ArrayList<kotlinx.serialization.json.JsonObject>()
                var next: String? = "https://api.deezer.com/artist/$artistId/albums?limit=100"
                var guard = 0
                while (next != null && guard++ < 4) {
                    val page = json.parseToJsonElement(apiGet(next!!) {}).jsonObject
                    (page["data"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
                        .forEach { raw.add(it.jsonObject) }
                    next = page["next"]?.jsonPrimitive?.contentOrNull
                }

                // компиляции (record_type "compile" ИЛИ кредит не на артиста) →
                // дотягиваем чей релиз и какой трек артиста в нём
                val comps = raw.filter {
                    (it["record_type"]?.jsonPrimitive?.contentOrNull ?: "") == "compile"
                }.take(18)
                val enrich = coroutineScope {
                    comps.map { a ->
                        val aid = a["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        async {
                            aid to runCatching {
                                val full = json.parseToJsonElement(apiGet("https://api.deezer.com/album/$aid") {}).jsonObject
                                val va = (full["artist"]?.jsonObject?.get("name"))?.jsonPrimitive?.contentOrNull.orEmpty()
                                val mine = (full["tracks"]?.jsonObject?.get("data")?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
                                    .map { it.jsonObject }
                                    .filter {
                                        val tn = (it["artist"]?.jsonObject?.get("name"))?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                                        val tt = it["title"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                                        (aLow.isNotBlank() && (aLow in tn || aLow in tt))
                                    }
                                    .mapNotNull { it["title"]?.jsonPrimitive?.contentOrNull }
                                    .distinct()
                                va to mine.joinToString("; ")
                            }.getOrDefault("" to "")
                        }
                    }.associate { it.await() }
                }

                val releases = raw.mapNotNull { a ->
                    val aid = a["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val rt = (a["record_type"]?.jsonPrimitive?.contentOrNull ?: "album")
                    val isComp = rt == "compile"
                    val date = a["release_date"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val e = if (isComp) enrich[aid] else null
                    net.ripster.mobile.core.pair.PcBridge.ArtistRelease(
                        id = aid,
                        title = a["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        coverUrl = (a["cover_medium"] ?: a["cover_big"] ?: a["cover"])?.jsonPrimitive?.contentOrNull,
                        year = date.take(4),
                        date = date,
                        trackCount = a["nb_tracks"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        type = if (isComp) "compilation" else when (rt) {
                            "single" -> "single"; "ep" -> "ep"; else -> "album"
                        },
                        url = a["link"]?.jsonPrimitive?.contentOrNull
                            ?: "https://www.deezer.com/album/$aid",
                        service = "deezer",
                        appearsAs = e?.second.orEmpty(),
                        albumArtist = e?.first.orEmpty(),
                    )
                }.sortedByDescending { it.date }

                net.ripster.mobile.core.pair.PcBridge.ArtistPage(
                    name = aName, pictureUrl = pic, releases = releases,
                )
            }.getOrNull()
        }
    }

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
