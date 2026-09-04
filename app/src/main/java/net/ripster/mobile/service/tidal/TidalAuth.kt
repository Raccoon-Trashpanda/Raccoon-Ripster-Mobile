package net.ripster.mobile.service.tidal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

/**
 * OAuth 2.0 device-flow для Tidal (схема из OrpheusDL/streamrip). Секрет не
 * нужен — используется публичный client_id ТВ-приложения.
 *
 *  1. [startDevice] → показать пользователю `userCode` и открыть `verificationUri`.
 *  2. [pollDevice] в цикле, пока не вернётся [Tokens] (или ошибка/таймаут).
 *  3. [refresh] — обновить access_token по refresh_token.
 *
 * Хранимый credential (`TIDAL_OAUTH`) — JSON [Stored] (refresh_token +
 * countryCode). access_token живёт в памяти клиента.
 */
object TidalAuth {

    /**
     * Клиент для ВХОДА по коду устройства. Стримить им нельзя.
     *
     * У Tidal два разных клиента, и ни один не умеет обе вещи (проверено
     * 04.09.2026 на живом аккаунте): этот проходит device_authorization, но
     * запрос потока с его токеном отдаёт 401/4005 «Asset is not ready for
     * playback»; [STREAM_CLIENT_ID] отдаёт поток LOSSLESS, но на
     * device_authorization отвечает «Client is not a Limited Input Device».
     *
     * Мобилка использовала один этот клиент везде — поэтому Tidal логинился и
     * тут же отказывался качать, а наружу шло «токен истёк», хотя токен был
     * свежий. Теперь каждый клиент делает то, что умеет: вход — этим, обновление
     * и поток — стриминговым. Refresh-токен принимают оба (тоже проверено), так
     * что вход по коду и последующее скачивание сходятся.
     */
    const val CLIENT_ID = "zU4XHVVkc2tDPo4t"

    /** Клиент, которым Tidal реально отдаёт поток. Тот же, что в ПК-движке. */
    const val STREAM_CLIENT_ID = "km8T1xS355y7dd3H"
    private const val SCOPE = "r_usr+w_usr+w_sub"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DeviceStart(
        @SerialName("deviceCode") val deviceCode: String,
        @SerialName("userCode") val userCode: String,
        @SerialName("verificationUri") val verificationUri: String = "link.tidal.com",
        @SerialName("verificationUriComplete") val verificationUriComplete: String = "",
        @SerialName("expiresIn") val expiresIn: Int = 300,
        @SerialName("interval") val interval: Int = 2,
    )

    @Serializable
    data class Tokens(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in") val expiresIn: Int = 0,
        val user: TokenUser = TokenUser(),
    )

    @Serializable
    data class TokenUser(
        @SerialName("userId") val userId: Long = 0,
        @SerialName("countryCode") val countryCode: String = "US",
    )

    @Serializable
    data class Stored(
        val refreshToken: String = "",
        val countryCode: String = "US",
        /** Живой access-токен (приходит из синка с ПК). Пусто у device-flow. */
        val accessToken: String = "",
    )

    fun encodeStored(t: Tokens): String =
        json.encodeToString(Stored.serializer(), Stored(t.refreshToken, t.user.countryCode))

    /** Разобранный payload JWT Tidal (claims `type` и `cc`), или null. */
    private fun jwtClaims(token: String): String? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return@runCatching null
        val pad = payload.padEnd((payload.length + 3) / 4 * 4, '=')
        String(android.util.Base64.decode(pad, android.util.Base64.URL_SAFE), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Обернуть вставленный ВРУЧНУЮ токен в хранимый вид.
     *
     * Раздают ОБА вида, и на глаз они неотличимы — оба длинные JWT. Тип лежит
     * в самом токене (claim `type`): `o2_refresh` — долгоживущий refresh, его
     * надо класть в refreshToken и менять на access при каждом запуске;
     * `o2_access` — короткий (4 часа) access. Раньше вставленное всегда падало
     * в accessToken, и refresh-токен уходил в заголовок как Bearer → 401 при
     * заведомо рабочем токене (жалоба 03.09.2026).
     *
     * Регион — из claim `cc`, если он есть (у refresh-токена его нет); иначе US,
     * `ensureToken()` всё равно уточнит по обновлённому access-токену.
     */
    fun encodeAccessToken(token: String): String {
        val t = token.trim()
        val claims = jwtClaims(t)
        val cc = claims?.let { Regex(""""cc"\s*:\s*"([A-Z]{2})"""").find(it)?.groupValues?.get(1) } ?: "US"
        val isRefresh = claims?.contains("\"o2_refresh\"") == true
        return json.encodeToString(
            Stored.serializer(),
            if (isRefresh) Stored(refreshToken = t, countryCode = cc, accessToken = "")
            else Stored(refreshToken = "", countryCode = cc, accessToken = t),
        )
    }

    fun decodeStored(raw: String): Stored? =
        runCatching { json.decodeFromString(Stored.serializer(), raw) }.getOrNull()

    suspend fun startDevice(): DeviceStart = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val raw = post("https://auth.tidal.com/v1/oauth2/device_authorization", body)
        json.decodeFromString(DeviceStart.serializer(), raw)
    }

    /** null — авторизация ещё не подтверждена (продолжать поллинг). */
    suspend fun pollDevice(deviceCode: String): Tokens? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("scope", SCOPE)
            .build()
        val (code, raw) = postRaw("https://auth.tidal.com/v1/oauth2/token", body)
        when {
            code in 200..299 -> json.decodeFromString(Tokens.serializer(), raw)
            raw.contains("authorization_pending") -> null
            else -> throw IOException("Tidal auth: $raw")
        }
    }

    /**
     * Обновить access-токен по refresh.
     *
     * `scope` НЕ передаём. [SCOPE] нужен только на старте device-flow, где мы
     * сами просим права; при refresh OAuth возвращает те права, что уже есть у
     * токена. А запрашивать полный набор здесь прямо вредно: у раздаваемых
     * токенов часто нет `w_sub`, и Tidal отвечает
     * `400 invalid_scope: Requested scopes: [WRITE_SUBSCRIPTION, …]` — то есть
     * заведомо живой токен не обновлялся, а наружу шло «токен истёк»
     * (поймано на Galaxy A31 03.09.2026).
     */
    suspend fun refresh(refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        // Обновляемся под СТРИМИНГОВЫМ клиентом: именно его access-токен потом
        // пустят к потоку. Обновление под клиентом входа проходит успешно и даёт
        // токен, которым ничего не скачать, — ровно эта ловушка и стоила Tidal
        // на мобиле работоспособности.
        val body = FormBody.Builder()
            .add("client_id", STREAM_CLIENT_ID)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        json.decodeFromString(Tokens.serializer(), post("https://auth.tidal.com/v1/oauth2/token", body))
    }

    private fun post(url: String, body: FormBody): String {
        val (code, raw) = postRaw(url, body)
        if (code !in 200..299) throw IOException("Tidal $url -> HTTP $code: $raw")
        return raw
    }

    private fun postRaw(url: String, body: FormBody): Pair<Int, String> {
        val req = Request.Builder().url(url).post(body).build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            return r.code to (r.body?.string() ?: "")
        }
    }
}
