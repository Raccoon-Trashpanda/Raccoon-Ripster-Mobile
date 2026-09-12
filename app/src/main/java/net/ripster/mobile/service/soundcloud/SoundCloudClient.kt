package net.ripster.mobile.service.soundcloud

import net.ripster.mobile.core.errors.EngineErrors
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
    /**
     * Тир «лучшее AAC» — только для ВЫБОРА потока, не для подписи.
     *
     * Число здесь стояло 256 намертво, и им подписывался ЛЮБОЙ aac-поток.
     *
     * Замер 06.09.2026 по ссылке владельца (bananagoldrecords/hareton-salvanini
     * -saire-beno-pebo-remix-1) — и он же урок про то, как меряют:
     *   • БЕЗ OAuth сервис отдаёт `aac_160k`, `aac_96k`, `abr_sq`, `mp3_1_0`;
     *   • С OAuth к тому же треку добавляются `aac_256k` (quality=hq) и
     *     `abr_hq` — те самые 256, плашка HD на сайте не врёт.
     * То есть 256 существуют, но не всегда, и по анонимному ответу этого не
     * видно: первый мой вывод «256 не бывает» был сделан по неавторизованному
     * запросу и оказался ложным.
     *
     * Отсюда и правило: тир-предпочтение не называет числа вовсе, а подпись
     * берётся у ТОГО потока, который реально выбран. Иначе `aac_160k`
     * подписывался «256», а `aac_96k` — тоже «256».
     *
     * Битрейта до выбора потока мы не знаем, поэтому у тира-предпочтения его
     * НЕТ (`bitrateKbps = null` = «не знаю»), а настоящее число проставляется
     * в [tierFor] по пресету уже выбранного транскодинга.
     */
    private val aac_hq = QualityTier(
        id = "aac_hq", label = "AAC", lossless = false, container = "aac", bitrateKbps = null,
    )

    /**
     * Подпись под РЕАЛЬНО выбранным потоком: имя пресета SoundCloud уже
     * содержит правду о битрейте, надо только её не выбрасывать.
     */
    private fun tierFor(tc: ScTranscoding): QualityTier {
        val aac = tc.quality == "hq" || tc.preset.startsWith("aac")
        val kbps = presetKbpsKnown(tc.preset)
        val name = if (aac) "AAC" else "MP3"
        val base = if (aac) aac_hq else mp3_128
        // Число ставим, только если пресет его действительно называет.
        // «Не знаю» — это пустая подпись, а не круглое число наугад: именно
        // так и появились несуществующие 256 kbps.
        return base.copy(
            id = tierId(tc),
            label = if (kbps != null) "$name $kbps" else name,
            bitrateKbps = kbps,
            container = if (aac) "aac" else "mp3",
        )
    }

    /**
     * Имя тира, указывающее на ОДИН конкретный поток трека.
     *
     * Общие `aac_hq`/`mp3_128` описывают предпочтение аккаунта («бери что
     * получше»), а выбор в списке качеств — это выбор конкретного потока,
     * который у этого трека есть. Разные вещи, поэтому и имена разные:
     * «sc:aac_160k:hls». Протокол в имени не для красоты — у одного пресета
     * бывают и progressive, и hls-варианты, и это разные ссылки.
     */
    private fun tierId(tc: ScTranscoding): String =
        "sc:${tc.preset}:${tc.format.protocol}"

    /**
     * Битрейт пресета, когда он ИЗВЕСТЕН. null — «не знаю».
     *
     * Отличается от [presetKbps] назначением: там число нужно для сортировки
     * и незнание допустимо заменить серединой, здесь оно показывается
     * человеку — и подставлять догадку нельзя.
     */
    internal fun presetKbpsKnown(preset: String): Int? {
        val p = preset.lowercase()
        Regex("(\\d+)k").find(p)?.let { return it.groupValues[1].toInt() }
        return when {
            p.startsWith("mp3") -> 128      // у SoundCloud mp3_0_0/mp3_1_0 фиксированы
            p.startsWith("opus") -> 72
            else -> null                    // abr_* адаптивный, остальное незнакомо
        }
    }

    // Поиск SoundCloud публичный: client_id скрейпится лениво в самом search().
    // Дёргать скрейп в пробе готовности — тот же баг «Проверяю сервисы…».
    override suspend fun isConfigured(): Boolean = true

    /**
     * Качества КОНКРЕТНОГО трека — ровно те, что SoundCloud реально отдаёт.
     *
     * Просьба владельца 06.09.2026: «предлагать качество исходя из того, какое
     * там нативно; если предлагается несколько — выбор ровно из того, что
     * есть». Общий список [qualities] к треку отношения не имеет: он про
     * аккаунт. У одного трека бывает `aac_160k` + `aac_96k` + `mp3_1_0`,
     * у другого — только `mp3_1_0`, и показывать во втором случае «AAC» —
     * это предлагать несуществующее.
     *
     * DRM-потоки (FairPlay) в список НЕ попадают: взять их мы не можем, и
     * пункт, который гарантированно не сработает, — это не выбор.
     */
    override suspend fun qualitiesFor(track: Track): List<QualityTier> = runCatching {
        orderedNonDrm(freshScTrack(track), emptyList())
            .map { it.first }
            .distinctBy { it.id }
    }.getOrElse { emptyList() }

    override suspend fun qualities(): List<QualityTier> =
        if (oauthToken.isNullOrBlank()) listOf(mp3_128) else listOf(aac_hq, mp3_128)

    /**
     * Измерить учётку: работает ли доступ и есть ли токен.
     *
     * У SoundCloud публичный доступ — штатный режим: без токена он отдаёт
     * 128 kbps, и это не поломка. Поэтому «нет токена» здесь не смерть, а
     * честное «жив, lossless не будет»; лечение (забрать токен с ПК) при этом
     * всё равно запускается — у ПК токен с Go+ может быть.
     */
    override suspend fun health(): net.ripster.mobile.core.service.AccountHealth {
        val H = net.ripster.mobile.core.service.AccountHealth
        return try {
            api.searchTracks("a", limit = 1)
            val hasToken = !oauthToken.isNullOrBlank()
            net.ripster.mobile.core.service.AccountHealth(
                alive = true,
                lossless = false,
                losslessPossible = false,   // SoundCloud его не отдаёт ни на каком тарифе
                plan = if (hasToken) "с токеном" else "публичный доступ",
                quality = if (hasToken) "AAC 256" else "MP3 128",
                reason = if (hasToken) "" else "без токена только 128 kbps",
            )
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if ("401" in msg) H.dead("токен отвергнут (401)")
            else H.unknown("не смогли спросить: ${e::class.simpleName}")
        }
    }

    override suspend fun search(query: String): MediaSelection {
        val tracks = api.searchTracks(query).map { it.toTrack() }
        // Плейлисты — это и есть альбомы SoundCloud: релизы лейблы выкладывают
        // именно ими. Без этого запроса фильтр «Альбомы» был пуст всегда (тот же
        // дефект, что нашёлся 12.09.2026 у Tidal, Qobuz и Beatport).
        // Отказ по плейлистам не должен ронять уже найденные треки.
        val albums = runCatching {
            api.searchPlaylists(query).map { p ->
                Album(
                    id = p.id.toString(),
                    title = p.title,
                    artist = p.user.username,
                    service = Service.SOUNDCLOUD,
                    trackCount = p.trackCount,
                    artworkUrl = p.artworkUrl,
                    // Постоянный адрес обязателен: у плейлиста SoundCloud ссылку
                    // из номера не собрать, а без неё релиз нечем открыть.
                    url = p.permalinkUrl.ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())
        return MediaSelection(
            kind = if (tracks.isEmpty() && albums.isNotEmpty()) MediaKind.ALBUM else MediaKind.TRACK,
            tracks = tracks,
            albums = albums,
        )
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

    override suspend fun getArtist(artistId: String): net.ripster.mobile.core.pair.PcBridge.ArtistPage? {
        if (artistId.isBlank() || !artistId.all(Char::isDigit)) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val u = api.user(artistId)
                val name = u.username.ifBlank { return@runCatching null }
                // На SoundCloud «релиз» = загруженный трек; альбомов почти нет.
                val releases = api.userTracks(artistId, 80).map { t ->
                    val date = (t.displayDate ?: t.createdAt ?: "").take(10)
                    net.ripster.mobile.core.pair.PcBridge.ArtistRelease(
                        id = t.id.toString(),
                        title = t.title,
                        coverUrl = bigArt(t.artworkUrl),
                        year = date.take(4),
                        date = date,
                        trackCount = 1,
                        type = "single",
                        url = t.permalinkUrl,
                        service = "soundcloud",
                    )
                }.sortedByDescending { it.date }
                net.ripster.mobile.core.pair.PcBridge.ArtistPage(
                    name = name,
                    pictureUrl = u.avatarUrl?.replace("-large.", "-t500x500."),
                    releases = releases,
                )
            }.getOrNull()
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
                emit(DownloadEvent.Log("SoundCloud: ${transcoding.format.protocol}/${tier.label} unavailable (${e.message}), trying next"))
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
                emit(DownloadEvent.Log("SoundCloud: ${transcoding.format.protocol} stream broke (${e.message}), trying next"))
                continue
            }
            emit(DownloadEvent.Done(outFile.absolutePath, tier, outFile.length()))
            return@flow
        }
        throw lastErr ?: IOException(EngineErrors.EMPTY_STREAM)
    }.flowOn(Dispatchers.IO)

    // --- внутреннее ---

    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadEvent>.downloadProgressive(
        url: String,
        out: File,
    ) {
        val req = Request.Builder().url(url).header("User-Agent", SoundCloudClientId.UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("SoundCloud: stream -> HTTP ${resp.code}")
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
    }

    /** Свежий resolve по permalink — transcodings и track_authorization короткоживущие. */
    private suspend fun freshScTrack(track: Track): ScTrack {
        val permalink = track.raw["permalink"]
            ?: throw IOException("SoundCloud: track has no permalink to re-resolve")
        return when (val r = api.resolve(permalink)) {
            is ScResolveResult.OneTrack -> r.track
            else -> throw IOException(EngineErrors.TRACK_UNAVAILABLE)
        }
    }

    /** Все не-DRM транскодинги, лучший первым — для перебора с фолбэком. */
    private fun orderedNonDrm(t: ScTrack, preference: List<String>): List<Pair<QualityTier, ScTranscoding>> {
        val candidates = t.media.transcodings.filter { isNonDrm(it) }
        if (candidates.isEmpty()) {
            val onlyEncrypted = t.media.transcodings.isNotEmpty()
            throw IOException(
                if (onlyEncrypted)
                    EngineErrors.DRM_UNSUPPORTED
                else
                    EngineErrors.TRACK_UNAVAILABLE
            )
        }
        // Человек выбрал КОНКРЕТНЫЙ поток («sc:aac_160k:hls») — ставим его
        // первым, а не «примерно такой же». Остальные оставляем ниже: ссылки
        // SoundCloud короткоживущие, и обрубать запасной путь ради буквальности
        // значило бы менять «не то качество» на «ничего не скачалось».
        val pinned = preference.filter { it.startsWith("sc:") }.toSet()

        // preference по нашим id → SC quality/preset. Всё, что не hq → mp3_128.
        val wantHq = preference.firstOrNull { it == "aac_hq" || it.startsWith("flac") } != null &&
            !oauthToken.isNullOrBlank()
        return candidates
            .sortedByDescending { score(it, wantHq) + if (tierId(it) in pinned) 100_000 else 0 }
            .map { tc -> tierFor(tc) to tc }
    }

    /**
     * Сколько килобит обещает пресет SoundCloud.
     *
     * Имена у них разного вида: «aac_160k» и «aac_96k» несут число в себе,
     * а «mp3_0_0» и «opus_0_0» — нет, хотя их битрейт у сервиса фиксирован.
     * Незнакомый пресет получает середину, а не ноль: «не знаю» не должно
     * означать «плохой» — иначе новый тир молча уедет в конец списка.
     */
    internal fun presetKbps(preset: String): Int {
        val p = preset.lowercase()
        Regex("(\\d+)k").find(p)?.let { return it.groupValues[1].toInt() }
        return when {
            p.startsWith("mp3") -> 128
            p.startsWith("opus") -> 72
            p.startsWith("aac") -> 160
            else -> 128
        }
    }

    /**
     * Какой поток брать. Главный признак — КАЧЕСТВО ЗВУКА.
     *
     * Было наоборот: mp3 получал +40 «за совместимость контейнера», aac — +30,
     * и progressive ещё +20 сверху. У трека с aac_160k и mp3_0_0 выигрывал mp3 128 —
     * то есть телефон сознательно брал худшее из двух доступных. Замер
     * 05.09.2026 по треку nWu: сервис отдаёт aac_160k, aac_96k, mp3_0_0.
     *
     * AAC на Android играется штатно, так что «совместимость» была не доводом,
     * а привычкой. Протокол остался тай-брейком: progressive действительно
     * проще HLS, но это не повод терять тридцать килобит.
     */
    private fun score(t: ScTranscoding, wantHq: Boolean): Int {
        // Тир Go+ без токена не отдаётся — не тратим на него первую попытку.
        val hqBonus = if (t.quality == "hq" && wantHq) 1000 else 0
        val protocolTieBreak = if (t.format.protocol == "progressive") 1 else 0
        return hqBonus + presetKbps(t.preset) * 2 + protocolTieBreak
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
            // Жанр, объявленный автором. Станции сверяют по нему, а не по
            // названию плитки: слово в заголовке — совпадение, а не жанр.
            genre = genre?.takeIf { it.isNotBlank() },
            // Настоящие счётчики SoundCloud — по ним волна решает, что в жанре
            // действительно слушают, а не что просто нашлось.
            popularity = net.ripster.mobile.core.service.Popularity
                .fromSoundCloudCounts(playbackCount, likesCount),
            raw = buildMap {
                put("permalink", permalinkUrl)
                put("scTrackId", id.toString())
                if (user.id != 0L) put("artId", user.id.toString())
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
