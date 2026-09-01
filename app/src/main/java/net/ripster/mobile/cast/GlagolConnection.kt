package net.ripster.mobile.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Локальное WebSocket-подключение к колонке Яндекса (протокол Glagol).
 *
 * TLS у колонки самоподписанный, поэтому свой доверяющий-всему клиент —
 * только для этого соединения в локальной сети, общий [net.ripster.mobile.core.net.RipsterHttp]
 * не трогаем.
 *
 * В объёме: транспорт (`play`/`stop`/`next`/`prev`) и громкость. Запуск
 * НАШЕГО скачанного файла на колонке через Glagol невозможен (нужен id
 * трека Яндекс.Музыки) — это следующий шаг вместе с интеграцией
 * Яндекс.Музыки.
 */
class GlagolConnection(
    private val host: String,
    private val port: Int,
    private val conversationToken: String,
) {
    enum class Status { CONNECTING, CONNECTED, CLOSED, FAILED }

    private val _status = MutableStateFlow(Status.CLOSED)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Последнее состояние воспроизведения от колонки (сырой JSON `state`). */
    private val _playing = MutableStateFlow<Boolean?>(null)
    val playing: StateFlow<Boolean?> = _playing.asStateFlow()

    private var ws: WebSocket? = null
    private val client: OkHttpClient by lazy { trustAllClient() }

    fun connect() {
        _status.value = Status.CONNECTING
        val req = Request.Builder().url("wss://$host:$port/").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = Status.CONNECTED
                send("ping")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val st = JSONObject(text).optJSONObject("state") ?: return
                    _playing.value = when (st.optString("playing")) {
                        "true" -> true; "false" -> false; else -> st.optBoolean("playing")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = Status.FAILED
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = Status.CLOSED
            }
        })
    }

    fun play() = send("play")
    fun pause() = send("stop")
    fun next() = send("next")
    fun previous() = send("prev")
    fun setVolume(level: Float) = send("setVolume", JSONObject().put("volume", level.coerceIn(0f, 1f).toDouble()))

    /** Запустить трек Яндекс.Музыки на колонке по его id. */
    fun playMusic(yandexMusicTrackId: String, type: String = "track") =
        send("playMusic", JSONObject().put("id", yandexMusicTrackId).put("type", type))

    fun close() {
        runCatching { ws?.close(1000, null) }
        ws = null
        _status.value = Status.CLOSED
    }

    private fun send(command: String, extra: JSONObject? = null) {
        val payload = JSONObject().put("command", command)
        extra?.keys()?.forEach { payload.put(it, extra.get(it)) }
        val msg = JSONObject()
            .put("conversationToken", conversationToken)
            .put("id", UUID.randomUUID().toString())
            .put("sentTime", System.currentTimeMillis())
            .put("payload", payload)
        ws?.send(msg.toString())
    }

    private fun trustAllClient(): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }
}
