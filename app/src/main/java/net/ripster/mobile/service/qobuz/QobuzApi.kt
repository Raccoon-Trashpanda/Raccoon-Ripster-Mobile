package net.ripster.mobile.service.qobuz

import net.ripster.mobile.core.errors.EngineErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.service.qobuz.dto.QbAlbumFull
import net.ripster.mobile.service.qobuz.dto.QbAlbumSearch
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
    /**
     * Что сервис сказал про наш токен: true — принял, false — отверг (401).
     *
     * Наружу это нужно затем, что «токен набран руками» и «токен рабочий» —
     * разные вещи, а решение «перекрывать ли ручной ввод синком с ПК»
     * зависит от второго. Сюда попадает ТОЛЬКО явный ответ сервиса: сетевой
     * сбой вердикта не даёт (иначе упавший Wi-Fi объявил бы живой токен
     * мёртвым и пустил бы ПК затирать ручной ввод).
     */
    private val onTokenVerdict: ((Boolean) -> Unit)? = null,
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
        if (!force && authToken.isNotBlank() && appId.isNotBlank()) return true
        // Ни поиску, ни скачиванию скрейп bundle.js больше НЕ нужен: встроенная
        // пара app_id↔secret ([QobuzBundle.FALLBACK]) подписывает getFileUrl и
        // отдаёт FLAC 24/96. Скрейп остался только как аварийный путь в
        // fileUrl(), если встроенная пара когда-нибудь протухнет. Это же убирает
        // «Qobuz didn't respond in time» — многомегабайтной загрузки на старте
        // просто нет.
        val custom = overrideAppId?.trim()?.takeIf { it.isNotBlank() }
        val customSec = overrideSecret?.trim()?.takeIf { it.isNotBlank() }
        val creds = when {
            // Своя пара перекрывает встроенную ТОЛЬКО целиком: чужой секрет к
            // чужому id не подойдёт, а половинка молча ломает подпись.
            custom != null && customSec != null -> QobuzBundle.Creds(custom, listOf(customSec))
            else -> QobuzBundle.FALLBACK
        }
        appIdIsFallback = customSec == null
        appId = creds.appId
        secrets = creds.secrets

        authToken = when {
            !presetToken.isNullOrBlank() -> presetToken.trim()
            !email.isNullOrBlank() && !password.isNullOrBlank() -> login(email, password)
            else -> return false
        }
        authToken.isNotBlank()
    }

    /** Тариф из последнего входа по email/паролю: (lossless, hires, offer, страна).
     *  null — входили токеном, ответа логина нет, тариф честно неизвестен. */
    @Volatile
    var subInfo: net.ripster.mobile.service.qobuz.dto.QbSubParams? = null
        private set

    @Volatile
    var country: String = ""
        private set

    private suspend fun login(email: String, password: String): String {
        val raw = get("user/login", authed = false) {
            it.addQueryParameter("email", email)
            it.addQueryParameter("password", password)
            it.addQueryParameter("app_id", appId)
        }
        val parsed = json.decodeFromString(QbLogin.serializer(), raw)
        subInfo = parsed.user.credential.parameters
        country = parsed.user.country
        return parsed.userAuthToken
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

    /** Альбомы по тому же запросу. Отдельный вызов: `track/search` их не отдаёт.
     *
     * Без него фильтр «Альбомы» в поиске был у Qobuz пуст всегда, и экран писал
     * «под этот фильтр ничего нет» — со стороны это неотличимо от сломанного
     * поиска (12.09.2026, тот же дефект был найден у Tidal). */
    suspend fun searchAlbums(query: String): QbAlbumSearch {
        ensureAuth()
        val searchAid = QobuzBundle.SEARCH_APP_ID
        val raw = getWithAppId(searchAid, "album/search", authed = true) {
            it.addQueryParameter("query", query)
            it.addQueryParameter("limit", "25")
            it.addQueryParameter("app_id", searchAid)
        }
        return json.decodeFromString(QbAlbumSearch.serializer(), raw)
    }

    suspend fun track(id: String): QbTrack {
        ensureAuth()
        return json.decodeFromString(QbTrack.serializer(), get("track/get") { it.addQueryParameter("track_id", id) })
    }

    suspend fun album(id: String): QbAlbumFull {
        ensureAuth()
        return json.decodeFromString(
            QbAlbumFull.serializer(),
            // БЕЗ `extra=tracks`. Qobuz такого значения не знает и отвечает
            // «400 Invalid argument: extra (accepted values are
            // albumsFromSameArtist, focus, focusAll, …)». Треки он и так кладёт
            // в ответ album/get — проверено запросом 06.09.2026: тринадцать
            // штук без единого extra.
            //
            // Из-за этого параметра ломались ВСЕ ссылки Qobuz: 400 внизу
            // трактовался как «протух app_id», код лез перескрейпить ключи, не
            // мог — и человек читал «введи app_id и app_secret вручную». Тестер
            // вводил, ключи были верные, и ничего не менялось.
            get("album/get") { it.addQueryParameter("album_id", id) },
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
        // ВСТРОЕННАЯ ПАРА КАК ЗАПАСНОЙ ХОД.
        //
        // Своя (или синкнутая с ПК) пара перекрывает встроенную целиком — и это
        // правильно, пока она рабочая. Но когда она НЕ рабочая, дальше шёл
        // сразу отказ «не удалось добыть ключи, введи app_id и app_secret
        // вручную», хотя рядом лежала заведомо рабочая FALLBACK
        // (312369995 + e79f8b9b…), которой мы даже не попробовали.
        //
        // Ровно так это и выглядело у тестеров 06.09.2026: приложение просило
        // ввести ключи, тестер вводил ВЕРНЫЕ, и ничего не менялось — потому что
        // проблема была не в том, что ключей нет, а в том, что мы отказывались
        // взять те, что уже есть. Сеть тут не нужна, попытка стоит один запрос.
        if (appId != QobuzBundle.FALLBACK.appId || secrets != QobuzBundle.FALLBACK.secrets) {
            android.util.Log.i(
                "RipsterQobuz",
                "own pair (appId=${appId.take(4)}…, secret ${secrets.firstOrNull()?.take(4) ?: "none"}…) " +
                    "failed to sign getFileUrl — trying the built-in pair",
            )
            tryFileUrl(trackId, formatId, QobuzBundle.FALLBACK.appId, QobuzBundle.FALLBACK.secrets)?.let {
                mutex.withLock {
                    appId = QobuzBundle.FALLBACK.appId
                    secrets = QobuzBundle.FALLBACK.secrets
                }
                    android.util.Log.i("RipsterQobuz", "built-in pair worked")
                return it
            }
        }

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
        throw IOException(EngineErrors.QOBUZ_KEYS)
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
                // 200 БЕЗ ссылки — это не «ключи плохие». Подпись сошлась,
                // сервис ответил и НАЗВАЛ причину в `restrictions`. Раньше мы
                // её выбрасывали, возвращали null, перебирали остальные
                // секреты и в конце писали «не удалось добыть ключи, введи
                // app_id вручную» — диагноз, не имеющий отношения к делу.
                // Тестер вводил верные ключи, и ничего не менялось.
                android.util.Log.w(
                    "RipsterQobuz",
                    "getFileUrl fmt=$formatId aid=${aid.take(4)}… sec=${secret.take(4)}…: " +
                        "HTTP 200 without url, body=${raw.take(200)}",
                )
                restrictionError(fu)?.let { throw it }
            } catch (e: Exception) {
                // Ловим ТОЛЬКО то, что и правда лечится следующим секретом.
                //
                // Здесь стояло `catch (_: Exception)` — и оно глотало ВСЁ,
                // включая «токен мёртв» (401). Наверху это превращалось в
                // «не удалось добыть ключи, введи app_id и app_secret вручную»,
                // и человек чинил ключи, когда сломан был токен. Ровно на этом
                // 06.09.2026 встали тестеры: ключи у них были верные.
                //
                // Мёртвый токен и любая неожиданная ошибка идут наверх как
                // есть: пусть человек прочитает НАСТОЯЩУЮ причину.
                val msg = e.message.orEmpty()
                android.util.Log.w(
                    "RipsterQobuz",
                    "getFileUrl fmt=$formatId aid=${aid.take(4)}… sec=${secret.take(4)}… failed: $msg",
                )
                val signatureIssue = "400" in msg || "__qobuz_bad_secret__" in msg
                if (!signatureIssue) throw e
            }
        }
        return null
    }

    /**
     * Ответ без ссылки, но с причиной → ошибка, которую не надо угадывать.
     *
     * null значит «причины сервис не назвал» — тогда молчим и пробуем
     * следующий секрет: вот там незнание действительно уместно.
     *
     * `FormatRestrictedByFormatAvailability` СЮДА НЕ ПОПАДАЕТ намеренно: это
     * «нет вот такого формата», и следующий формат в очереди может подойти —
     * так и работает перебор 27 → 7 → 6 → 5.
     */
    private fun restrictionError(fu: QbFileUrl): IOException? {
        val codes = fu.restrictions.map { it.code }
        if (codes.isEmpty()) return null
        return when {
            codes.any { it == "TrackRestrictedByPurchaseCredentials" } ->
                IOException(EngineErrors.QOBUZ_PURCHASE_ONLY)
            codes.any { it == "SampleRestrictedByRightHolders" } ->
                IOException(EngineErrors.QOBUZ_RIGHTS_BLOCKED)
            codes.all { it == "FormatRestrictedByFormatAvailability" } -> null
            else -> IOException(EngineErrors.code(EngineErrors.QOBUZ_RESTRICTED, codes.joinToString()))
        }
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
            net.ripster.mobile.core.errors.EngineErrors.QOBUZ_KEYS)
        val url = "https://www.qobuz.com/api.json/0.2/$path".toHttpUrl().newBuilder().apply(params).build()
        val req = Request.Builder()
            .url(url)
            .header("X-App-Id", aid)
            .apply { if (authed && authToken.isNotBlank()) header("X-User-Auth-Token", authToken) }
            .header("User-Agent", "RipsterMobile/0.1")
            .build()
        return withContext(Dispatchers.IO) {
            RipsterHttp.client.newCall(req).execute().use { r ->
                if (r.code == 401 && authed) {
                    onTokenVerdict?.invoke(false)
                    throw IOException("__qobuz_bad_token__")
                }
                // 400 у Qobuz на /catalog/search почти всегда = «Invalid or missing
                // app_id» (протух/пустой app_id), а не проблема самого запроса.
                // get() ловит это и один раз пере-скрейпит bundle.js.
                // 400 — НЕ синоним «протух app_id».
                //
                // Так было записано, и на этом всё и держалось: любой наш
                // неверный запрос объявлялся смертью ключей, код лез
                // перескрейпить bundle.js, не мог — и печатал человеку «введи
                // app_id и app_secret вручную». Тестер вводил ВЕРНЫЕ ключи, и
                // ничего не менялось, потому что чинили не то (06.09.2026).
                //
                // Читаем, что сказал сервис. Про app_id — верим и обновляем;
                // «Invalid argument» — это наша ошибка в запросе, и прятать её
                // за чужой причиной нельзя.
                if (r.code == 400) {
                    val body = runCatching { r.peekBody(2048).string() }.getOrDefault("")
                    val aboutAppId = "app_id" in body.lowercase()
                    if (aboutAppId) throw StaleAppId()
                    throw IOException(EngineErrors.code(EngineErrors.HTTP, "400 " + body.take(160)))
                }
                if (!r.isSuccessful) throw IOException(EngineErrors.code(EngineErrors.HTTP, "${r.code} ${url.encodedPath.substringAfterLast('/')}"))
                // Ответ пришёл с нашим токеном и сервис не возразил — значит
                // токен живой. Снимаем прежний приговор, если он был.
                if (authed && authToken.isNotBlank()) onTokenVerdict?.invoke(true)
                r.body?.string() ?: throw IOException(EngineErrors.EMPTY_STREAM)
            }
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return buildString { for (b in d) append("%02x".format(b)) }
    }
}
