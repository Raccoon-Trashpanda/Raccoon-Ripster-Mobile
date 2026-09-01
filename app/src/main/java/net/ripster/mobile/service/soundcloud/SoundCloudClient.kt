package net.ripster.mobile.service.soundcloud

import kotlinx.coroutines.Dispatchers
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
import net.ripster.mobile.service.soundcloud.dto.ScTrack
import net.ripster.mobile.service.soundcloud.dto.ScTranscoding
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Клиент SoundCloud — не-DRM загрузка через публичный API v2.
 *
 * В объёме: прогрессивные потоки (обычно MP3 128, без аккаунта) и не-DRM HLS
 * (`mp3` / `aac` пресеты, склейка сегментов без ffmpeg). Вне объёма:
 * FairPlay-зашифрованный HLS (`cbcs`) — на десктопе он тоже не берётся без
 * отдельного дешифратора, здесь отдаём честную ошибку.
 *
 * `oauthToken` (SoundCloud Go+) опционален и нужен только для HQ AAC.
 */
class SoundCloudClient(
    private val oauthToken: String? = null,
    private val cacheDir: File,
) : ServiceClient {

    override val service = Service.SOUNDCLOUD

    private val api = SoundCloudApi(oauthToken)

    private val mp3_128 = QualityTier(
        id = "mp3_128", label = "MP3 128", lossless = false, container = "mp3", bitrateKbps = 128,
    )
    private val aac_hq = QualityTier(
        id = "aac_hq", label = "HQ AAC", lossless = false, container = "aac", bitrateKbps = 256,
    )

    // Поиск SoundCloud публичный: client_id скрейпится лениво в самом search().
    // Дёргать скрейп в пробе готовности — тот же баг «Проверяю сервисы…».
    override suspend fun isConfigured(): Boolean = true

    override suspend fun qualities(): List<QualityTier> =
        if (oauthToken.isNullOrBlank()) listOf(mp3_128) else listOf(aac_hq, mp3_128)

    override suspend fun search(query: String): MediaSelection {
        val tracks = api.searchTracks(query).map { it.toTrack() }
        return MediaSelection(kind = MediaKind.TRACK, tracks = tracks)
    }

    /** Станция по жанру: чарт SoundCloud (top → trending → поиск как запас). */
    suspend fun station(genreSlug: String, limit: Int = 30): List<Track> {
        for (kind in listOf("top", "trending")) {
            val t = runCatching { api.chart(genreSlug, kind, limit) }.getOrDefault(emptyList())
            if (t.isNotEmpty()) return t.map { it.toTrack() }
        }
        return runCatching { api.searchTracks(genreSlug.replace("-", " "), limit) }
            .getOrDefault(emptyList()).map { it.toTrack() }
    }

    override suspend fun resolve(url: String): MediaSelection? {
        if (!isSoundCloudUrl(url)) return null
        return when (val r = api.resolve(url)) {
            is ScResolveResult.OneTrack -> MediaSelection(
                kind = MediaKind.TRACK,
                tracks = listOf(r.track.toTrack()),
            )
            is ScResolveResult.Playlist -> {
                val stubs = r.playlist.tracks.filter { it.isStub }.map { it.id }
                val full = r.playlist.tracks.filterNot { it.isStub }
                val filled = full + api.tracksByIds(stubs)
                // Сохранить порядок плейлиста.
                val byId = filled.associateBy { it.id }
                val ordered = r.playlist.tracks.mapNotNull { byId[it.id] }
                MediaSelection(
                    kind = MediaKind.PLAYLIST,
                    containerTitle = r.playlist.title,
                    tracks = ordered.map { it.toTrack() },
                    albums = listOf(
                        Album(
                            id = r.playlist.id.toString(),
                            title = r.playlist.title,
                            artist = r.playlist.user.username,
                            service = Service.SOUNDCLOUD,
                            trackCount = r.playlist.trackCount,
                            artworkUrl = bigArt(r.playlist.artworkUrl),
                        )
                    ),
                    artists = listOf(
                        Artist(r.playlist.user.id.toString(), r.playlist.user.username, Service.SOUNDCLOUD),
                    ),
                )
            }
            ScResolveResult.Unsupported -> null
        }
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val fresh = freshScTrack(track)
        val candidates = orderedNonDrm(fresh, preference)
        var lastErr: IOException? = null
        for ((tier, transcoding) in candidates) {
            val url = try {
                api.streamUrl(transcoding.url, fresh.trackAuthorization)
            } catch (e: IOException) {
                lastErr = e
                continue
            }
            return StreamInfo(
                url = url,
                quality = tier,
                headers = mapOf("User-Agent" to SoundCloudClientId.UA),
            )
        }
        throw lastErr ?: IOException("SoundCloud: no playable transcoding for ${track.title}")
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val fresh = freshScTrack(request.track)
        val preference = request.forcedQualityId?.let { listOf(it) } ?: request.qualityPreference
        val candidates = orderedNonDrm(fresh, preference)

        // SC-транскодинги живут недолго и часть из них отдаёт 404/403 на
        // media-эндпоинте (особенно progressive) — идём по списку, пока
        // какой-нибудь не отдаст рабочий поток.
        var lastErr: IOException? = null
        for ((idx, cand) in candidates.withIndex()) {
            val (tier, transcoding) = cand
            currentCoroutineContext().ensureActive()
            val streamUrl = try {
                api.streamUrl(transcoding.url, fresh.trackAuthorization)
            } catch (e: IOException) {
                lastErr = e
                emit(DownloadEvent.Log("SoundCloud: ${transcoding.format.protocol}/${tier.label} недоступен (${e.message}), пробую следующий"))
                continue
            }
            emit(DownloadEvent.Log("SoundCloud: ${tier.label}, ${transcoding.format.protocol} stream" + if (idx > 0) " (fallback #$idx)" else ""))
            val outFile = File(cacheDir, "sc_${fresh.id}.${tier.container}")
            try {
                when (transcoding.format.protocol) {
                    "progressive" -> downloadProgressive(streamUrl, outFile)
                    "hls" -> HlsAssembler.assemble(streamUrl, outFile).collect { p ->
                        emit(DownloadEvent.Progress(p.fraction, p.bytesWritten, null))
                    }
                    else -> throw IOException("SoundCloud: unknown protocol ${transcoding.format.protocol}")
                }
            } catch (e: IOException) {
                lastErr = e
                outFile.delete()
                emit(DownloadEvent.Log("SoundCloud: поток ${transcoding.format.protocol} оборвался (${e.message}), пробую следующий"))
                continue
            }
            emit(DownloadEvent.Done(outFile.absolutePath, tier, outFile.length()))
            return@flow
        }
        throw lastErr ?: IOException("SoundCloud: не удалось получить ни один поток трека")
    }.flowOn(Dispatchers.IO)

    // --- внутреннее ---

    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadEvent>.downloadProgressive(
        url: String,
        out: File,
    ) {
        val req = Request.Builder().url(url).header("User-Agent", SoundCloudClientId.UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("SoundCloud: stream -> HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("SoundCloud: empty stream body")
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
    }

    /** Свежий resolve по permalink — transcodings и track_authorization короткоживущие. */
    private suspend fun freshScTrack(track: Track): ScTrack {
        val permalink = track.raw["permalink"]
            ?: throw IOException("SoundCloud: track has no permalink to re-resolve")
        return when (val r = api.resolve(permalink)) {
            is ScResolveResult.OneTrack -> r.track
            else -> throw IOException("SoundCloud: permalink no longer resolves to a track ($permalink)")
        }
    }

    /** Все не-DRM транскодинги, лучший первым — для перебора с фолбэком. */
    private fun orderedNonDrm(t: ScTrack, preference: List<String>): List<Pair<QualityTier, ScTranscoding>> {
        val candidates = t.media.transcodings.filter { isNonDrm(it) }
        if (candidates.isEmpty()) {
            val onlyEncrypted = t.media.transcodings.isNotEmpty()
            throw IOException(
                if (onlyEncrypted)
                    "SoundCloud: трек только в FairPlay-зашифрованном HLS — не-DRM загрузкой не берётся"
                else
                    "SoundCloud: у трека нет потоков (снят с публикации или Go+ без токена)"
            )
        }
        // preference по нашим id → SC quality/preset. Всё, что не hq → mp3_128.
        val wantHq = preference.firstOrNull { it == "aac_hq" || it.startsWith("flac") } != null &&
            !oauthToken.isNullOrBlank()
        return candidates.sortedByDescending { score(it, wantHq) }.map { tc ->
            val tier = if (tc.quality == "hq" || tc.preset.startsWith("aac")) aac_hq else mp3_128
            tier to tc
        }
    }

    private fun score(t: ScTranscoding, wantHq: Boolean): Int {
        var s = 0
        if (t.quality == "hq") s += if (wantHq) 100 else 10
        if (t.preset.startsWith("mp3")) s += 40          // самый совместимый контейнер
        if (t.preset.startsWith("aac")) s += 30
        if (t.format.protocol == "progressive") s += 20  // проще и надёжнее HLS
        if (t.preset.startsWith("opus")) s -= 20         // валиден, но хуже играется на Android-плеерах
        return s
    }

    private fun isNonDrm(t: ScTranscoding): Boolean {
        if (t.snipped) return false
        val preset = t.preset.lowercase()
        val url = t.url.lowercase()
        // SC 2024+ отдаёт CTR/CBC-зашифрованные адаптивные потоки: маркер — либо
        // в пресете ("abr_sq"/"abr_hq"), либо прямо в пути transcoding.url
        // (".../stream/ctr-encrypted-hls"). Оба берём только с ключом (нет).
        if ("encrypted" in url || "cbcs" in preset || "cbc" in url || "ctr" in url) return false
        if (preset.startsWith("abr")) return false
        // Классические не-DRM пресеты: mp3 (progressive/HLS), aac_*, opus_*.
        if (preset.startsWith("mp3") || preset.startsWith("aac") || preset.startsWith("opus")) return true
        // Пресет неизвестен, но поток прогрессивный и не помечен шифрованием —
        // это старый добрый прямой MP3, тоже берём.
        return t.format.protocol == "progressive"
    }

    private fun ScTrack.toTrack(): Track {
        val pm = publisherMetadata
        return Track(
            id = id.toString(),
            title = title,
            artist = pm?.artist?.takeIf { it.isNotBlank() } ?: user.username,
            service = Service.SOUNDCLOUD,
            albumTitle = pm?.albumTitle,
            albumArtist = pm?.artist,
            durationMs = duration.takeIf { it > 0 },
            year = releaseYear,
            isrc = isrc ?: pm?.isrc,
            artworkUrl = bigArt(artworkUrl),
            raw = buildMap {
                put("permalink", permalinkUrl)
                put("scTrackId", id.toString())
            },
        )
    }

    /** SC отдаёт `...-large.jpg` (100px). Для встраивания в тег берём t500x500. */
    private fun bigArt(url: String?): String? =
        url?.replace("-large.", "-t500x500.")

    private fun isSoundCloudUrl(url: String): Boolean {
        val u = url.lowercase()
        return "soundcloud.com/" in u || "snd.sc/" in u
    }
}
