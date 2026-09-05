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
 * Кто ДЕЛАЕТ этот жанр — список артистов, а не список слов.
 *
 * Жалоба владельца 05.09.2026, дословно: «то, что сейчас играет в плеере, это
 * просто какая-то шляпа… есть Timecop1983, есть The Midnight, есть куча
 * артистов в жанре, которых слушают».
 *
 * Он прав, и причина была в самом устройстве подбора. Станция искала по НАЗВАНИЮ
 * жанра, а поиск по слову «synthwave» находит вещи, в названии или тегах которых
 * это слово стоит: любительские загрузки и часовые миксы «synthwave mix». У
 * Timecop1983 слова «synthwave» в названиях треков нет — и он не находился
 * никогда. Никакая фильтрация по тегам это не лечит: фильтр работает по тому же
 * мусору, из которого выбирает.
 *
 * Поэтому жанр теперь начинается с имён. MusicBrainz — открытая база без ключа,
 * где артистов размечают тегами, и у каждого тега есть ВЕС (сколько людей его
 * поставили). Замерено живьём 05.09.2026: по тегу `dub techno` приходят
 * Deepchord, cv313, STL, Steve Bug — настоящий канон жанра.
 *
 * Один только поиск MusicBrainz брать нельзя: его `score` — это релевантность
 * ТЕКСТА, а не сила связи с жанром, и по запросу «melodic techno» в выдаче
 * оказывается Лана Дель Рей. Поэтому оставляем только тех, у кого запрошенный
 * тег ДЕЙСТВИТЕЛЬНО стоит, и сортируем по его весу: у Стэна Колева «melodic
 * techno» есть, у Ланы — нет.
 */
object GenreCanon {

    /** Список канона меняется медленно — держим неделю. */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    /** MusicBrainz просит представляться; без этого отвечает отказом. */
    private const val UA = "Ripster/1.0 (https://github.com/Raccoon-Trashpanda/Raccoon-Ripster)"

    data class Artist(
        val name: String,
        /** Сколько людей отметили артиста ЭТИМ жанром. Сила связи, не популярность. */
        val tagWeight: Int,
    )

    private data class Cached(val artists: List<Artist>, val at: Long)

    private val cache = HashMap<String, Cached>()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Артисты жанра, от самой сильной связи к слабой.
     * Пустой список — не смогли спросить; вызывающий обязан обойтись без этого,
     * а не показать пустую станцию.
     */
    suspend fun artists(genre: String, limit: Int = 40): List<Artist> = withContext(Dispatchers.IO) {
        val key = norm(genre)
        if (key.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf { now - it.at < TTL_MS }?.let { return@withContext it.artists }
        }

        val q = java.net.URLEncoder.encode("tag:\"$genre\"", "UTF-8")
        val url = "https://musicbrainz.org/ws/2/artist?query=$q&fmt=json&limit=$limit"
        val out = runCatching {
            val body = http.newCall(
                Request.Builder().url(url).header("User-Agent", UA).build(),
            ).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
            if (body.isBlank()) return@runCatching emptyList<Artist>()

            json.parseToJsonElement(body).jsonObject["artists"]?.jsonArray.orEmpty()
                .mapNotNull { el ->
                    val o = el.jsonObject
                    val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    // Тег должен реально стоять у артиста — иначе это просто
                    // текстовое совпадение, каким и была Лана Дель Рей.
                    val w = o["tags"]?.jsonArray.orEmpty()
                        .firstOrNull { norm(it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()) == key }
                        ?.jsonObject?.get("count")?.jsonPrimitive?.content?.toIntOrNull()
                        ?: return@mapNotNull null
                    Artist(name, w)
                }
                .sortedByDescending { it.tagWeight }
        }.getOrDefault(emptyList())

        synchronized(cache) { cache[key] = Cached(out, now) }
        out
    }
}
