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
    private val cacheDir: java.io.File? = null,
) {
    private val bundleCache: java.io.File? get() = cacheDir?.let { java.io.File(it, "qobuz_bundle.txt") }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()

    @Volatile private var appId: String = ""
    @Volatile private var secrets: List<String> = emptyList()
    @Volatile private var authToken: String = ""
    /** Секрет, который реально дал рабочую подпись — кэшируем. */
    @Volatile private var goodSecret: String = ""
    /** Один раз добывали свежие app_id/секреты из bundle.js после отказа подписи. */
    @Volatile private var refreshedFromBundle: Boolean = false

    /** Есть чем логиниться (без сети). Для быстрой проверки готовности сервиса. */
    fun hasCredentials(): Boolean =
        !presetToken.isNullOrBlank() || (!email.isNullOrBlank() && !password.isNullOrBlank())

    /** true, если appId сейчас — зашитый запасной (скрейп не прошёл). Тогда
     *  ensureAuth не считается «готовым» и в следующий раз пробует настоящий. */
    @Volatile private var appIdIsFallback = false

    suspend fun ensureAuth(force: Boolean = false): Boolean = mutex.withLock {
        if (!force && authToken.isNotBlank() && appId.isNotBlank() && !appIdIsFallback) return true
        // Скрейп bundle.js — многомегабайтный; на медленном канале не влезал в
        // таймаут поиска (жалоба тестера, каждый Qobuz-поиск → «didn't respond
        // in time»). Ограничиваем его 12 с и падаем на зашитый запасной app_id —
        // для catalog/search достаточно валидного X-App-Id, подпись не нужна.
        val creds = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(12_000) {
                QobuzBundle.resolve(overrideAppId, overrideSecret, bundleCache)
            }
        }.getOrNull() ?: QobuzBundle.FALLBACK
        appIdIsFallback = creds === QobuzBundle.FALLBACK
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
        // Поиск: `catalog/search` у рабочих app_id больше не отдаёт результаты, а
        // свежескрейпленный app_id (798273057) сейчас 401. Рабочая связка (как в
        // ПК-Ripster): `track/search` + `app_id=312369995` + `X-User-Auth-Token`
        // (без токена total=0). Скрейп bundle.js для поиска вообще не нужен.
        //
        // ВСЕГДА 312369995: кастомный/синкнутый `overrideAppId` для ПОИСКА не
        // нужен и часто протухший — Qobuz отвечает 400 «bad app_id» (жалоба
        // тестера: вбил кривой app_id руками → каждый поиск 400). Свой app_id
        // имеет смысл только для подписи `getFileUrl` (скачивание).
        val searchAid = QobuzBundle.SEARCH_APP_ID
        val raw = getWithAppId(searchAid, "track/search", authed = true) {
            it.addQueryParameter("query", query)
            it.addQueryParameter("limit", "25")
            it.addQueryParameter("app_id", searchAid)
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

    /** Артист + все его альбомы (для дискографии без ПК). */
    suspend fun artist(id: String): net.ripster.mobile.service.qobuz.dto.QbArtistFull {
        ensureAuth()
        return json.decodeFromString(
            net.ripster.mobile.service.qobuz.dto.QbArtistFull.serializer(),
            get("artist/get") {
                it.addQueryParameter("artist_id", id)
                it.addQueryParameter("extra", "albums")
                it.addQueryParameter("limit", "200")
            },
        )
    }

    /** Прямая ссылка на файл нужного формата. Пробует секреты по очереди. */
    suspend fun fileUrl(trackId: String, formatId: Int): QbFileUrl {
        ensureAuth()
        tryFileUrl(trackId, formatId, appId, secrets)?.let { return it }

        // Все секреты дали 400/пустой url. Синхронизированный с ПК секрет мог
        // протухнуть (у ПК за спиной свой фолбэк streamrip, у нас — нет). ОДИН раз
        // добываем свежую пару из bundle.js веб-плеера. `app_id` из бандла Qobuz
        // сейчас часто не совпадает с рабочим (для этого пути/аккаунта), поэтому
        // НЕ затираем синхронизированный, а пробуем свежие секреты под ОБА id.
        if (!refreshedFromBundle) {
            refreshedFromBundle = true
            runCatching { QobuzBundle.resolve(null, null, bundleCache, forceFresh = true) }.getOrNull()?.let { fresh ->
                val pool = (fresh.secrets + secrets).distinct()
                for (aid in listOf(appId, fresh.appId).filter { it.isNotBlank() }.distinct()) {
                    tryFileUrl(trackId, formatId, aid, pool)?.let {
                        mutex.withLock { appId = aid; secrets = pool }
                        return it
                    }
                }
            }
        }
        throw IOException("Qobuz: не удалось подписать запрос файла — обнови app_id/app_secret в Настройках → Учётные записи")
    }

    /** Одна серия попыток по всем секретам под конкретным [aid]. null — мимо. */
    private suspend fun tryFileUrl(
        trackId: String, formatId: Int, aid: String, secretPool: List<String>,
    ): QbFileUrl? {
        val toTry = (if (goodSecret.isNotBlank()) listOf(goodSecret) else emptyList()) + secretPool
        for (secret in toTry.distinct()) {
            try {
                val ts = System.currentTimeMillis() / 1000
                // Подпись Qobuz: "trackgetFileUrl" + params БЕЗ имён-разделителей
                // + сырой timestamp + secret. Здесь годами лишним куском стоял
                // литерал "request_ts" перед $ts → md5 не совпадал → КАЖДАЯ
                // загрузка Qobuz падала «no streamable file», хотя аккаунт и
                // секрет верные. (сверено со streamrip client/qobuz.py)
                val sig = md5("trackgetFileUrlformat_id${formatId}intentstreamtrack_id${trackId}$ts$secret")
                val raw = getWithAppId(aid, "track/getFileUrl") {
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
            } catch (_: Exception) {
                // 400 = неверная подпись под этот app_id; пробуем следующий секрет
            }
        }
        return null
    }

    /** Брошено getWithAppId при 400 = Qobuz отверг app_id. Ловим в get() для
     *  само-лечения; если не помогло — конвертируется в юзер-сообщение. */
    private class StaleAppId : IOException("__qobuz_stale_appid__")

    private suspend fun get(
        path: String,
        authed: Boolean = true,
        params: (okhttp3.HttpUrl.Builder) -> Unit,
    ): String {
        try {
            return getWithAppId(appId, path, authed, params)
        } catch (_: StaleAppId) {
            // app_id протух — ОДИН раз добываем свежий из bundle.js и повторяем
            // (ровно как fileUrl() делает для подписи). Раньше поиск просто падал
            // с «обнови app_id вручную», хотя добыть новый мы умеем сами.
            val fresh = mutex.withLock {
                if (refreshedFromBundle) null else {
                    refreshedFromBundle = true
                    runCatching {
                        QobuzBundle.resolve(null, null, bundleCache, forceFresh = true)
                    }.getOrNull()
                }
            }
            if (fresh != null && fresh.appId.isNotBlank()) {
                mutex.withLock {
                    appId = fresh.appId
                    secrets = (fresh.secrets + secrets).distinct()
                }
                return getWithAppId(appId, path, authed, params)
            }
            throw IOException("__qobuz_stale_appid__")
        }
    }

    private suspend fun getWithAppId(
        aid: String,
        path: String,
        authed: Boolean = true,
        params: (okhttp3.HttpUrl.Builder) -> Unit,
    ): String {
        if (aid.isBlank()) throw IOException(
            "Qobuz: нет app_id — вставь app_id и app_secret в Настройках → Учётные записи " +
                "или войди по email/паролю.")
        val url = "https://www.qobuz.com/api.json/0.2/$path".toHttpUrl().newBuilder().apply(params).build()
        val req = Request.Builder()
            .url(url)
            .header("X-App-Id", aid)
            .apply { if (authed && authToken.isNotBlank()) header("X-User-Auth-Token", authToken) }
            .header("User-Agent", "RipsterMobile/0.1")
            .build()
        return withContext(Dispatchers.IO) {
            RipsterHttp.client.newCall(req).execute().use { r ->
                if (r.code == 401 && authed) throw IOException("__qobuz_bad_token__")
                // 400 у Qobuz на /catalog/search почти всегда = «Invalid or missing
                // app_id» (протух/пустой app_id), а не проблема самого запроса.
                // get() ловит это и один раз пере-скрейпит bundle.js.
                if (r.code == 400) throw StaleAppId()
                if (!r.isSuccessful) throw IOException("Qobuz: ошибка ${r.code} на ${url.encodedPath.substringAfterLast('/')}")
                r.body?.string() ?: throw IOException("Qobuz: пустой ответ")
            }
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return buildString { for (b in d) append("%02x".format(b)) }
    }
}
