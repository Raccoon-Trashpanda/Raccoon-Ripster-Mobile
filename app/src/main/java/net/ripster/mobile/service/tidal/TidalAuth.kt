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

    const val CLIENT_ID = "zU4XHVVkc2tDPo4t"
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

    /** Обернуть вставленный ВРУЧНУЮ access-токен в хранимый вид. Регион берём из
     *  самого JWT (claim `cc`), иначе US — `ensureToken()` всё равно уточнит. */
    fun encodeAccessToken(accessToken: String): String {
        val cc = runCatching {
            val payload = accessToken.split('.').getOrNull(1) ?: return@runCatching null
            val pad = payload.padEnd((payload.length + 3) / 4 * 4, '=')
            val body = String(android.util.Base64.decode(pad, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            Regex(""""cc"\s*:\s*"([A-Z]{2})"""").find(body)?.groupValues?.get(1)
        }.getOrNull() ?: "US"
        return json.encodeToString(
            Stored.serializer(),
            Stored(refreshToken = "", countryCode = cc, accessToken = accessToken.trim()),
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

    suspend fun refresh(refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", SCOPE)
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
