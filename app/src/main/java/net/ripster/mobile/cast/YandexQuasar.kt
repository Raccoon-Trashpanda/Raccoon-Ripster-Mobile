package net.ripster.mobile.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request
import java.io.IOException

/**
 * Облачная часть протокола Glagol (управление умными колонками Яндекса).
 * Нужен OAuth-токен аккаунта Яндекса (тот же, что и у движка Яндекс.Музыки
 * на десктопе).
 *
 *  - `device_list` — список колонок аккаунта с их id/платформой.
 *  - `token` — per-device conversation-токен для локального WebSocket.
 *
 * Локальный адрес колонки (ip:port) берётся из mDNS-обнаружения
 * ([GlagolDiscovery]).
 */
class YandexQuasar(private val oauthToken: String) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    data class Device(
        val id: String = "",
        val name: String = "",
        val platform: String = "",
    )

    @Serializable
    private data class DeviceListResp(
        val status: String = "",
        val devices: List<Device> = emptyList(),
    )

    @Serializable
    private data class TokenResp(
        val status: String = "",
        val token: String = "",
    )

    suspend fun deviceList(): List<Device> = withContext(Dispatchers.IO) {
        val raw = get("https://quasar.yandex.net/glagol/device_list")
        json.decodeFromString(DeviceListResp.serializer(), raw).devices
    }

    /** Conversation-токен для локального подключения к конкретной колонке. */
    suspend fun deviceToken(deviceId: String, platform: String): String = withContext(Dispatchers.IO) {
        val raw = get("https://quasar.yandex.net/glagol/token?device_id=$deviceId&platform=$platform")
        json.decodeFromString(TokenResp.serializer(), raw).token
            .ifBlank { throw IOException("Yandex: empty glagol token for $deviceId") }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Authorization", "OAuth $oauthToken")
            .header("User-Agent", "RipsterMobile/0.1")
            .build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("Yandex: OAuth-токен недействителен")
            if (!r.isSuccessful) throw IOException("Yandex quasar ${url.substringAfterLast('/')} -> HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("Yandex quasar -> empty")
        }
    }
}
