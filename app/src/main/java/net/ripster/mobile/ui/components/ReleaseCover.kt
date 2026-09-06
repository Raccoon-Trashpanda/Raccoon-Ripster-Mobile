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

/**
 * Что удалось узнать о релизе по ссылке: обложка и НАСТОЯЩЕЕ название.
 *
 * Название берётся из того же самого ответа, за которым мы и так ходим ради
 * обложки: у iTunes это `collectionName`, у Spotify — заголовок страницы
 * встраивания. Лишнего запроса не появляется.
 *
 * `null` в любом поле значит «не знаю», а не «нет». Подставлять вместо
 * неизвестного названия что-то правдоподобное нельзя — ровно этим карточка и
 * врала.
 */
data class ReleaseInfo(val cover: String?, val title: String?)

private val infoCache = ConcurrentHashMap<String, ReleaseInfo>()

/**
 * Обложка И название релиза по ссылке из радара.
 *
 * 06.09.2026 владелец увидел на Главной три карточки подряд, где название
 * равнялось имени артиста: «Maribou State / Maribou State». Так и было — код
 * подставлял в заголовок имя артиста, потому что настоящего названия у радара
 * нет. Оказалось, оно лежит в том же ответе, который уже приходил за обложкой.
 */
@Composable
fun rememberReleaseInfo(latestUrl: String): ReleaseInfo {
    if (latestUrl.isBlank()) return ReleaseInfo(null, null)
    infoCache[latestUrl]?.let { return it }
    val v by produceState(initialValue = ReleaseInfo(null, null), latestUrl) {
        infoCache[latestUrl]?.let { value = it; return@produceState }
        val got = runCatching { fetchInfo(latestUrl) }.getOrNull() ?: ReleaseInfo(null, null)
        infoCache[latestUrl] = got
        value = got
    }
    return v
}

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

private suspend fun fetchCover(latestUrl: String): String? = fetchInfo(latestUrl).cover

private suspend fun fetchInfo(latestUrl: String): ReleaseInfo = withContext(Dispatchers.IO) {
    val http = RipsterHttp.client
    when {
        "music.apple.com" in latestUrl || "itunes.apple.com" in latestUrl -> {
            val id = Regex("/(?:album|song)/[^/]+/(\\d+)").find(latestUrl)?.groupValues?.getOrNull(1)
                ?: Regex("[?&]i=(\\d+)").find(latestUrl)?.groupValues?.getOrNull(1)
                ?: return@withContext ReleaseInfo(null, null)
            val req = okhttp3.Request.Builder()
                .url("https://itunes.apple.com/lookup?id=$id&entity=album").build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val cover = Regex("\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"").find(body)
                    ?.groupValues?.getOrNull(1)?.replace("100x100bb", "600x600bb")
                val title = Regex("\"collectionName\"\\s*:\\s*\"([^\"]+)\"").find(body)
                    ?.groupValues?.getOrNull(1)
                ReleaseInfo(cover, title?.takeIf { it.isNotBlank() })
            }
        }
        "open.spotify.com" in latestUrl && "/album/" in latestUrl -> {
            val id = Regex("album/([A-Za-z0-9]+)").find(latestUrl)?.groupValues?.getOrNull(1)
                ?: return@withContext ReleaseInfo(null, null)
            val req = okhttp3.Request.Builder()
                .url("https://open.spotify.com/embed/album/$id")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val cover = Regex("https://i\\.scdn\\.co/image/[A-Za-z0-9]+").find(body)?.value
                    ?: Regex("i\\.scdn\\.co\\\\u002fimage\\\\u002f([A-Za-z0-9]+)").find(body)
                        ?.groupValues?.getOrNull(1)?.let { "https://i.scdn.co/image/$it" }
                // Заголовок страницы встраивания: «<Альбом> - Album by <Артист> | Spotify».
                // Режем хвост, иначе в карточку уедет вся эта строка целиком.
                val raw = Regex("<title>([^<]+)</title>").find(body)?.groupValues?.getOrNull(1)
                val title = raw?.substringBefore(" - Album by")?.substringBefore(" | Spotify")
                    ?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("Spotify") }
                ReleaseInfo(cover, title)
            }
        }
        else -> ReleaseInfo(null, null)
    }
}
