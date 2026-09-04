package net.ripster.mobile.service.qobuz

import net.ripster.mobile.core.errors.EngineErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
import net.ripster.mobile.service.qobuz.dto.QbAlbumFull
import net.ripster.mobile.service.qobuz.dto.QbTrack
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Клиент Qobuz. Прямые FLAC/MP3-ссылки без расшифровки. Формат выбирается
 * от лучшего к худшему по предпочтению; Qobuz отдаёт ровно запрошенный
 * `format_id` или помечает `sample=true` (тогда пробуем ниже).
 */
class QobuzClient(
    email: String?,
    password: String?,
    token: String?,
    appId: String?,
    secret: String?,
    private val cacheDir: File,
) : ServiceClient {

    override val service = Service.QOBUZ

    private val api = QobuzApi(email, password, token, appId, secret, cacheDir)

    // format_id: 27=FLAC≤192/24, 7=FLAC≤96/24, 6=FLAC16/44, 5=MP3 320
    private val flac24 = QualityTier("flac_24", "FLAC 24-bit", lossless = true, container = "flac", bitDepth = 24)
    private val flac16 = QualityTier("flac_16", "FLAC 16-bit", lossless = true, container = "flac", bitDepth = 16, sampleRateHz = 44100)
    private val mp3_320 = QualityTier("mp3_320", "MP3 320", lossless = false, container = "mp3", bitrateKbps = 320)

    // Готовность = есть креды. Живой ensureAuth() — при первом search(), не в
    // пробе готовности (иначе сетевой вызов вешает экран поиска).
    override suspend fun isConfigured(): Boolean = api.hasCredentials()

    // Вход Qobuz дорогой: если нет готовых app_id+secret, resolve() тянет
    // многомегабайтный bundle.js со страницы веб-плеера. Делаем это в фоне при
    // регистрации, а не в первом поиске под таймаутом.
    override suspend fun warmUp() { runCatching { api.ensureAuth() } }

    override suspend fun qualities(): List<QualityTier> = listOf(flac24, flac16, mp3_320)

    override suspend fun search(query: String): MediaSelection {
        val s = api.search(query)
        return MediaSelection(kind = MediaKind.TRACK, tracks = s.tracks.items.map { it.toTrack() })
    }

    override suspend fun resolve(url: String): MediaSelection? {
        val m = Regex("""(?:open|play|www)\.qobuz\.com/.*?(album|track)/([a-z0-9]+)""").find(url) ?: return null
        val (kind, id) = m.destructured
        return when (kind) {
            "track" -> MediaSelection(kind = MediaKind.TRACK, tracks = listOf(api.track(id).toTrack()))
            "album" -> {
                val a = api.album(id)
                MediaSelection(
                    kind = MediaKind.ALBUM,
                    containerTitle = a.title,
                    tracks = a.tracks.items.map { it.toTrack(a) },
                    albums = listOf(
                        Album(a.id, a.title, a.artist.name, Service.QOBUZ,
                            trackCount = a.tracksCount, artworkUrl = a.image.large, upc = a.upc,
                            // Полные метаданные релиза: Qobuz отдаёт их прямо
                            // на альбоме, и раньше они просто выбрасывались.
                            genre = a.genre?.name,
                            label = a.label?.name,
                            releaseDate = a.releaseDateOriginal
                                ?: a.releasedAt?.let {
                                    java.time.Instant.ofEpochSecond(it)
                                        .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                                },
                            copyright = a.copyright),
                    ),
                    artists = listOf(Artist(a.artist.id.toString(), a.artist.name, Service.QOBUZ)),
                )
            }
            else -> null
        }
    }

    override suspend fun getArtist(artistId: String): net.ripster.mobile.core.pair.PcBridge.ArtistPage? {
        if (artistId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val a = api.artist(artistId)
                val aLow = a.name.lowercase()
                val items = a.albums.items

                fun isComp(al: net.ripster.mobile.service.qobuz.dto.QbArtistAlbum): Boolean {
                    if ((al.releaseType ?: "").lowercase() == "compilation") return true
                    val aa = al.artist.name.lowercase()
                    return aa.isNotBlank() && aa != aLow && aa != "various artists" &&
                        al.artist.id.toString() != artistId
                }

                val comps = items.filter { it.id.isNotBlank() && isComp(it) }.take(18)
                val enrich = coroutineScope {
                    comps.map { al ->
                        async {
                            al.id to runCatching {
                                val full = api.album(al.id)
                                val mine = full.tracks.items.filter {
                                    val pn = it.performer.name.lowercase()
                                    (aLow.isNotBlank() && aLow in pn) ||
                                        it.performer.id.toString() == artistId ||
                                        (aLow.isNotBlank() && aLow in it.title.lowercase())
                                }.mapNotNull { it.title.ifBlank { null } }.distinct()
                                mine.joinToString("; ")
                            }.getOrDefault("")
                        }
                    }.associate { it.await() }
                }

                val releases = items.mapNotNull { al ->
                    if (al.id.isBlank()) return@mapNotNull null
                    val comp = isComp(al)
                    val date = al.releaseDateOriginal
                        ?: al.releasedAt?.let {
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .format(java.util.Date(it * 1000))
                        } ?: ""
                    net.ripster.mobile.core.pair.PcBridge.ArtistRelease(
                        id = al.id,
                        title = al.title,
                        coverUrl = al.image.large,
                        year = date.take(4),
                        date = date,
                        trackCount = al.tracksCount,
                        type = if (comp) "compilation" else when ((al.releaseType ?: "album").lowercase()) {
                            "single" -> "single"; "ep", "epminialbum" -> "ep"; else -> "album"
                        },
                        url = al.url ?: "https://www.qobuz.com/album/${al.id}",
                        service = "qobuz",
                        appearsAs = if (comp) enrich[al.id].orEmpty() else "",
                        albumArtist = if (comp) al.artist.name else "",
                    )
                }.sortedByDescending { it.date }

                net.ripster.mobile.core.pair.PcBridge.ArtistPage(
                    name = a.name, pictureUrl = a.image.large, releases = releases,
                )
            }.getOrNull()
        }
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val id = track.raw["qbId"] ?: throw IOException("Qobuz: no track id")
        val (tier, url) = resolveStream(id, preference)
        return StreamInfo(url = url, quality = tier)
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val id = request.track.raw["qbId"] ?: throw IOException("Qobuz: no track id")
        val preference = request.forcedQualityId?.let { listOf(it) } ?: request.qualityPreference
        val (tier, url) = resolveStream(id, preference)
        emit(DownloadEvent.Log("Qobuz: ${tier.label}"))

        val outFile = File(cacheDir, "qb_$id.${tier.container}")
        val req = Request.Builder().url(url).header("User-Agent", "RipsterMobile/0.1").build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Qobuz: stream -> HTTP ${resp.code}")
            val body = resp.body ?: throw IOException(EngineErrors.EMPTY_STREAM)
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                outFile.outputStream().buffered().use { sink ->
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
        emit(DownloadEvent.Done(outFile.absolutePath, tier, outFile.length()))
    }.flowOn(Dispatchers.IO)

    private suspend fun resolveStream(id: String, preference: List<String>): Pair<QualityTier, String> {
        if (!api.ensureAuth()) throw IOException(EngineErrors.AUTH_FAILED)
        val fmtOrder = buildList {
            for (p in preference) when {
                p == "flac_24" || p == "flac" -> { add(27 to flac24); add(7 to flac24) }
                p == "flac_16" -> add(6 to flac16)
                p == "mp3_320" -> add(5 to mp3_320)
            }
            if (isEmpty()) { add(6 to flac16); add(5 to mp3_320) }
        }.distinctBy { it.first }

        // Причину ПЕРВОГО реального отказа держим: раньше каждый бросок глотался
        // в `getOrNull()`, и человек всегда получал «нет файла для твоего
        // аккаунта/региона» — даже когда на деле не сошлась подпись запроса
        // (не тот app_secret). Диагноз врал и уводил чинить не то.
        var lastErr: Throwable? = null
        for ((fmt, tier) in fmtOrder) {
            val fu = runCatching { api.fileUrl(id, fmt) }
                .onFailure { if (lastErr == null) lastErr = it }
                .getOrNull() ?: continue
            if (fu.url.isNotBlank() && !fu.sample) {
                val realTier = when (fu.formatId) {
                    5 -> mp3_320
                    6 -> flac16
                    else -> flac24.copy(
                        bitDepth = fu.bitDepth ?: 24,
                        sampleRateHz = fu.samplingRate?.let { (it * 1000).toInt() },
                    )
                }
                return realTier to fu.url
            }
        }
        // Ни один формат не дал ссылку. Если падало с ошибкой — отдаём ЕЁ
        // (протухший ключ/подпись/токен), и только если Qobuz честно отвечал
        // «нет файла» на все форматы — говорим про аккаунт и регион.
        lastErr?.let { throw it }
        throw IOException(EngineErrors.NO_SOURCE_REGION)
    }

    private fun QbTrack.toTrack(albumFull: QbAlbumFull? = null): Track {
        val img = album?.image?.large ?: albumFull?.image?.large
        val year = (album?.releasedAt ?: albumFull?.releasedAt)?.let {
            java.util.Calendar.getInstance().apply { timeInMillis = it * 1000 }.get(java.util.Calendar.YEAR)
        }
        return Track(
            id = id.toString(),
            title = title,
            artist = performer.name.ifBlank { album?.artist?.name ?: albumFull?.artist?.name ?: "" },
            service = Service.QOBUZ,
            albumTitle = album?.title ?: albumFull?.title,
            albumArtist = album?.artist?.name ?: albumFull?.artist?.name,
            durationMs = duration.takeIf { it > 0 }?.times(1000),
            trackNumber = trackNumber,
            discNumber = mediaNumber,
            year = year,
            isrc = isrc,
            artworkUrl = img,
            // Полные теги: Qobuz отдаёт жанр/лейбл/копирайт на альбоме,
            // композитора — на треке. Берём с трека, иначе с альбома.
            genre = (album?.genre ?: albumFull?.genre)?.name?.takeIf { it.isNotBlank() },
            composer = composer?.name?.takeIf { it.isNotBlank() },
            label = (album?.label ?: albumFull?.label)?.name?.takeIf { it.isNotBlank() },
            copyright = (copyright ?: album?.copyright ?: albumFull?.copyright)?.takeIf { it.isNotBlank() },
            upc = (album?.upc ?: albumFull?.upc)?.takeIf { it.isNotBlank() },
            trackTotal = album?.tracksCount ?: albumFull?.tracksCount,
            discTotal = album?.mediaCount ?: albumFull?.mediaCount,
            releaseDate = (album?.releaseDateOriginal ?: albumFull?.releaseDateOriginal)
                ?.takeIf { it.isNotBlank() },
            raw = mapOf("qbId" to id.toString(), "albId" to (album?.id?.toString() ?: ""), "artId" to (album?.artist?.id?.toString() ?: performer?.id?.toString() ?: "")),
        )
    }
}
