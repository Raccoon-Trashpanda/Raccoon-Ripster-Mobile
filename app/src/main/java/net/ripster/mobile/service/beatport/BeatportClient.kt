package net.ripster.mobile.service.beatport

import net.ripster.mobile.core.errors.EngineErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Beatport — нативно, без ПК. Client-id от Serato DJ Lite, 4-шаговый OAuth
 * (логин/пароль синкаются с ПК или вводятся вручную), дальше `api.beatport.com/v4`.
 *
 * Поток: `catalog/tracks/{id}/download?quality=lossless|high|medium` отдаёт
 * `{"location": "<прямой URL>"}` — FLAC (lossless) или AAC (high/medium), без
 * расшифровки. Нужна подписка Beatport Streaming; без неё придёт ошибка/превью.
 */
class BeatportClient(
    private val username: String?,
    private val password: String?,
    private val cacheDir: File,
) : ServiceClient {

    override val service = Service.BEATPORT

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var accessToken = ""
    @Volatile private var refreshToken = ""
    @Volatile private var expiresAt = 0L

    private val flac = QualityTier("flac", "FLAC (Lossless)", lossless = true, container = "flac", bitDepth = 16, sampleRateHz = 44100)
    private val aac = QualityTier("aac_256", "AAC 256", lossless = false, container = "m4a", bitrateKbps = 256)
    private val aacLo = QualityTier("aac_128", "AAC 128", lossless = false, container = "m4a", bitrateKbps = 128)

    // OAuth-шаги нуждаются в cookie-сессии и в чтении Location вручную.
    private val jar = object : CookieJar {
        private val store = HashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { store[url.host] = cookies }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
    }
    private val authHttp: OkHttpClient by lazy {
        RipsterHttp.client.newBuilder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build()
    }

    override suspend fun isConfigured(): Boolean = !username.isNullOrBlank() && !password.isNullOrBlank()

    override suspend fun qualities(): List<QualityTier> = listOf(flac, aac, aacLo)

    // ── auth ──────────────────────────────────────────────────────────────

    private suspend fun ensureToken(): Boolean = mutex.withLock {
        if (accessToken.isNotBlank() && expiresAt > System.currentTimeMillis() + 60_000) return true
        withContext(Dispatchers.IO) {
            if (refreshToken.isNotBlank() && runCatching { doRefresh() }.getOrDefault(false)) return@withContext true
            if (username.isNullOrBlank() || password.isNullOrBlank()) return@withContext false
            runCatching { doLogin(username, password) }.getOrDefault(false)
        }
    }

    private fun applyToken(body: String) {
        val o = json.parseToJsonElement(body).jsonObject
        accessToken = o["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        refreshToken = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: refreshToken
        val ttl = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        expiresAt = System.currentTimeMillis() + ttl * 1000
    }

    private fun doRefresh(): Boolean {
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID).add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken).build()
        RipsterHttp.client.newCall(Request.Builder().url("$BASE/auth/o/token/").post(form).build()).execute().use { r ->
            if (!r.isSuccessful) return false
            applyToken(r.body?.string().orEmpty())
            return accessToken.isNotBlank()
        }
    }

    private fun doLogin(user: String, pass: String): Boolean {
        val authParams = "?client_id=$CLIENT_ID&response_type=code&redirect_uri=" +
            java.net.URLEncoder.encode(REDIRECT, "UTF-8")
        // 1) старт — сервер ставит cookie сессии и 302
        authHttp.newCall(Request.Builder().url("$BASE/auth/o/authorize/$authParams")
            .header("User-Agent", WEB_UA).build()).execute().use { r ->
            if (r.code != 302) return false
        }
        // 2) логин
        val loginJson = """{"username":${jstr(user)},"password":${jstr(pass)}}"""
        authHttp.newCall(Request.Builder().url("$BASE/auth/login/")
            .header("User-Agent", WEB_UA)
            .post(loginJson.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) return false
        }
        // 3) снова authorize → 302 с ?code=
        authHttp.newCall(Request.Builder().url("$BASE/auth/o/authorize/$authParams")
            .header("User-Agent", WEB_UA).build()).execute().use { r ->
            if (r.code != 302) return false
            val loc = r.header("Location").orEmpty()
            val code = loc.substringAfter("code=", "").substringBefore("&")
            if (code.isBlank()) return false
            // 4) code → токены
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID).add("code", code)
                .add("grant_type", "authorization_code").add("redirect_uri", REDIRECT).build()
            RipsterHttp.client.newCall(Request.Builder().url("$BASE/auth/o/token/").post(form).build()).execute().use { t ->
                if (!t.isSuccessful) return false
                applyToken(t.body?.string().orEmpty())
                return accessToken.isNotBlank()
            }
        }
    }

    private suspend fun apiGet(endpoint: String, params: Map<String, String> = emptyMap()): String {
        if (!ensureToken()) throw IOException(EngineErrors.AUTH_FAILED)
        val url = "$BASE/$endpoint".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return withContext(Dispatchers.IO) {
            RipsterHttp.client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .header("User-Agent", "libbeatport/v2.8.2").build(),
            ).execute().use { r ->
                if (r.code == 401) { accessToken = ""; throw IOException(EngineErrors.TOKEN_INVALID) }
                if (!r.isSuccessful) throw IOException("Beatport ${url.encodedPath} -> HTTP ${r.code}")
                r.body?.string() ?: throw IOException("Beatport -> empty")
            }
        }
    }

    // ── mapping ───────────────────────────────────────────────────────────

    /** Beatport v4: секция бывает `[...]`, `{data:[...]}`, `{results:[...]}`, а
     *  верхний уровень — то `{key:...}`, то плоский `{data:[...]}`. */
    private fun rows(raw: String, key: String): List<JsonObject> {
        val root = json.parseToJsonElement(raw)
        fun toList(e: JsonElement?): List<JsonObject>? = when (e) {
            is JsonArray -> e.map { it.jsonObject }
            is JsonObject -> (e["data"] ?: e["results"])?.let { (it as? JsonArray)?.map { j -> j.jsonObject } }
            else -> null
        }
        if (root is JsonObject) {
            toList(root[key])?.let { return it }
            toList(root)?.let { return it }
        }
        (root as? JsonArray)?.let { return it.map { j -> j.jsonObject } }
        return emptyList()
    }

    private fun img(o: kotlinx.serialization.json.JsonObject?): String? {
        val uri = o?.get("image")?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
            ?: o?.get("image")?.jsonObject?.get("dynamic_uri")?.jsonPrimitive?.contentOrNull
        return uri?.replace("{w}", "600")?.replace("{h}", "600")
    }

    private fun trackOf(t: kotlinx.serialization.json.JsonObject): Track {
        val id = (t["id"]?.jsonPrimitive?.longOrNull ?: t["id"]?.jsonPrimitive?.intOrNull ?: 0).toString()
        val mix = t["mix_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = t["name"]?.jsonPrimitive?.contentOrNull.orEmpty() + if (mix.isNotBlank()) " ($mix)" else ""
        val artists = (t["artists"]?.jsonArray ?: emptyList())
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        val rel = t["release"]?.jsonObject
        val durMs = t["duration"]?.jsonObject?.get("milliseconds")?.jsonPrimitive?.longOrNull
            ?: t["length_ms"]?.jsonPrimitive?.longOrNull
        val artId = (t["artists"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("id"))?.jsonPrimitive?.longOrNull?.toString().orEmpty()
        return Track(
            id = id,
            title = name,
            artist = artists.joinToString(", "),
            service = Service.BEATPORT,
            albumTitle = rel?.get("name")?.jsonPrimitive?.contentOrNull,
            durationMs = durMs,
            isrc = t["isrc"]?.jsonPrimitive?.contentOrNull,
            year = (t["publish_date"]?.jsonPrimitive?.contentOrNull ?: "").take(4).toIntOrNull(),
            artworkUrl = img(rel) ?: img(t),
            raw = mapOf("bpId" to id, "artId" to artId),
        )
    }

    // ── ServiceClient ─────────────────────────────────────────────────────

    override suspend fun search(query: String): MediaSelection {
        val raw = apiGet("catalog/search/", mapOf("q" to query, "type" to "tracks", "per_page" to "25"))
        return MediaSelection(kind = MediaKind.TRACK, tracks = rows(raw, "tracks").map { trackOf(it) })
    }

    override suspend fun resolve(url: String): MediaSelection? {
        val m = Regex("""beatport\.com/(track|release)/[^/]+/(\d+)""").find(url) ?: return null
        val (kind, id) = m.destructured
        return when (kind) {
            "track" -> {
                val t = json.parseToJsonElement(apiGet("catalog/tracks/$id/")).jsonObject
                MediaSelection(kind = MediaKind.TRACK, tracks = listOf(trackOf(t)))
            }
            "release" -> {
                val rel = json.parseToJsonElement(apiGet("catalog/releases/$id/")).jsonObject
                val tracks = rows(apiGet("catalog/releases/$id/tracks/", mapOf("per_page" to "100")), "tracks")
                MediaSelection(
                    kind = MediaKind.ALBUM,
                    containerTitle = rel["name"]?.jsonPrimitive?.contentOrNull,
                    tracks = tracks.map { trackOf(it) },
                )
            }
            else -> null
        }
    }

    override suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo {
        val id = track.raw["bpId"] ?: throw IOException("Beatport: no track id")
        val wantLossless = preference.firstOrNull().let {
            it == null || it.startsWith("flac") || it == "lossless" || it.contains("hires")
        }
        val order = if (wantLossless) listOf("lossless" to flac, "high" to aac, "medium" to aacLo)
        else listOf("high" to aac, "medium" to aacLo, "lossless" to flac)
        var last: Exception? = null
        for ((q, tier) in order) {
            try {
                val body = apiGet("catalog/tracks/$id/download/", mapOf("quality" to q))
                val loc = json.parseToJsonElement(body).jsonObject["location"]?.jsonPrimitive?.contentOrNull
                if (!loc.isNullOrBlank()) return StreamInfo(url = loc, quality = tier)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Beatport: не удалось получить поток (нужна подписка Beatport Streaming)")
    }

    override suspend fun getArtist(artistId: String): net.ripster.mobile.core.pair.PcBridge.ArtistPage? {
        if (artistId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val info = json.parseToJsonElement(apiGet("catalog/artists/$artistId/")).jsonObject
                val name = info["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { return@runCatching null }
                val tracks = rows(apiGet("catalog/artists/$artistId/tracks/", mapOf("per_page" to "100")), "tracks")
                // Beatport отдаёт треки — группируем по релизу
                data class Acc(var title: String, var cover: String?, var date: String, var url: String, var n: Int)
                val byRel = LinkedHashMap<String, Acc>()
                for (el in tracks) {
                    val rel = el["release"]?.jsonObject ?: continue
                    val rid = rel["id"]?.jsonPrimitive?.longOrNull?.toString() ?: continue
                    val acc = byRel.getOrPut(rid) {
                        Acc(
                            rel["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            img(rel),
                            (el["publish_date"]?.jsonPrimitive?.contentOrNull ?: ""),
                            "https://www.beatport.com/release/x/$rid",
                            0,
                        )
                    }
                    acc.n++
                }
                val releases = byRel.map { (rid, a) ->
                    net.ripster.mobile.core.pair.PcBridge.ArtistRelease(
                        id = rid, title = a.title, coverUrl = a.cover,
                        year = a.date.take(4), date = a.date,
                        trackCount = a.n,
                        type = if (a.n in 1..3) "single" else "album",
                        url = a.url, service = "beatport",
                    )
                }.sortedByDescending { it.date }
                net.ripster.mobile.core.pair.PcBridge.ArtistPage(
                    name = name,
                    pictureUrl = img(info),
                    releases = releases,
                )
            }.getOrNull()
        }
    }

    override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
        val track = request.track
        val pref = request.forcedQualityId?.let { listOf(it) } ?: request.qualityPreference
        val si = streamInfo(track, pref)
        emit(DownloadEvent.Log("Beatport: ${si.quality.label}"))
        val out = File(cacheDir, "bp_${track.raw["bpId"]}.${si.quality.container}")
        val req = Request.Builder().url(si.url).header("User-Agent", "libbeatport/v2.8.2").build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException(EngineErrors.code(EngineErrors.HTTP, "HTTP ${r.code}"))
            val total = r.body?.contentLength()?.takeIf { it > 0 }
            val src = r.body?.byteStream() ?: throw IOException(EngineErrors.EMPTY_STREAM)
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024); var got = 0L
                while (true) {
                    val n = src.read(buf); if (n < 0) break
                    os.write(buf, 0, n); got += n
                    emit(DownloadEvent.Progress(total?.let { got.toFloat() / it }, got, total))
                }
            }
            emit(DownloadEvent.Done(out.absolutePath, si.quality, out.length()))
        }
    }.flowOn(Dispatchers.IO)

    private fun jstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val BASE = "https://api.beatport.com/v4"
        private const val CLIENT_ID = "Zy2K9Wvy6DkUds7g8s1GNMHfk17E5Ch2BWHlyaGY"
        private const val REDIRECT = "seratodjlite://beatport"
        private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
