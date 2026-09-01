package net.ripster.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.net.RipsterHttp
import java.util.concurrent.ConcurrentHashMap

/**
 * Обложка релиза по ссылке из радара:
 *   Apple  — iTunes lookup по id альбома;
 *   Spotify — ban-safe embed-страница (тянем URL картинки i.scdn.co);
 *   прочее — null (карточка рисует градиент по хэшу).
 *
 * Результат кэшируется на весь процесс — карточки в Радаре и на Главной больше
 * НЕ дёргают сеть заново при каждом перелистывании (это и была причина
 * «обложки не грузятся»: десятки одновременных фетчей без кэша, часть отваливалась).
 */
private val coverCache = ConcurrentHashMap<String, String>()   // url → "" (нет) | image url

@Composable
fun rememberReleaseCover(latestUrl: String): String? {
    if (latestUrl.isBlank()) return null
    coverCache[latestUrl]?.let { return it.ifBlank { null } }
    val v by produceState<String?>(initialValue = null, latestUrl) {
        coverCache[latestUrl]?.let { value = it.ifBlank { null }; return@produceState }
        val resolved = runCatching { fetchCover(latestUrl) }.getOrNull()
        coverCache[latestUrl] = resolved ?: ""
        value = resolved
    }
    return v
}

private suspend fun fetchCover(latestUrl: String): String? = withContext(Dispatchers.IO) {
    val http = RipsterHttp.client
    when {
        "music.apple.com" in latestUrl || "itunes.apple.com" in latestUrl -> {
            val id = Regex("/(?:album|song)/[^/]+/(\\d+)").find(latestUrl)?.groupValues?.getOrNull(1)
                ?: Regex("[?&]i=(\\d+)").find(latestUrl)?.groupValues?.getOrNull(1)
                ?: return@withContext null
            val req = okhttp3.Request.Builder()
                .url("https://itunes.apple.com/lookup?id=$id&entity=album").build()
            http.newCall(req).execute().use { r ->
                Regex("\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"").find(r.body?.string().orEmpty())
                    ?.groupValues?.getOrNull(1)?.replace("100x100bb", "600x600bb")
            }
        }
        "open.spotify.com" in latestUrl && "/album/" in latestUrl -> {
            val id = Regex("album/([A-Za-z0-9]+)").find(latestUrl)?.groupValues?.getOrNull(1)
                ?: return@withContext null
            val req = okhttp3.Request.Builder()
                .url("https://open.spotify.com/embed/album/$id")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                Regex("https://i\\.scdn\\.co/image/[A-Za-z0-9]+").find(body)?.value
                    ?: Regex("i\\.scdn\\.co\\\\u002fimage\\\\u002f([A-Za-z0-9]+)").find(body)
                        ?.groupValues?.getOrNull(1)?.let { "https://i.scdn.co/image/$it" }
            }
        }
        else -> null
    }
}
