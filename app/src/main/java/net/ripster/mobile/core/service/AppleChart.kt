package net.ripster.mobile.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Кто сейчас на слуху в жанре — по чарту Apple.
 *
 * Владелец 05.09.2026: «изучи ещё ротации из Эпла, тоже там качественно
 * подборка создаётся».
 *
 * Что взято у Apple и почему именно это. Чарт по жанру — открытый, без ключа и
 * без подписки, отдаёт то, что РЕАЛЬНО слушают в этом жанре прямо сейчас, и у
 * каждой записи стоит своя метка жанра. Ключи (`genre=…`) здесь не угаданы, а
 * замерены живыми запросами 05.09.2026: 5 — Classical, 7 — Electronic,
 * 11 — Jazz, 17 — Dance/House, 21 — Rock.
 *
 * Чем это НЕ является: источником треков для проигрывания. Треки Apple играются
 * только через подписку и ПК, и подмешивать их в станцию значило бы собрать
 * список, половина которого на телефоне молчит. Поэтому берутся ИМЕНА: чарт
 * говорит, кто в жанре на слуху, а играем мы этих же артистов оттуда, откуда
 * умеем — ровно та же мысль, что «именитых артистов жанра надо предлагать».
 *
 * Ответ живёт в памяти [TTL_MS]: чарт меняется раз в сутки, дёргать его на
 * каждое нажатие плитки незачем.
 */
object AppleChart {

    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private data class Cached(val artists: Set<String>, val at: Long)

    private val cache = HashMap<String, Cached>()

    /**
     * Имена исполнителей из чарта жанра, приведённые к сравнимому виду.
     * Пустое множество — не смогли спросить; это не повод менять подбор.
     */
    suspend fun topArtists(
        genreId: Int,
        storefront: String = "us",
        limit: Int = 100,
    ): Set<String> = withContext(Dispatchers.IO) {
        val key = "$storefront/$genreId/$limit"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf { now - it.at < TTL_MS }?.let { return@withContext it.artists }
        }
        val url = "https://itunes.apple.com/$storefront/rss/topsongs/limit=$limit/genre=$genreId/json"
        val artists = runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching emptySet<String>()
                r.body?.string().orEmpty()
            }
            if (body.isBlank()) return@runCatching emptySet<String>()
            json.parseToJsonElement(body).jsonObject["feed"]?.jsonObject
                ?.get("entry")?.jsonArray.orEmpty()
                .mapNotNull { e ->
                    e.jsonObject["im:artist"]?.jsonObject?.get("label")?.jsonPrimitive?.content
                }
                .flatMap { ChartBoost.namesOf(it) }
                .toSet()
        }.getOrDefault(emptySet())

        synchronized(cache) { cache[key] = Cached(artists, now) }
        artists
    }
}
