package net.ripster.mobile.service.deezer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.service.deezer.dto.DzGwEnvelope
import net.ripster.mobile.service.deezer.dto.DzMediaResponse
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Клиент приватного API Deezer (`gw-light.php`) + media-API (`get_url`).
 * Не порт нашего кода — схема из deemix/streamrip.
 *
 *  1. `deezer.getUserData` с cookie `arl` → `checkForm` (api_token),
 *     `USER_ID` (0 = ARL мёртв), `license_token`.
 *  2. `song.getData` → `TRACK_TOKEN` + размеры форматов + метаданные.
 *  3. `media.deezer.com/v1/get_url` (license_token + track_token) →
 *     URL зашифрованного потока (`BF_CBC_STRIPE`).
 *
 * Публичный поиск/метаданные идут мимо — через `api.deezer.com` без ARL
 * (см. [DeezerClient]).
 */
class DeezerGw(private val arl: String) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()

    @Volatile private var apiToken: String = ""
    @Volatile private var licenseToken: String = ""
    @Volatile private var userId: Long = -1

    private val jar = object : CookieJar {
        private val store = HashMap<String, Cookie>()
        init {
            // ARL кладём руками — Deezer его сам не выставит.
            store["arl"] = Cookie.Builder()
                .name("arl").value(arl).domain("deezer.com").path("/").build()
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) store[c.name] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.toList()
    }

    private val http: OkHttpClient by lazy {
        RipsterHttp.client.newBuilder().cookieJar(jar).build()
    }

    /** true, если ARL живой (USER_ID != 0). Кэширует токены. */
    suspend fun ensureSession(force: Boolean = false): Boolean = mutex.withLock {
        if (!force && userId > 0) return true
        val env = gw("deezer.getUserData", "", "{}")
        val u = env.results.user
        userId = u.userId
        apiToken = env.results.checkForm
        licenseToken = u.options.licenseToken
        userId > 0
    }

    suspend fun songData(sngId: String): DzGwEnvelope {
        ensureSession()
        val env = gw("song.getData", apiToken, """{"sng_id":"$sngId"}""")
        if (!env.ok && env.errorText.contains("TOKEN", true)) {
            ensureSession(force = true)
            return gw("song.getData", apiToken, """{"sng_id":"$sngId"}""")
        }
        return env
    }

    /** URL зашифрованного потока для трека. `formats` — от лучшего к худшему. */
    suspend fun mediaUrl(trackToken: String, formats: List<String>): Pair<String, String>? {
        ensureSession()
        val fmtJson = formats.joinToString(",") { """{"cipher":"BF_CBC_STRIPE","format":"$it"}""" }
        val body = """{"license_token":"$licenseToken","media":[{"type":"FULL","formats":[$fmtJson]}],"track_tokens":["$trackToken"]}"""
        val req = Request.Builder()
            .url("https://media.deezer.com/v1/get_url")
            .post(body.toRequestBody(JSON_MT))
            .header("User-Agent", UA)
            .build()
        val raw = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Deezer media get_url -> HTTP ${r.code}")
                r.body?.string() ?: throw IOException("Deezer media get_url -> empty body")
            }
        }
        val resp = json.decodeFromString(DzMediaResponse.serializer(), raw)
        val entry = resp.data.firstOrNull()?.media?.firstOrNull { it.sources.isNotEmpty() } ?: return null
        val url = entry.sources.first().url
        return url to entry.format
    }

    /**
     * Поиск через приватный gw-light В КОНТЕКСТЕ АККАУНТА (страна = страна ARL).
     * Публичный `api.deezer.com/search` геолоцирует по IP запроса, и релиз,
     * которого ещё нет в каталоге этой территории, не находится (жалоба:
     * турецкий ARL не видит релиз, британский видит). Тут — каталог страны
     * аккаунта. Возвращает сырой JSON `deezer.pageSearch`.
     */
    suspend fun searchTracksRaw(query: String, limit: Int): String {
        ensureSession()
        val q = query.replace("\"", "\\\"")
        val body = """{"query":"$q","start":0,"nb":$limit,"top_tracks":true}"""
        var raw = gwRaw("deezer.pageSearch", apiToken, body)
        if (raw.contains("\"VALID_TOKEN_REQUIRED\"") || raw.contains("\"error\":{\"GATEWAY")) {
            ensureSession(force = true)
            raw = gwRaw("deezer.pageSearch", apiToken, body)
        }
        return raw
    }

    private suspend fun gw(method: String, token: String, bodyJson: String): DzGwEnvelope =
        json.decodeFromString(DzGwEnvelope.serializer(), gwRaw(method, token, bodyJson))

    private suspend fun gwRaw(method: String, token: String, bodyJson: String): String {
        val url = "https://www.deezer.com/ajax/gw-light.php".toHttpUrl()
            .newBuilder()
            .addQueryParameter("method", method)
            .addQueryParameter("input", "3")
            .addQueryParameter("api_version", "1.0")
            .addQueryParameter("api_token", token)
            .build()
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MT))
            .header("User-Agent", UA)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Deezer $method -> HTTP ${r.code}")
                r.body?.string() ?: throw IOException("Deezer $method -> empty body")
            }
        }
    }

    companion object {
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
