package net.ripster.mobile.service.soundcloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.service.soundcloud.dto.ScPlaylist
import net.ripster.mobile.service.soundcloud.dto.ScSearch
import net.ripster.mobile.service.soundcloud.dto.ScStreamUrl
import net.ripster.mobile.service.soundcloud.dto.ScTrack
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * Тонкая обёртка над SoundCloud API v2. Каждый вызов подставляет свежий
 * `client_id`; на 401 — сбрасывает ключ и повторяет один раз.
 *
 * `oauthToken` (Go+) нужен только для HQ AAC. null → доступны публичные
 * прогрессивные потоки (обычно MP3 128).
 */
class SoundCloudApi(private val oauthToken: String? = null) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun resolve(url: String): ScResolveResult = withContext(Dispatchers.IO) {
        val permalink = expandShortlink(url.trim())
        val raw = getJson("$API/resolve") { it.addQueryParameter("url", permalink) }
        when {
            raw.contains("\"kind\":\"playlist\"") || raw.contains("\"kind\": \"playlist\"") ->
                ScResolveResult.Playlist(json.decodeFromString(ScPlaylist.serializer(), raw))
            raw.contains("\"kind\":\"track\"") || raw.contains("\"kind\": \"track\"") ->
                ScResolveResult.OneTrack(json.decodeFromString(ScTrack.serializer(), raw))
            else -> ScResolveResult.Unsupported
        }
    }

    suspend fun searchTracks(query: String, limit: Int = 20): List<ScTrack> = withContext(Dispatchers.IO) {
        val raw = getJson("$API/search/tracks") {
            it.addQueryParameter("q", query)
            it.addQueryParameter("limit", limit.toString())
        }
        json.decodeFromString(ScSearch.serializer(), raw).collection
    }

    /**
     * Чарт по жанру — «станция»: `kind` = top | trending, `genreSlug` без
     * префикса (house, techno, deephouse, …). SC отдаёт готовый курируемый
     * список — это и есть автосборка плейлиста, а не поиск.
     */
    suspend fun chart(genreSlug: String, kind: String = "top", limit: Int = 30): List<ScTrack> =
        withContext(Dispatchers.IO) {
            val raw = getJson("$API/charts") {
                it.addQueryParameter("kind", kind)
                it.addQueryParameter("genre", "soundcloud:genres:$genreSlug")
                it.addQueryParameter("limit", limit.toString())
                it.addQueryParameter("linked_partitioning", "1")
            }
            json.decodeFromString(net.ripster.mobile.service.soundcloud.dto.ScChart.serializer(), raw)
                .collection.map { it.track }.filter { it.id != 0L }
        }

    /** Добор заглушечных треков плейлиста: `/tracks?ids=1,2,3`. SC режет на ~50 за раз. */
    suspend fun tracksByIds(ids: List<Long>): List<ScTrack> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        buildList {
            ids.chunked(50).forEach { chunk ->
                val raw = getJson("$API/tracks") { it.addQueryParameter("ids", chunk.joinToString(",")) }
                addAll(json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ScTrack.serializer()), raw))
            }
        }
    }

    /** transcoding.url → настоящий URL потока (progressive) или m3u8 (hls). */
    suspend fun streamUrl(transcodingUrl: String, trackAuthorization: String?): String =
        withContext(Dispatchers.IO) {
            val raw = getJson(transcodingUrl) { b ->
                trackAuthorization?.let { b.addQueryParameter("track_authorization", it) }
            }
            json.decodeFromString(ScStreamUrl.serializer(), raw).url
                .ifBlank { throw IOException("SoundCloud: empty stream url for $transcodingUrl") }
        }

    // --- внутреннее ---

    private suspend fun getJson(baseUrl: String, addParams: (okhttp3.HttpUrl.Builder) -> Unit): String {
        var refreshedCid = false
        // Протухший/битый OAuth-токен не должен блокировать анонимный доступ:
        // на первый 401 при наличии токена — повтор без заголовка Authorization
        // (публичные треки, MP3 128). Только на второй 401 — реальная ошибка.
        var dropOauth = false
        while (true) {
            val cid = SoundCloudClientId.get(forceRefresh = refreshedCid)
            val httpUrl = baseUrl.toHttpUrl().newBuilder().apply {
                addParams(this)
                addQueryParameter("client_id", cid)
            }.build()
            val req = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", SoundCloudClientId.UA)
                .apply {
                    val tok = oauthToken
                    if (!tok.isNullOrBlank() && !dropOauth) header("Authorization", "OAuth $tok")
                }
                .build()
            RipsterHttp.client.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    if (!oauthToken.isNullOrBlank() && !dropOauth) {
                        dropOauth = true
                        return@use
                    }
                    if (!refreshedCid) {
                        SoundCloudClientId.invalidate()
                        refreshedCid = true
                        return@use
                    }
                }
                if (!resp.isSuccessful) throw IOException("SoundCloud: GET ${req.url.encodedPath} -> HTTP ${resp.code}")
                return resp.body?.string() ?: throw IOException("SoundCloud: empty response from ${req.url.encodedPath}")
            }
        }
    }

    /**
     * `on.soundcloud.com/xxxx` (и `snd.sc`) — короткие ссылки. `/resolve` их не
     * принимает, нужен полный permalink. Урок десктопа: разворачивать ДО resolve.
     */
    private fun expandShortlink(url: String): String {
        val isShort = url.contains("on.soundcloud.com/") || url.contains("snd.sc/")
        if (!isShort) return url
        val req = Request.Builder().url(url).header("User-Agent", SoundCloudClientId.UA).head().build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            return if (finalUrl != url) finalUrl else url
        }
    }

    companion object {
        const val API = "https://api-v2.soundcloud.com"
    }
}

sealed interface ScResolveResult {
    data class OneTrack(val track: ScTrack) : ScResolveResult
    data class Playlist(val playlist: ScPlaylist) : ScResolveResult
    data object Unsupported : ScResolveResult
}
