package net.ripster.mobile.service.jiosaavn

import net.ripster.mobile.core.errors.EngineErrors
import net.ripster.mobile.core.errors.attempt
import net.ripster.mobile.core.errors.isJobCancellation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import net.ripster.mobile.core.service.ServiceClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec

/**
 * JioSaavn — индийский каталог, и это самый «самостоятельный» сервис мобильного
 * Рипстера: У НЕГО НЕТ ЛОГИНА ВООБЩЕ. Ни токена, ни аккаунта, ни DRM, ни
 * подписи на ссылке — телефон делает всё сам и ПК-мост не зовёт.
 *
 * Протокол (доказан на десктопе 21.09.2026):
 *  • Каталог и поиск — публичный `www.jiosaavn.com/api.php`
 *    (`search.getResults` / `search.getAlbumResults`, по ссылке — `webapi.get`).
 *  • Ссылка на звук добывается ЛОКАЛЬНО расшифровкой `encrypted_media_url`:
 *    DES-ECB, ключ `38346591`, PKCS#5, на вход — base64. Результат — прямой
 *    `https://aac.saavncdn.com/.../<hash>_96.mp4`; ступень меняется подстановкой
 *    `_96`/`_160`/`_320`.
 *  • Подписанный хост `web.saavncdn.com` (его выдаёт `song.generateAuthToken`)
 *    отвечает нам 403 ВСЕГДА — поэтому `generateAuthToken` мы НЕ зовём вовсе.
 *    Весь смысл в том, чтобы не ехать на подписанный хост.
 *
 * Качества — только lossy AAC-LC 44.1 стерео; ступень 320 — ПОТОЛОК, lossless у
 * JioSaavn не бывает ни в каком виде, и UI не должен намекать на обратное.
 * Наличие 320 у конкретного трека видно из каталога (`more_info."320kbps"`),
 * поэтому [qualitiesFor] строится по этому флагу, а лестница в [resolveStream]
 * спускается 320→160→96 до первой существующей и НИКОГДА не поднимается выше
 * запрошенного.
 *
 * Идея локальной расшифровки и константа ключа — из открытых реализаций
 * JioSaavn (подход описан в oviirup/jiosaavn-downloader, MIT; протокол API —
 * в OrpheusDL-модуле @bunnykek, github.com/bunnykek/orpheusdl-jiosaavn).
 * Реализация написана заново под эти модели, без переноса кода.
 */
class JioSaavnClient(private val cacheDir: File) : ServiceClient {

    override val service = Service.JIOSAAVN

    private val json = Json { ignoreUnknownKeys = true }

    // Контейнер — MP4 (ftyp isom), внутри AAC-LC. Расширение на диске .m4a: так
    // файл опознают и ExoPlayer, и JAudiotagger (теги MP4-атомами).
    private val aac320 = QualityTier("aac_320", "AAC 320", lossless = false, container = "m4a", bitrateKbps = 320, sampleRateHz = 44100)
    private val aac160 = QualityTier("aac_160", "AAC 160", lossless = false, container = "m4a", bitrateKbps = 160, sampleRateHz = 44100)
    private val aac96 = QualityTier("aac_96", "AAC 96", lossless = false, container = "m4a", bitrateKbps = 96, sampleRateHz = 44100)
    private val ladder = listOf(aac320, aac160, aac96)

    /**
     * Всегда `true` — и это не баг «забыли проверить токен», а свойство сервиса:
     * входа у JioSaavn нет. Проверять нечего, живой доступ подтвердит сам поиск.
     */
    override suspend fun isConfigured(): Boolean = true

    override suspend fun qualities(): List<QualityTier> = ladder

    /**
     * Качества КОНКРЕТНОГО трека. Общий список [qualities] говорит «320 бывает»,
     * а здесь решает флаг каталога: у трека без `320kbps=true` третьего пункта
     * нет, и предлагать его — значит обещать то, чего запись не отдаёт.
     *
     * `raw` может не нести флага (например, трек пришёл не из карточки) — тогда
     * честное «не знаю» и показываем общий список, пусть лестница подберёт.
     */
    override suspend fun qualitiesFor(track: Track): List<QualityTier> = tiersFor(track.raw["has320"])

    /** Ступени трека по каталожному флагу `320kbps` — чистая функция, см. тест. */
    internal fun tiersFor(has320: String?): List<QualityTier> = when (has320) {
        "true" -> ladder
        "false" -> listOf(aac160, aac96)
        else -> ladder
    }

    // ── поиск ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): MediaSelection {
        val q = query.trim()
        if (q.isEmpty()) return MediaSelection(kind = MediaKind.TRACK)
        val tracks = attempt {
            searchResults("search.getResults", q).mapNotNull { trackFromItem(it) }
        }.getOrDefault(emptyList())
        // Плитка «Альбомы» пустая без отдельного вызова: getResults отдаёт только
        // песни. Тот же дефект, что ловили у Tidal/Qobuz/Beatport.
        val albums = attempt {
            searchResults("search.getAlbumResults", q).mapNotNull { albumFromContainer(it, null) }
        }.getOrDefault(emptyList())
        return MediaSelection(
            kind = if (tracks.isEmpty() && albums.isNotEmpty()) MediaKind.ALBUM else MediaKind.TRACK,
            tracks = tracks,
            albums = albums,
        )
    }

    private suspend fun searchResults(call: String, query: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val url = apiUrl().addQueryParameter("__call", call)
            .addQueryParameter("q", query).addQueryParameter("n", "20").addQueryParameter("p", "1").build().toString()
        val root = getObject(url).jsonObject
        root["results"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    }

    // ── ссылка ───────────────────────────────────────────────────────────────

    override suspend fun resolve(url: String): MediaSelection? {
        val u = url.lowercase()
        if ("jiosaavn.com" !in u) return null
        // Тип берём из пути; токен — ПОСЛЕДНИЙ сегмент перма-ссылки (см. [tokenOf]).
        val (type, token) = when {
            "/song/" in u -> "song" to tokenOf(url)
            "/album/" in u -> "album" to tokenOf(url)
            "/playlist/" in u || "/featured/" in u -> "playlist" to tokenOf(url)
            else -> return null
        }
        if (token.isBlank()) return null
        val card = apiCard(token, type) ?: return null
        return when (type) {
            "song" -> {
                val item = card.first ?: return null
                val tr = trackFromItem(item) ?: return null
                MediaSelection(kind = MediaKind.TRACK, tracks = listOf(tr))
            }
            "album" -> {
                val meta = card.second
                val items = cardItems(meta)
                val tracks = items.mapNotNull { trackFromItem(it) }
                if (tracks.isEmpty()) throw IOException(EngineErrors.NOT_FOUND)
                MediaSelection(
                    kind = MediaKind.ALBUM,
                    containerTitle = plain(meta["title"] ?: meta["name"]),
                    tracks = tracks,
                    albums = listOfNotNull(albumFromContainer(meta, tracks.size)),
                )
            }
            else -> { // playlist
                val meta = card.second
                val tracks = cardItems(meta).mapNotNull { trackFromItem(it) }
                if (tracks.isEmpty()) throw IOException(EngineErrors.NOT_FOUND)
                MediaSelection(
                    kind = MediaKind.PLAYLIST,
                    containerTitle = plain(meta["title"] ?: meta["name"]),
                    tracks = tracks,
                )
            }
        }
    }

    /**
     * Полный ответ `webapi.get` по токену. Возвращает (главный объект-трек для
     * `song`, либо null — для контейнера; сам контейнер-JSON с полем `list`).
     *
     * Ловушка, о которой спотыкаются: ручка понимает ТОЛЬКО токен из перма-URL
     * (`.../album/<slug>/<TOKEN>`). На числовой `albumid` она НЕ ошибается —
     * молча отдаёт ЧУЖУЮ пустую карточку (name '', year '0', primary_artists
     * null). Поэтому токен берём строго из хвоста ссылки, а не из числа.
     */
    private suspend fun apiCard(token: String, type: String): Pair<JsonObject?, JsonObject>? =
        withContext(Dispatchers.IO) {
            val url = apiUrl().addQueryParameter("__call", "webapi.get")
                .addQueryParameter("token", token).addQueryParameter("type", type).build().toString()
            val raw = runCatching { getObject(url) }.getOrNull() ?: return@withContext null
            val root = raw.jsonObject
            if (type == "song") {
                val song = root["songs"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@withContext null
                song to song
            } else {
                null to root
            }
        }

    /** Треки альбома/плейлиста лежат в `list` (форма та же, что у поиска). */
    private fun cardItems(meta: JsonObject): List<JsonObject> =
        meta["list"]?.jsonArray?.map { it.jsonObject } ?: meta["tracks"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

    // ── поток/скачивание ─────────────────────────────────────────────────────

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val (tier, url) = resolveStream(track, desiredKbps(preference))
        return StreamInfo(
            url = url,
            quality = tier,
            // decryption = null: файл ЧИСТЫЙ. Никакой Blowfish/AES на лету —
            // расшифрована сама ССЫЛКА, а не байты. Именно это и позволяет
            // воспроизведению и загрузке идти общим путём без правок в их слое.
            decryption = null,
            headers = mapOf("User-Agent" to UA),
        )
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val track = request.track
        val want = request.forcedQualityId?.let { forcedKbps(it) } ?: desiredKbps(request.qualityPreference)
        val (tier, url) = try {
            resolveStream(track, want)
        } catch (e: Exception) {
            if (isJobCancellation(e)) throw e
            throw IOException(e.message ?: EngineErrors.TRACK_UNAVAILABLE)
        }
        emit(DownloadEvent.Log("JioSaavn: ${tier.label}"))
        val out = File(cacheDir, "js_${track.id.ifBlank { track.title.hashCode().toString() }}.m4a")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                out.delete()
                throw IOException(EngineErrors.code(EngineErrors.HTTP, "${resp.code}"))
            }
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
        if (out.length() < 8192) {
            out.delete()
            throw IOException(EngineErrors.EMPTY_STREAM)
        }
        emit(DownloadEvent.Done(out.absolutePath, tier, out.length()))
    }.flowOn(Dispatchers.IO)

    /**
     * Расшифровать ссылку и спуститься по лестнице до первой существующей ступени.
     *
     * Возвращает НЕ только URL, но и ТУ ступень, что реально отдаётся — ею и
     * подписываем результат, чтобы «показанное качество» = «полученный файл».
     * Выше [wantKbps] не поднимаемся: трек без 320 на запрос 320 просто спускается
     * к 160, а не получает 404.
     */
    private suspend fun resolveStream(track: Track, wantKbps: Int): Pair<QualityTier, String> =
        withContext(Dispatchers.IO) {
            val enc = mediaUrlOf(track) ?: throw IOException(EngineErrors.TRACK_UNAVAILABLE)
            val base = decryptMediaUrl(enc)
            pickTier(base, wantKbps) { tierServed(it) }
        }

    /**
     * Выбор ступени ЧИСТОЙ функцией, чтобы её можно было проверить без сети:
     * [served] — то, в живом пути делает HEAD.
     *
     * `served = { false }` — это и есть трек без 320: в каталоге JioSaavn флаг
     * `320kbps` сегодня `true` у всех 200 проверенных 22.09.2026 треков, так что
     * настоящую ветку «спустились» из сети не вытащить — проверяем её здесь.
     */
    internal fun pickTier(base: String, wantKbps: Int, served: (String) -> Boolean): Pair<QualityTier, String> {
        if (!base.endsWith(".mp4")) {
            // Неизвестный вид ссылки (без ступени в хвосте) — берём как есть и
            // подбираем подпись тира по битрейту из имени, иначе — потолок.
            return (ladder.firstOrNull { it.bitrateKbps == wantKbps } ?: aac320) to base
        }
        val step = Regex("_\\d+\\.mp4$")
        for (tier in ladder.filter { (it.bitrateKbps ?: 0) <= wantKbps }) {
            val cand = base.replace(step, "_${tier.bitrateKbps}.mp4")
            if (served(cand)) return tier to cand
        }
        // Лестница не нашла ничего — последняя попытка на исходной ссылке.
        val fallback = ladder.last()
        return fallback to base.replace(step, "_${fallback.bitrateKbps}.mp4")
    }

    /**
     * Расшифрованный `encrypted_media_url` трека. Из карточек и поиска он лежит в
     * `raw["enc"]`; если его нет (трек прилетел в обход парсинга) — добираем
     * карточку по токену. Без токена и без ссылки делать нечего.
     */
    private suspend fun mediaUrlOf(track: Track): String? {
        track.raw["enc"]?.takeIf { it.isNotBlank() }?.let { return it }
        val token = track.raw["token"]?.takeIf { it.isNotBlank() } ?: track.id.takeIf { it.isNotBlank() }
            ?: return null
        val song = apiCard(token, "song")?.second ?: return null
        return str(moreInfo(song)["encrypted_media_url"])
    }

    /**
     * Отдаёт ли CDN эту ступень (200/206). `attempt`, а не `runCatching`:
     * отменённую загрузку HEAD-проверка должна прерывать, а не отвечать
     * «ступени нет» — иначе отмена молча превращается в вердикт о качестве.
     *
     * НЕ suspend: вызывается только из `withContext(Dispatchers.IO)` /
     * `flowOn(IO)` — блокирующий HEAD там законен, а чистому [pickTier] нужна
     * обычная функция-предикат.
     */
    private fun tierServed(url: String): Boolean = attempt {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            r.close()
            r.code == 200 || r.code == 206
        }
    }.getOrDefault(false)

    // ── разбор сущностей ─────────────────────────────────────────────────────

    /**
     * Трек из «трекоподобного» объекта — одну и ту же форму несут результат
     * поиска, элемент `list` альбома/плейлиста и карточка `song`. Всё ценное
     * лежит в `more_info`; год, картинка, `title` — наверху.
     */
    private fun trackFromItem(el: JsonObject): Track? {
        val mi = moreInfo(el)
        val perma = str(el["perma_url"]).orEmpty()
        val token = tokenOf(perma)
        val title = plain(el["title"]) ?: plain(mi["song"]) ?: return null
        if (title.isBlank()) return null
        val albumToken = tokenOf(plain(mi["album_url"]).orEmpty())
        return Track(
            id = token.ifBlank { plain(el["id"]).orEmpty() },
            title = title,
            artist = artistOf(el, mi),
            service = Service.JIOSAAVN,
            albumTitle = plain(mi["album"]) ?: title,
            albumArtist = artistOf(el, mi),
            durationMs = plain(mi["duration"])?.toLongOrNull()?.times(1000),
            year = plain(el["year"])?.toIntOrNull(),
            label = plain(mi["label"]),
            copyright = plain(mi["copyright_text"]),
            releaseDate = plain(mi["release_date"]),
            // Обложку всегда тянем 500×500: поиск даёт 150×150, а 500 — потолок
            // CDN (1000 и выше уже 404). В тег просить 150 — значит портить файл.
            artworkUrl = bigCover(str(el["image"])),
            raw = buildMap {
                put("token", token)
                put("enc", plain(mi["encrypted_media_url"]).orEmpty())
                plain(mi["320kbps"])?.let { put("has320", it) }
                if (albumToken.isNotBlank()) put("albumToken", albumToken)
            },
        )
    }

    /** Альбом/плейлист-контейнер (форма та же: `title`, `more_info`, `list`). */
    private fun albumFromContainer(el: JsonObject, trackCount: Int?): Album? {
        val title = plain(el["title"]) ?: plain(el["name"]) ?: return null
        if (title.isBlank()) return null
        val token = tokenOf(plain(el["perma_url"]).orEmpty()).ifBlank { plain(el["id"]).orEmpty() }
        if (token.isBlank()) return null
        val mi = moreInfo(el)
        val n = trackCount ?: (plain(mi["song_count"])?.toIntOrNull() ?: plain(el["list_count"])?.toIntOrNull())
        return Album(
            id = token,
            title = title,
            artist = artistOf(el, mi),
            service = Service.JIOSAAVN,
            year = plain(el["year"])?.toIntOrNull(),
            trackCount = n,
            artworkUrl = bigCover(str(el["image"])),
            url = plain(el["perma_url"]),
            label = plain(mi["label"]),
            copyright = plain(mi["copyright_text"]),
            releaseDate = plain(mi["release_date"]),
        )
    }

    /**
     * Исполнитель — ТОЛЬКО музыкальный кредит. В `artistMap.artists` на
     * саундтреках сидят актёры фильма (у «Kesariya» — Ranbir Kapoor, Alia Bhatt),
     * и им в тег артиста попасть нельзя. Берём `primary_artists`; запас — строка
     * `subtitle`/`music`, если дерева артистов нет вовсе.
     */
    private fun artistOf(el: JsonObject, mi: JsonObject): String {
        val map = mi["artistMap"]?.jsonObject
        val primary = map?.get("primary_artists")?.jsonArray
            ?.mapNotNull { str(it.jsonObject["name"]) }?.filter { it.isNotBlank() }
            ?.joinToString(", ")
        if (!primary.isNullOrBlank()) return primary
        return (plain(el["subtitle"]) ?: plain(mi["music"]) ?: "").trim()
    }

    // ── разбор JSON ──────────────────────────────────────────────────────────

    private fun moreInfo(el: JsonObject): JsonObject =
        el["more_info"]?.jsonObject ?: JsonObject(emptyMap())

    /** Строка примитива без разбора: пустая/нулевая/не-примитив → null. */
    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        runCatching { (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotBlank() }

    /** То же, но со снятием HTML-entities: JioSaavn экранирует текст даже в JSON. */
    private fun plain(el: kotlinx.serialization.json.JsonElement?): String? =
        str(el)?.let {
            it.replace("&amp;", "&").replace("&#38;", "&").replace("&apos;", "'")
                .replace("&quot;", "\"").replace("&#X27;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
        }

    /** Токен = последний сегмент пути после отбрасывания query/хэша/слэшей. */
    private fun tokenOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return path.substringAfterLast('/').takeIf { it.isNotBlank() && '/' !in it }.orEmpty()
    }

    /** `-150x150.jpg` → `-500x500.jpg`; 500 — потолок CDN, выше 404. */
    private fun bigCover(url: String?): String? =
        url?.replace(Regex("([_-])(?:50x50|150x150)(\\.\\w+)$"), "$1" + "500x500" + "$2")

    // ── предпочтения качества ────────────────────────────────────────────────

    /** До какой ступени просить. Глобальный список тиров переводим в kbps. */
    internal fun desiredKbps(preference: List<String>): Int {
        for (id in preference) {
            val k = forcedKbps(id)
            if (k != null) return k
            val s = id.lowercase()
            when {
                s.contains("320") || s.contains("256") -> return 320
                s.contains("160") -> return 160
                s.contains("128") || s.contains("96") -> return 96
                // Просящий lossless/hi-res выше нашего потолка всё равно получит
                // 320: у JioSaavn его нет, и придумать lossless — значит солгать.
                s.startsWith("flac") || "lossless" in s || "hires" in s || "hi_res" in s || "hifi" in s -> return 320
                s.startsWith("aac") || s.startsWith("mp3") -> return 320
            }
        }
        return 320   // пусто/непонятно — лучший доступный тир сервиса
    }

    /** Битрейт из НАШЕГО собственного id тира («aac_160» → 160).
     *
     * Якорь на `aac_` обязателен. Без него сюда попадал общий список
     * предпочтений проекта, который начинается с `flac_24` и `flac_16`, —
     * а это ГЛУБИНА В БИТАХ, не килобиты. Число выдёргивалось как «хочу
     * 24 кбит/с», лестница отбрасывала все три ступени (ни одна не ≤ 24),
     * срабатывал запасной путь с `ladder.last()`, и в эмуляторе всегда
     * тянулось 96 вместо 320. Поймано владельцем 22.09.2026 на живом
     * воспроизведении.
     */
    internal fun forcedKbps(id: String?): Int? =
        id?.lowercase()?.let { Regex("^aac_(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    // ── транспорт и крипто ───────────────────────────────────────────────────

    private fun apiUrl(): okhttp3.HttpUrl.Builder =
        okhttp3.HttpUrl.Builder().scheme("https").host("www.jiosaavn.com").addPathSegment("api.php")
            .addQueryParameter("_format", "json").addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4").addQueryParameter("ctx", "web6dot0")

    private fun getObject(url: String): kotlinx.serialization.json.JsonElement {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException(EngineErrors.code(EngineErrors.HTTP, "${r.code}"))
            val body = r.body?.string() ?: throw IOException(EngineErrors.EMPTY_STREAM)
            return json.parseToJsonElement(body)
        }
    }

    /**
     * DES-ECB (ключ `38346591`, PKCS#5) по base64 из `encrypted_media_url` →
     * прямой URL на `aac.saavncdn.com`. Только javax.crypto — новой зависимости
     * нет (на этой машине реестр Google отдаёт 404, тянуть AndroidX нельзя).
     */
    private fun decryptMediaUrl(enc: String): String {
        val clean = enc.trim().replace("=", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val data = java.util.Base64.getDecoder().decode(padded)
        val key = SecretKeyFactory.getInstance("DES").generateSecret(DESKeySpec(DES_KEY))
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, key) }
        val url = String(cipher.doFinal(data), Charsets.UTF_8).trim()
        if (url.isBlank()) throw IOException(EngineErrors.TRACK_UNAVAILABLE)
        // http → https: часть сборок Android запрещает открытый текст, и такая
        // ссылка молча не открылась бы ни в плеере, ни в загрузчике.
        return url.replace("http://", "https://")
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        // Ключ DES-расшифровки публичной ссылки JioSaavn (константа протокола, не
        // секрет учётки — аккаунта здесь нет). Источник подхода — описан выше.
        private val DES_KEY = "38346591".toByteArray(Charsets.UTF_8)
    }
}
