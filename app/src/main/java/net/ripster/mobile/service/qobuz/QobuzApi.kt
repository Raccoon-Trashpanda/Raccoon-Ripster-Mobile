package net.ripster.mobile.service.qobuz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.service.qobuz.dto.QbAlbumFull
import net.ripster.mobile.service.qobuz.dto.QbFileUrl
import net.ripster.mobile.service.qobuz.dto.QbLogin
import net.ripster.mobile.service.qobuz.dto.QbSearch
import net.ripster.mobile.service.qobuz.dto.QbTrack
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * Клиент Qobuz API 0.2. Прямые FLAC/MP3-ссылки, БЕЗ расшифровки — отличие
 * от Deezer. Подпись `getFileUrl` — md5 от конкатенации параметров с
 * секретом (схема streamrip).
 *
 * Аутентификация: либо готовый `X-User-Auth-Token` (из настроек), либо
 * `user/login` по email+паролю. app_id/секрет — из [QobuzBundle].
 */
class QobuzApi(
    private val email: String?,
    private val password: String?,
    private val presetToken: String?,
    private val overrideAppId: String?,
    private val overrideSecret: String?,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()

    @Volatile private var appId: String = ""
    @Volatile private var secrets: List<String> = emptyList()
    @Volatile private var authToken: String = ""
    /** Секрет, который реально дал рабочую подпись — кэшируем. */
    @Volatile private var goodSecret: String = ""

    suspend fun ensureAuth(force: Boolean = false): Boolean = mutex.withLock {
        if (!force && authToken.isNotBlank() && appId.isNotBlank()) return true
        val creds = QobuzBundle.resolve(overrideAppId, overrideSecret)
        appId = creds.appId
        secrets = creds.secrets

        authToken = when {
            !presetToken.isNullOrBlank() -> presetToken.trim()
            !email.isNullOrBlank() && !password.isNullOrBlank() -> login(email, password)
            else -> return false
        }
        authToken.isNotBlank()
    }

    private suspend fun login(email: String, password: String): String {
        val raw = get("user/login", authed = false) {
            it.addQueryParameter("email", email)
            it.addQueryParameter("password", password)
            it.addQueryParameter("app_id", appId)
        }
        return json.decodeFromString(QbLogin.serializer(), raw).userAuthToken
    }

    suspend fun search(query: String): QbSearch {
        ensureAuth()
        val raw = get("catalog/search") {
            it.addQueryParameter("query", query)
            it.addQueryParameter("type", "tracks")
            it.addQueryParameter("limit", "25")
        }
        return json.decodeFromString(QbSearch.serializer(), raw)
    }

    suspend fun track(id: String): QbTrack {
        ensureAuth()
        return json.decodeFromString(QbTrack.serializer(), get("track/get") { it.addQueryParameter("track_id", id) })
    }

    suspend fun album(id: String): QbAlbumFull {
        ensureAuth()
        return json.decodeFromString(
            QbAlbumFull.serializer(),
            get("album/get") { it.addQueryParameter("album_id", id); it.addQueryParameter("extra", "tracks") },
        )
    }

    /** Прямая ссылка на файл нужного формата. Пробует секреты по очереди. */
    suspend fun fileUrl(trackId: String, formatId: Int): QbFileUrl {
        ensureAuth()
        val toTry = if (goodSecret.isNotBlank()) listOf(goodSecret) + secrets else secrets
        var last: Exception? = null
        for (secret in toTry.distinct()) {
            try {
                val ts = System.currentTimeMillis() / 1000
                val sig = md5("trackgetFileUrlformat_id${formatId}intentstreamtrack_id${trackId}request_ts$ts$secret")
                val raw = get("track/getFileUrl") {
                    it.addQueryParameter("request_ts", ts.toString())
                    it.addQueryParameter("request_sig", sig)
                    it.addQueryParameter("track_id", trackId)
                    it.addQueryParameter("format_id", formatId.toString())
                    it.addQueryParameter("intent", "stream")
                }
                val fu = json.decodeFromString(QbFileUrl.serializer(), raw)
                if (fu.url.isNotBlank()) {
                    goodSecret = secret
                    return fu
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Qobuz: no valid app secret for getFileUrl")
    }

    private suspend fun get(
        path: String,
        authed: Boolean = true,
        params: (okhttp3.HttpUrl.Builder) -> Unit,
    ): String {
        val url = "https://www.qobuz.com/api.json/0.2/$path".toHttpUrl().newBuilder().apply(params).build()
        val req = Request.Builder()
            .url(url)
            .header("X-App-Id", appId)
            .apply { if (authed && authToken.isNotBlank()) header("X-User-Auth-Token", authToken) }
            .header("User-Agent", "RipsterMobile/0.1")
            .build()
        return withContext(Dispatchers.IO) {
            RipsterHttp.client.newCall(req).execute().use { r ->
                if (r.code == 401 && authed) throw IOException("Qobuz: 401 (auth token invalid)")
                if (!r.isSuccessful) throw IOException("Qobuz ${url.encodedPath} -> HTTP ${r.code}")
                r.body?.string() ?: throw IOException("Qobuz ${url.encodedPath} -> empty")
            }
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return buildString { for (b in d) append("%02x".format(b)) }
    }
}
