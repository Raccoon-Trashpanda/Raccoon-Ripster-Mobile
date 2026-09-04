package net.ripster.mobile.service.apple

import kotlinx.serialization.json.intOrNull
import net.ripster.mobile.core.errors.EngineErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonElement
import net.ripster.mobile.core.model.Album
import net.ripster.mobile.core.model.DownloadEvent
import net.ripster.mobile.core.model.DownloadRequest
import net.ripster.mobile.core.model.MediaKind
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.StreamInfo
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.core.pair.PcBridge
import net.ripster.mobile.core.service.ServiceClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Apple Music — единственный источник, который телефон НЕ тянет сам: расшифровка
 * (Docker-враппер / zhaarey / AMD) живёт на ПК. Этот клиент работает только при
 * активном сопряжении: ссылку отдаёт ПК (`/api/pair/fetch`), опрашивает статус,
 * забирает готовый файл (`/api/pair/file/{id}`). Дальше — общий конвейер (проба
 * качества, SAF, библиотека); теги НЕ переписываем — ПК уже проставил настоящие.
 *
 * Поиска нет (у ПК-движка его тоже нет для Apple — только по ссылке).
 */
class AppleProxyClient(
    private val pc: PcBridge,
    private val cacheDir: File,
) : ServiceClient {

    override val service = Service.APPLE

    private val alac = QualityTier("alac", "ALAC (Apple Lossless)", lossless = true, container = "m4a")
    private val aac = QualityTier("aac", "AAC 256", lossless = false, container = "m4a", bitrateKbps = 256)

    override suspend fun isConfigured(): Boolean = pc.paired && pc.capabilities.contains("apple_music")

    // Метаданные и обложки — из открытого iTunes API (без ключа), в той же
    // витрине, где качает ПК (иначе ПК получит «0 треков»).
    private val json = Json { ignoreUnknownKeys = true }
    private val storefront: String get() = pc.appleStorefront

    override suspend fun search(query: String): MediaSelection = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext MediaSelection(kind = MediaKind.TRACK, tracks = emptyList())
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", q)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "25")
            .addQueryParameter("country", storefront)
            .build()
        // Альбомы отдельным запросом: `entity=song` их не возвращает, и до
        // 04.09.2026 карточек релизов Apple в поиске телефона не было вовсе —
        // а значит и кнопке ↓ было не за что зацепиться. Скачивание идёт через
        // сопряжённый ПК, поэтому релиз тут полноценно полезен, хотя играть его
        // на телефоне нельзя.
        val albumUrl = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", q)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "album")
            .addQueryParameter("limit", "15")
            .addQueryParameter("country", storefront)
            .build()
        val items = itunes(url.toString())
        val albums = runCatching { itunes(albumUrl.toString()) }.getOrDefault(emptyList())
            .mapNotNull { it.toAlbum() }
        MediaSelection(
            kind = MediaKind.TRACK,
            tracks = items.mapNotNull { it.toTrack() },
            albums = albums,
        )
    }

    /** Строка-коллекция iTunes → релиз. Ссылку берём ту, что отдал сам iTunes:
     *  номер релиза у Apple свой в каждой витрине, и собранная из id ссылка
     *  ведёт в чужой витрине не туда. */
    private fun JsonElement.toAlbum(): Album? {
        val o = jsonObject
        if (o["collectionType"]?.jsonPrimitive?.contentOrNull != "Album") return null
        val cid = o["collectionId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = o["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (title.isBlank()) return null
        val date = o["releaseDate"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return Album(
            id = cid,
            title = title,
            artist = o["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            service = Service.APPLE,
            year = date.take(4).toIntOrNull(),
            trackCount = o["trackCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            artworkUrl = o["artworkUrl100"]?.jsonPrimitive?.contentOrNull
                ?.replace("100x100bb", "600x600bb"),
            releaseDate = date.take(10).ifBlank { null },
            genre = o["primaryGenreName"]?.jsonPrimitive?.contentOrNull,
            copyright = o["copyright"]?.jsonPrimitive?.contentOrNull,
            url = o["collectionViewUrl"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun resolve(url: String): MediaSelection? {
        if (!APPLE.containsMatchIn(url)) return null
        // id: ?i=<songId> (трек) либо .../<albumId> в конце пути
        val songId = Regex("""[?&]i=(\d+)""").find(url)?.groupValues?.get(1)
        val tailId = url.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.all(Char::isDigit) }
        val id = songId ?: tailId ?: return stubSelection(url)
        val isAlbum = songId == null
        return withContext(Dispatchers.IO) {
            // entity=song ВСЕГДА: для альбома iTunes вернёт строку-коллекцию +
            // все треки; entity=album отдавал только коллекцию → фильтр «track»
            // давал ноль → заглушка с голым ID вместо названия.
            // Витрина подписки (RU) часто не содержит релиз — тогда пробуем без
            // страны и us: метаданные для показа берём откуда есть, качает всё
            // равно ПК по канонической ссылке.
            val stores = listOfNotNull(storefront.takeIf { it.isNotBlank() }, null, "us").distinct()
            var items: List<kotlinx.serialization.json.JsonElement> = emptyList()
            for (cc in stores) {
                val look = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
                    .addQueryParameter("id", id)
                    .addQueryParameter("entity", "song")
                    .apply { if (cc != null) addQueryParameter("country", cc) }
                    .build()
                items = itunes(look.toString())
                if (items.any { it.jsonObject["wrapperType"]?.jsonPrimitive?.contentOrNull == "track" }) break
            }
            val collection = items.firstOrNull {
                it.jsonObject["wrapperType"]?.jsonPrimitive?.contentOrNull == "collection"
            }?.jsonObject
            val tracks = items.filter { it.jsonObject["wrapperType"]?.jsonPrimitive?.contentOrNull == "track" }
                .mapNotNull { it.toTrack() }
            if (tracks.isEmpty()) stubSelection(url)
            else MediaSelection(
                kind = if (isAlbum) MediaKind.ALBUM else MediaKind.TRACK,
                tracks = tracks,
                containerTitle = collection?.get("collectionName")?.jsonPrimitive?.contentOrNull
                    ?: tracks.firstOrNull()?.albumTitle,
            )
        }
    }

    override suspend fun getArtist(artistId: String): net.ripster.mobile.core.pair.PcBridge.ArtistPage? {
        if (artistId.isBlank() || !artistId.all(Char::isDigit)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // Витрина подписки, иначе релиза может «не быть»; при пустом
                // ответе — без страны и us.
                val stores = listOfNotNull(storefront.takeIf { it.isNotBlank() }, null, "us").distinct()
                var items: List<kotlinx.serialization.json.JsonElement> = emptyList()
                for (cc in stores) {
                    val u = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
                        .addQueryParameter("id", artistId)
                        .addQueryParameter("entity", "album")
                        .addQueryParameter("limit", "200")
                        .apply { if (cc != null) addQueryParameter("country", cc) }
                        .build()
                    items = itunes(u.toString())
                    if (items.any { it.jsonObject["collectionType"]?.jsonPrimitive?.contentOrNull == "Album" }) break
                }
                val artistRow = items.firstOrNull {
                    it.jsonObject["wrapperType"]?.jsonPrimitive?.contentOrNull == "artist"
                }?.jsonObject
                val aName = artistRow?.get("artistName")?.jsonPrimitive?.contentOrNull.orEmpty()
                val aLow = aName.lowercase()

                val releases = items.mapNotNull { el ->
                    val o = el.jsonObject
                    if (o["collectionType"]?.jsonPrimitive?.contentOrNull != "Album") return@mapNotNull null
                    val cid = o["collectionId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val owner = o["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val date = o["releaseDate"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val tc = o["trackCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    // «с этим артистом» — если релиз кредитован не на него
                    // (Various Artists / другой артист)
                    val appears = owner.isNotBlank() && owner.lowercase() != aLow
                    net.ripster.mobile.core.pair.PcBridge.ArtistRelease(
                        id = cid,
                        title = o["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        coverUrl = o["artworkUrl100"]?.jsonPrimitive?.contentOrNull?.replace("100x100bb", "600x600bb"),
                        year = date.take(4),
                        date = date,
                        trackCount = tc,
                        type = if (appears) "compilation" else if ((tc ?: 99) in 1..3) "single" else "album",
                        url = o["collectionViewUrl"]?.jsonPrimitive?.contentOrNull
                            ?: "https://music.apple.com/album/$cid",
                        service = "apple",
                        albumArtist = if (appears) owner else "",
                    )
                }.sortedByDescending { it.date }

                val pic = artistRow?.get("artworkUrl100")?.jsonPrimitive?.contentOrNull
                    ?: releases.firstOrNull()?.coverUrl
                net.ripster.mobile.core.pair.PcBridge.ArtistPage(
                    name = aName.ifBlank { return@runCatching null },
                    pictureUrl = pic, releases = releases,
                )
            }.getOrNull()
        }
    }

    private fun stubSelection(url: String): MediaSelection {
        val slug = url.substringAfterLast('/').substringBefore('?').replace('-', ' ').trim()
        return MediaSelection(
            kind = MediaKind.TRACK,
            tracks = listOf(Track(
                id = url.substringAfterLast('/').substringBefore('?'),
                title = slug.ifBlank { "Apple Music" }.replaceFirstChar { it.uppercase() },
                artist = "Apple Music",
                service = Service.APPLE,
                raw = mapOf("appleUrl" to canonicalUrl(url)),
            )),
        )
    }

    private fun itunes(url: String): List<kotlinx.serialization.json.JsonElement> = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "RipsterMobile/0.1").build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            json.parseToJsonElement(r.body?.string().orEmpty())
                .jsonObject["results"]?.jsonArray?.toList() ?: emptyList()
        }
    }.getOrDefault(emptyList())

    private fun kotlinx.serialization.json.JsonElement.toTrack(): Track? {
        val o = jsonObject
        val trackId = o["trackId"]?.jsonPrimitive?.longOrNull ?: return null
        val title = o["trackName"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = o["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val album = o["collectionName"]?.jsonPrimitive?.contentOrNull
        val art = o["artworkUrl100"]?.jsonPrimitive?.contentOrNull
            ?.replace("100x100bb", "600x600bb")
        val durMs = o["trackTimeMillis"]?.jsonPrimitive?.longOrNull
        val artId = o["artistId"]?.jsonPrimitive?.longOrNull?.toString().orEmpty()
        // trackViewUrl уже в нужной витрине (country=storefront запроса)
        val viewUrl = o["trackViewUrl"]?.jsonPrimitive?.contentOrNull
            ?: "https://music.apple.com/$storefront/song/$trackId"
        return Track(
            id = trackId.toString(),
            title = title,
            artist = artist,
            albumTitle = album,
            // iTunes отдаёт жанр, дату и копирайт в том же ответе, а мы их
            // выбрасывали: карточка релиза, открытого из радара по apple-ссылке,
            // оставалась без жанра и лейбла, хотя у Deezer/Qobuz они были
            // (жалоба владельца 04.09.2026 про Etherwood «Haven»).
            albumArtist = o["collectionArtistName"]?.jsonPrimitive?.contentOrNull ?: artist,
            trackNumber = o["trackNumber"]?.jsonPrimitive?.intOrNull,
            discNumber = o["discNumber"]?.jsonPrimitive?.intOrNull,
            trackTotal = o["trackCount"]?.jsonPrimitive?.intOrNull,
            discTotal = o["discCount"]?.jsonPrimitive?.intOrNull,
            genre = o["primaryGenreName"]?.jsonPrimitive?.contentOrNull,
            releaseDate = o["releaseDate"]?.jsonPrimitive?.contentOrNull?.take(10),
            year = o["releaseDate"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull(),
            copyright = o["copyright"]?.jsonPrimitive?.contentOrNull,
            // Лейбла у iTunes отдельного поля нет — он почти всегда стоит в
            // копирайте («℗ 2024 Med School Music»). Берём оттуда, срезав
            // метку и год: выдумывать нечего, это ровно та же строка.
            label = o["copyright"]?.jsonPrimitive?.contentOrNull
                ?.replace(Regex("""^[℗©\s]*\d{4}\s*"""), "")
                ?.trim()?.takeIf { it.isNotBlank() },
            service = Service.APPLE,
            durationMs = durMs,
            artworkUrl = art,
            raw = mapOf("appleUrl" to canonicalUrl(viewUrl), "artId" to artId),
        )
    }

    /** Привести витрину ссылки к той, где качает ПК (иначе «0 треков»). */
    private fun canonicalUrl(url: String): String =
        Regex("""(music|geo\.music)\.apple\.com/[a-z]{2}/""")
            .replace(url) { "music.apple.com/$storefront/" }

    override suspend fun qualities(): List<QualityTier> = listOf(alac, aac)

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo =
        throw IOException(EngineErrors.PC_ONLY)

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = channelFlow {
        val url = request.track.raw["appleUrl"] ?: request.track.raw["url"]
            ?: throw IOException(EngineErrors.NO_LINK)
        if (!pc.paired || !pc.capabilities.contains("apple_music")) {
            send(DownloadEvent.Error(EngineErrors.PC_UNPAIRED))
            return@channelFlow
        }

        // качество для ПК: верх предпочтения lossless → alac, иначе aac
        val wantLossless = (request.forcedQualityId ?: request.qualityPreference.firstOrNull() ?: "")
            .let { it.startsWith("flac") || it == "alac" || "lossless" in it || "hires" in it }
        val q = if (wantLossless) "alac" else "aac"

        send(DownloadEvent.Log("Apple → PC ($q)"))
        val taskId = pc.appleFetch(url, q).getOrElse {
            send(DownloadEvent.Error(EngineErrors.code(EngineErrors.PC_REJECTED, it.message)))
            return@channelFlow
        }

        var tier = if (wantLossless) alac else aac
        // опрос статуса; крышка 20 мин — если ПК завис на задаче, не поллим вечно
        val deadline = System.currentTimeMillis() + 20 * 60_000L
        var misses = 0
        while (true) {
            delay(2000)
            if (System.currentTimeMillis() > deadline) {
                send(DownloadEvent.Error(EngineErrors.code(EngineErrors.PC_INCOMPLETE, "20 min")))
                return@channelFlow
            }
            val job = pc.appleStatus(taskId).getOrElse {
                if (++misses >= 5) {
                    send(DownloadEvent.Error(EngineErrors.code(EngineErrors.PC_OFFLINE, it.message)))
                    return@channelFlow
                }
                null
            } ?: continue
            misses = 0
            when (job.status) {
                "queued" -> send(DownloadEvent.Progress(null, 0, null))
                "running" -> send(DownloadEvent.Progress(job.progress / 100f, job.progress.toLong(), 100L))
                "done" -> break
                "error" -> {
                    send(DownloadEvent.Error(job.error.ifBlank { EngineErrors.PC_INCOMPLETE }))
                    return@channelFlow
                }
            }
        }

        // забрать файл
        val cache = File(cacheDir, "apple_${taskId}.m4a")
        val bytes = pc.appleFile(taskId, cache).getOrElse {
            send(DownloadEvent.Error(EngineErrors.code(EngineErrors.PC_FETCH_FAILED, it.message)))
            return@channelFlow
        }
        // расширение — по факту (ПК мог отдать .flac для ALAC-remux)
        val real = probeExt(cache)?.let { ext ->
            if (ext != "m4a") {
                val renamed = File(cacheDir, "apple_${taskId}.$ext")
                if (cache.renameTo(renamed)) renamed else cache
            } else cache
        } ?: cache
        if (real.name.endsWith(".flac")) tier = tier.copy(container = "flac", lossless = true)

        send(DownloadEvent.Done(real.absolutePath, tier, bytes))
    }

    /** Первые байты → расширение (m4a/flac/mp3). */
    private fun probeExt(f: File): String? = runCatching {
        f.inputStream().use { s ->
            val head = ByteArray(12)
            if (s.read(head) < 12) return null
            val ascii = String(head, Charsets.ISO_8859_1)
            when {
                ascii.startsWith("fLaC") -> "flac"
                ascii.substring(4).startsWith("ftyp") -> "m4a"
                ascii.startsWith("ID3") || (head[0].toInt() and 0xFF == 0xFF) -> "mp3"
                else -> null
            }
        }
    }.getOrNull()

    private companion object {
        val APPLE = Regex("""(music|geo\.music|itunes)\.apple\.com""")
    }
}
