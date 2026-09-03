package net.ripster.mobile.service.yandex

import net.ripster.mobile.core.errors.EngineErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Яндекс.Музыка как источник. OAuth-токен (`YANDEX_OAUTH`) — тот же, что у
 * колонок (Glagol). Скачивание: `download-info` → подписанный URL
 * (`get-mp3`), без расшифровки. FLAC там, где аккаунт его отдаёт.
 *
 * `ymId` в `raw` нужен ещё и для каста трека на Яндекс Станцию.
 */
class YandexMusicClient(
    private val oauthToken: String,
    private val cacheDir: File,
) : ServiceClient {

    override val service = Service.YANDEX
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val flac = QualityTier("flac", "FLAC", lossless = true, container = "flac")
    // Яндекс lossless приходит кодеком FLAC в MP4-контейнере. Файл на диске —
    // .m4a (так его правильно опознаёт ExoPlayer), но в подписи качества это
    // FLAC, а не «M4A»: контейнер тут не про потерю данных. Сэмплрейт/битность
    // Яндекс не сообщает — не выдумываем, показываем просто «FLAC».
    private val flacMp4 = QualityTier("flac", "FLAC (Lossless)", lossless = true, container = "flac")
    private val mp3 = QualityTier("mp3_320", "MP3 320", lossless = false, container = "mp3", bitrateKbps = 320)

    // Есть токен — сервис считается подключённым. НЕ гейтим на живой
    // `account/status`: этот вызов у Яндекса геозависим (403 вне RU/СНГ,
    // капризная сеть телефона) и его провал не значит «токен не задан» —
    // иначе спаренный телефон показывает «подключите Яндекс», хотя токен
    // синхронизирован с ПК. Реальные ошибки всплывут на самом поиске/потоке.
    /**
     * Готовность = токен похож на OAuth-токен Яндекса, а не «поле не пустое».
     * Иначе «Подключён» загорается на опечатке или обрезке при вставке, и
     * человек ищет причину не там. Токены Яндекса — длинная строка без
     * пробелов (обычно с префиксом `y0_`), заметно длиннее полусотни символов.
     */
    override suspend fun isConfigured(): Boolean = looksLikeYandexToken(oauthToken)

    private fun looksLikeYandexToken(v: String): Boolean {
        val t = v.trim()
        return t.length >= 30 && t.none { it.isWhitespace() }
    }

    override suspend fun qualities(): List<QualityTier> = listOf(flac, mp3)

    override suspend fun search(query: String): MediaSelection {
        val raw = api("search") {
            it.addQueryParameter("text", query); it.addQueryParameter("type", "all"); it.addQueryParameter("page", "0")
        }
        val res = json.decodeFromString(YmEnvelope.serializer(YmSearchResult.serializer()), raw).result
        return MediaSelection(
            kind = MediaKind.TRACK,
            tracks = res.tracks.results.map { it.toTrack() },
            albums = res.albums.results.map { a ->
                Album(
                    id = a.id.toString(),
                    title = a.title,
                    artist = a.artists.firstOrNull()?.name ?: "",
                    service = Service.YANDEX,
                    year = a.year,
                    trackCount = a.trackCount,
                    artworkUrl = a.coverUri?.let { "https://" + it.removeSuffix("%%") + "400x400" },
                )
            },
        )
    }

    /**
     * Станция «Моей волны» по жанру/настроению/активности — родной rotor Яндекса
     * (`/rotor/station/<id>/tracks`). id вида `genre:house`, `mood:energetic`,
     * `activity:sport`, `user:onyourwave`. Это настоящая курация Яндекса, а не
     * поиск. Треки без `available` отбрасываем.
     */
    suspend fun station(stationId: String, size: Int = 30): List<Track> = withContext(Dispatchers.IO) {
        val raw = api("rotor/station/$stationId/tracks") {
            it.addQueryParameter("settings2", "true")
        }
        val seq = json.decodeFromString(YmEnvelope.serializer(YmRotorSeq.serializer()), raw).result
        seq.sequence.mapNotNull { it.track }.filter { it.available }.take(size).map { it.toTrack() }
    }

    override suspend fun resolve(url: String): MediaSelection? {
        val t = Regex("""music\.yandex\.[a-z]+/(?:album/(\d+)/track/(\d+)|track/(\d+))""").find(url)
        val id = t?.groupValues?.getOrNull(2)?.ifBlank { null }
            ?: t?.groupValues?.getOrNull(3)?.ifBlank { null }
        val albumId = Regex("""music\.yandex\.[a-z]+/album/(\d+)$""").find(url)?.groupValues?.get(1)
        return when {
            id != null -> {
                val raw = api("tracks/$id") {}
                val list = json.decodeFromString(YmEnvelope.serializer(kotlinx.serialization.builtins.ListSerializer(YmTrack.serializer())), raw).result
                list.firstOrNull()?.let { MediaSelection(kind = MediaKind.TRACK, tracks = listOf(it.toTrack())) }
            }
            albumId != null -> {
                val raw = api("albums/$albumId/with-tracks") {}
                val a = json.decodeFromString(YmEnvelope.serializer(YmAlbumFull.serializer()), raw).result
                MediaSelection(
                    kind = MediaKind.ALBUM,
                    containerTitle = a.title,
                    tracks = a.volumes.flatten().map { it.toTrack() },
                    albums = listOf(Album(a.id.toString(), a.title, a.artists.firstOrNull()?.name ?: "", Service.YANDEX,
                        year = a.year, trackCount = a.trackCount, artworkUrl = cover(a.coverUri))),
                    artists = a.artists.map { Artist(it.id.toString(), it.name, Service.YANDEX) },
                )
            }
            else -> null
        }
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val id = track.raw["ymId"] ?: throw IOException("Yandex: no track id")
        // Воспроизведение из карточек/поиска тоже в lossless, если аккаунт даёт:
        // `get-file-info?quality=lossless` → AES-128-CTR-поток, плеерный
        // DataSource расшифровывает на лету (как Deezer Blowfish). Не вышло —
        // старый mp3-путь.
        val wantFlac = preference.any { it.startsWith("flac") || it.contains("hires") }
        if (wantFlac) {
            val ll = runCatching { losslessInfo(id) }.getOrNull()
            if (ll != null && ll.keyHex != null) {
                return StreamInfo(
                    url = ll.url,
                    quality = flacMp4,
                    decryption = net.ripster.mobile.core.model.Decryption.YandexAesCtr(ll.keyHex),
                )
            }
        }
        val (tier, url) = signedUrl(id, preference)
        return StreamInfo(url = url, quality = tier)
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val id = request.track.raw["ymId"] ?: throw IOException("Yandex: no track id")
        val pref = request.forcedQualityId?.let { listOf(it) } ?: request.qualityPreference
        val wantFlac = pref.any { it.startsWith("flac") || it.contains("hires") }

        // Настоящий lossless у Яндекса отдаёт ТОЛЬКО новый эндпоинт
        // `get-file-info?quality=lossless` (со своей HMAC-подписью и клиент-
        // хедером). Старый `download-info` для Plus-аккаунта всё равно возвращает
        // только mp3 — из-за этого мобилка качала Яндекс в mp3, хотя подписка
        // есть. Файл приходит AES-CTR-зашифрованным (`transport: encraw`) —
        // расшифровываем на лету при записи. Не вышло — тихо падаем на mp3.
        val lossless = if (wantFlac) runCatching { losslessInfo(id) }.getOrNull() else null
        if (lossless != null) {
            val out = File(cacheDir, "ym_$id.m4a")   // FLAC-в-MP4 → расширение .m4a
            val ok = runCatching {
                emit(DownloadEvent.Log("Yandex: ${flacMp4.label}"))
                val req = Request.Builder().url(lossless.url).header("User-Agent", UA).build()
                RipsterHttp.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Yandex: lossless -> HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException(EngineErrors.EMPTY_STREAM)
                    val total = body.contentLength().takeIf { it > 0 }
                    // encraw = AES-128-CTR, IV = 16 нулей (nonce 12 + counter 0).
                    // CTR — потоковый шифр: только update(), doFinal() не нужен.
                    val ctr = lossless.keyHex?.let { hex ->
                        Cipher.getInstance("AES/CTR/NoPadding").apply {
                            init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(hex), "AES"), IvParameterSpec(ByteArray(16)))
                        }
                    }
                    body.byteStream().use { input ->
                        out.outputStream().buffered().use { sink ->
                            val buf = ByteArray(64 * 1024); var got = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf); if (n < 0) break
                                val chunk = ctr?.update(buf, 0, n) ?: buf.copyOf(n)
                                if (chunk.isNotEmpty()) sink.write(chunk)
                                got += n
                                emit(DownloadEvent.Progress(total?.let { got.toFloat() / it }, got, total))
                            }
                        }
                    }
                }
                out.length() > 4096
            }.getOrDefault(false)
            if (ok) {
                emit(DownloadEvent.Done(out.absolutePath, flacMp4, out.length()))
                return@flow
            }
            runCatching { out.delete() }
            emit(DownloadEvent.Log("Yandex: lossless не отдался — беру mp3"))
        }

        val (tier, url) = signedUrl(id, pref)
        emit(DownloadEvent.Log("Yandex: ${tier.label}"))
        val out = File(cacheDir, "ym_$id.${tier.container}")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Yandex: stream -> HTTP ${resp.code}")
            val body = resp.body ?: throw IOException(EngineErrors.EMPTY_STREAM)
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                out.outputStream().buffered().use { sink ->
                    val buf = ByteArray(64 * 1024); var got = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        sink.write(buf, 0, n); got += n
                        emit(DownloadEvent.Progress(total?.let { got.toFloat() / it }, got, total))
                    }
                }
            }
        }
        emit(DownloadEvent.Done(out.absolutePath, tier, out.length()))
    }.flowOn(Dispatchers.IO)

    private class LosslessInfo(val url: String, val keyHex: String?)

    /** Новый lossless-путь Яндекса: `get-file-info?quality=lossless` + HMAC-sign. */
    private suspend fun losslessInfo(id: String): LosslessInfo? = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        // порядок и содержимое строго как у ymd: ts,trackId,quality,codecs,transports
        val signMsg = "$ts$id" + "lossless" + YM_CODECS.replace(",", "") + "encraw"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(YM_SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val sign = android.util.Base64.encodeToString(
            mac.doFinal(signMsg.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP,
        ).dropLast(1)   // ymd режет последний символ (padding '=')
        val url = "$BASE/get-file-info".toHttpUrl().newBuilder()
            .addQueryParameter("ts", ts.toString())
            .addQueryParameter("trackId", id)
            .addQueryParameter("quality", "lossless")
            .addQueryParameter("codecs", YM_CODECS)
            .addQueryParameter("transports", "encraw")
            .addQueryParameter("sign", sign)
            .build().toString()
        val req = Request.Builder().url(url)
            .header("Authorization", "OAuth $oauthToken")
            .header("X-Yandex-Music-Client", YM_CLIENT)
            .header("User-Agent", UA)
            .build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val di = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(r.body?.string() ?: return@withContext null)
                .jsonObject["result"]?.jsonObject?.get("downloadInfo")?.jsonObject
                ?: return@withContext null
            val codec = di["codec"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            if (!codec.startsWith("flac")) return@withContext null   // не lossless — уступаем mp3
            val u = di["urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: return@withContext null
            LosslessInfo(u, di["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length == 32 })
        }
    }

    private fun hexToBytes(h: String): ByteArray =
        ByteArray(h.length / 2) { ((h[it * 2].digitToInt(16) shl 4) + h[it * 2 + 1].digitToInt(16)).toByte() }

    // --- подписанный URL ---

    private suspend fun signedUrl(id: String, preference: List<String>): Pair<QualityTier, String> = withContext(Dispatchers.IO) {
        val infoRaw = api("tracks/$id/download-info") {}
        val infos = json.decodeFromString(
            YmEnvelope.serializer(kotlinx.serialization.builtins.ListSerializer(YmDownloadInfo.serializer())), infoRaw,
        ).result
        val wantFlac = preference.any { it.startsWith("flac") }
        val chosen = infos
            .filter { !it.preview }
            .sortedWith(compareByDescending<YmDownloadInfo> { (it.codec == "flac") == wantFlac }.thenByDescending { it.bitrateInKbps })
            .firstOrNull() ?: throw IOException(EngineErrors.TRACK_UNAVAILABLE)

        val xml = get(chosen.downloadInfoUrl)
        fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)
            ?: throw IOException("Yandex: download-info xml missing <$name>")
        val host = tag("host"); val path = tag("path"); val ts = tag("ts"); val s = tag("s")
        val sign = md5(SALT + path.substring(1) + s)
        val url = "https://$host/get-mp3/$sign/$ts$path"
        val tier = if (chosen.codec == "flac") flac else mp3
        tier to url
    }

    override suspend fun getArtist(artistId: String): net.ripster.mobile.core.pair.PcBridge.ArtistPage? {
        if (artistId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val raw = api("artists/$artistId/brief-info") {}
                val res = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                    .jsonObject["result"]?.jsonObject ?: return@runCatching null
                val artist = res["artist"]?.jsonObject
                val name = artist?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                val pic = artist?.get("cover")?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
                    ?.let { "https://" + it.removeSuffix("%%") + "400x400" }

                fun mapAlbum(el: kotlinx.serialization.json.JsonElement, appears: Boolean):
                    net.ripster.mobile.core.pair.PcBridge.ArtistRelease? {
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
                    val date = o["releaseDate"]?.jsonPrimitive?.contentOrNull
                        ?: o["year"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val owner = (o["artists"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("name")?.jsonPrimitive?.contentOrNull).orEmpty()
                    val tc = o["trackCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    val kind = (o["type"]?.jsonPrimitive?.contentOrNull ?: "").lowercase()
                    return net.ripster.mobile.core.pair.PcBridge.ArtistRelease(
                        id = id,
                        title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        coverUrl = o["coverUri"]?.jsonPrimitive?.contentOrNull
                            ?.let { "https://" + it.removeSuffix("%%") + "400x400" },
                        year = date.take(4),
                        date = date,
                        trackCount = tc,
                        type = if (appears || kind == "compilation") "compilation"
                        else if ((tc ?: 99) in 1..3 || kind == "single") "single" else "album",
                        url = "https://music.yandex.ru/album/$id",
                        service = "yandex",
                        albumArtist = if (appears && owner.isNotBlank() && !owner.equals(name, true)) owner else "",
                    )
                }

                val own = (res["albums"]?.jsonArray ?: emptyList()).mapNotNull { mapAlbum(it, false) }
                val also = (res["alsoAlbums"]?.jsonArray ?: emptyList()).mapNotNull { mapAlbum(it, true) }
                val seen = HashSet<String>()
                val releases = (own + also).filter { seen.add(it.id) }.sortedByDescending { it.date }

                net.ripster.mobile.core.pair.PcBridge.ArtistPage(
                    name = name.ifBlank { return@runCatching null },
                    pictureUrl = pic, releases = releases,
                )
            }.getOrNull()
        }
    }

    private suspend fun api(path: String, params: (okhttp3.HttpUrl.Builder) -> Unit): String {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply(params).build()
        return get(url.toString())
    }

    // suspend + withContext(IO): раньше поиск/resolve дёргали этот блокирующий
    // OkHttp-вызов прямо из Main-корутины экрана → NetworkOnMainThreadException
    // (station()/signedUrl()/download() свой IO-контекст имели, а search() — нет).
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", "OAuth $oauthToken")
            .header("User-Agent", UA)
            .build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException(EngineErrors.TOKEN_INVALID)
            if (!r.isSuccessful) throw IOException("Yandex ${url.substringAfterLast('/')} -> HTTP ${r.code}")
            r.body?.string() ?: throw IOException("Yandex -> empty")
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return buildString { for (b in d) append("%02x".format(b)) }
    }

    private fun cover(uri: String?): String? =
        uri?.takeIf { it.isNotBlank() }?.let { "https://" + it.removeSuffix("%%") + "1000x1000" }

    // --- DTO ---

    /**
     * `id` у Яндекса приходит то строкой (`"tracks/{id}"`, rotor), то ЧИСЛОМ
     * (эндпоинт `search`) — из-за чего kotlinx падал «Expected quotation mark».
     * Читаем оба вида, наружу всегда строка.
     */
    private object LenientString : kotlinx.serialization.KSerializer<String> {
        override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "LenientString", kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        )
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String {
            val el = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement()
            return (el as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        }
        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String) =
            encoder.encodeString(value)
    }

    @Serializable private data class YmEnvelope<T>(val result: T)
    @Serializable private data class YmArtist(val id: Long = 0, val name: String = "")
    @Serializable private data class YmAlbumRef(
        val id: Long = 0, val title: String = "", val year: Int? = null,
        @SerialName("coverUri") val coverUri: String? = null,
    )
    @Serializable
    private data class YmTrack(
        @Serializable(with = LenientString::class) val id: String = "",
        @Serializable(with = LenientString::class) val realId: String = "",
        val title: String = "",
        val durationMs: Long = 0,
        val available: Boolean = true,
        val artists: List<YmArtist> = emptyList(),
        val albums: List<YmAlbumRef> = emptyList(),
    ) {
        fun toTrack(): Track {
            val alb = albums.firstOrNull()
            return Track(
                id = (realId.ifBlank { id }),
                title = title,
                artist = artists.joinToString(", ") { it.name },
                service = Service.YANDEX,
                albumTitle = alb?.title,
                durationMs = durationMs.takeIf { it > 0 },
                year = alb?.year,
                artworkUrl = alb?.coverUri?.let { "https://" + it.removeSuffix("%%") + "400x400" },
                raw = mapOf(
                    "ymId" to (realId.ifBlank { id }),
                    "artId" to (artists.firstOrNull()?.id?.toString().orEmpty()),
                    // id альбома нужен, чтобы тап по треку из сборника открывал
                    // весь релиз (экран артиста, «участие»), и для каста на Станцию.
                    "albId" to (alb?.id?.takeIf { it > 0 }?.toString().orEmpty()),
                ),
            )
        }
    }
    @Serializable private data class YmSearchTracks(val results: List<YmTrack> = emptyList())
    @Serializable private data class YmSearchAlbums(val results: List<YmAlbumShort> = emptyList())
    @Serializable private data class YmAlbumShort(
        val id: Long = 0, val title: String = "", val year: Int? = null,
        @SerialName("trackCount") val trackCount: Int? = null,
        @SerialName("coverUri") val coverUri: String? = null,
        val artists: List<YmArtist> = emptyList(),
    )
    // type=all возвращает и tracks, и albums одним ответом — раньше поиск просил
    // type=track, поэтому фильтр «Альбомы» в поиске был всегда пуст.
    @Serializable private data class YmSearchResult(
        val tracks: YmSearchTracks = YmSearchTracks(),
        val albums: YmSearchAlbums = YmSearchAlbums(),
    )
    @Serializable private data class YmRotorSeq(val sequence: List<YmRotorItem> = emptyList())
    @Serializable private data class YmRotorItem(val track: YmTrack? = null)
    @Serializable
    private data class YmAlbumFull(
        val id: Long = 0, val title: String = "", val year: Int? = null,
        @SerialName("trackCount") val trackCount: Int? = null,
        @SerialName("coverUri") val coverUri: String? = null,
        val artists: List<YmArtist> = emptyList(),
        val volumes: List<List<YmTrack>> = emptyList(),
    )
    @Serializable
    private data class YmDownloadInfo(
        val codec: String = "",
        val bitrateInKbps: Int = 0,
        val preview: Boolean = false,
        @SerialName("downloadInfoUrl") val downloadInfoUrl: String = "",
    )

    companion object {
        private const val BASE = "https://api.music.yandex.net"
        private const val SALT = "XGRlBW9FXlekgbPrRHuSiA"
        private const val UA = "Yandex-Music-API"
        // Новый lossless-путь (`get-file-info`): клиент-хедер обязателен (без него
        // 403 not-allowed), ключ подписи и список кодеков — как в `ymd` api.py.
        private const val YM_CLIENT = "YandexMusicAndroid/24023621"
        private const val YM_SIGN_KEY = "p93jhgh689SBReK6ghtw62"
        private const val YM_CODECS = "flac,flac-mp4,mp3,aac,he-aac,aac-mp4,he-aac-mp4"
    }
}
