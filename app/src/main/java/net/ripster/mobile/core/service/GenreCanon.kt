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

    /**
     * MusicBrainz просит не чаще запроса в секунду и на превышение отвечает
     * ПУСТЫМ телом, а не ошибкой. Без этого шлюза канон случайным образом
     * оказывался пустым — замер 05.09.2026: подряд идущие запросы по `techno`
     * дали сначала 32 имени, а через минуту ноль.
     */
    private val gate = kotlinx.coroutines.sync.Mutex()
    private var lastCallAt = 0L
    private const val MIN_GAP_MS = 1_100L

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Тег должен стоять среди ПЕРВЫХ тегов самого артиста, а не просто стоять.
     *
     * Веса тегов у нишевых жанров почти всегда единица, поэтому по весу
     * настоящих от случайных не отличить. Замер 05.09.2026 по `melodic techno`:
     * тег с весом 1 стоит и у Boris Brejcha, и у Ланы Дель Рей. Зато у первого
     * он в тройке главных, а у второй — далеко внизу под indie pop и dream pop.
     * Проверено на трёх жанрах: правило оставляет Anyma, Stan Kolev, Adana
     * Twins, Deepchord, cv313, Porter Ricks и убирает Лану, Massive Attack,
     * Calvin Harris, Dragonette.
     *
     * Проверялась и другая гипотеза — «требовать родительский тег techno». Она
     * НЕ ГОДИТСЯ, и это видно на тех же данных: под неё не проходят Anyma и
     * Stan Kolev (у них нет тега `techno`), зато проходит Massive Attack. Правило
     * должно отражать, чем артист занимается, а не какие слова у него в списке.
     */
    private const val TOP_TAGS = 3

    /**
     * Артисты жанра, от самой сильной связи к слабой.
     * Пустой список — не смогли спросить; вызывающий обязан обойтись без этого,
     * а не показать пустую станцию.
     */
    /**
     * Родительский жанр: «melodic techno» → «techno», «deep house» → «house».
     *
     * Нужен потому, что тег поджанра в MusicBrainz может просто не
     * существовать. Замер 05.09.2026: по `melodic techno` канон пуст, и
     * станция целиком собиралась из выдачи поиска — часовых миксов вида
     * «Melodic Techno - Best Mix 2025». Спускаться к родителю честнее, чем
     * отдавать пустоту: артисты будут настоящие, а уточняющее слово всё равно
     * работает дальше в сверке жанра.
     *
     * Одно слово родителя не имеет: «techno» → null, ниже спускаться некуда.
     */
    fun parentGenre(genre: String): String? =
        genre.trim().split(' ', '\t').filter { it.isNotBlank() }
            .takeIf { it.size > 1 }?.last()

    /** Ниже этого числа имён канон бесполезен — лучше спросить родителя. */
    private const val MIN_CANON = 3

    suspend fun artists(genre: String, limit: Int = 40): List<Artist> = withContext(Dispatchers.IO) {
        val exact = artistsExact(genre, limit)
        if (exact.size >= MIN_CANON) return@withContext exact
        // Тега поджанра может не быть вовсе («melodic techno» у части сервисов),
        // и тогда спуск к родителю честнее пустоты: артисты будут настоящие, а
        // уточняющее слово всё равно работает дальше, в сверке жанра.
        val parent = parentGenre(genre) ?: return@withContext exact
        val up = artistsExact(parent, limit)
        if (up.size > exact.size) up else exact
    }

    private suspend fun artistsExact(genre: String, limit: Int): List<Artist> = withContext(Dispatchers.IO) {
        val key = norm(genre)
        if (key.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.takeIf { now - it.at < TTL_MS }?.let { return@withContext it.artists }
        }

        val q = java.net.URLEncoder.encode("tag:\"$genre\"", "UTF-8")
        val url = "https://musicbrainz.org/ws/2/artist?query=$q&fmt=json&limit=$limit"
        val out: List<Artist>? = runCatching {
            gate.lock()
            val body = try {
                val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallAt)
                if (wait > 0) kotlinx.coroutines.delay(wait)
                lastCallAt = System.currentTimeMillis()
                http.newCall(
                    Request.Builder().url(url).header("User-Agent", UA).build(),
                ).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
            } finally {
                gate.unlock()
            }
            // Пустое тело — это «не ответили», а не «таких артистов нет».
            // Разница решающая: ниже такой ответ НЕ попадает в кэш, иначе одна
            // осечка хоронила бы жанр на неделю (см. TTL_MS).
            if (body.isBlank()) return@runCatching null

            json.parseToJsonElement(body).jsonObject["artists"]?.jsonArray.orEmpty()
                .mapNotNull { el ->
                    val o = el.jsonObject
                    val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    // Мало того, что тег стоит, — он должен быть среди
                    // ГЛАВНЫХ тегов артиста. См. TOP_TAGS: по одному лишь
                    // наличию тега в станцию «melodic techno» приезжала Лана
                    // Дель Рей, у которой он стоит весом 1 под кучей поп-тегов.
                    val ranked = o["tags"]?.jsonArray.orEmpty()
                        .mapNotNull { t ->
                            val n = norm(t.jsonObject["name"]?.jsonPrimitive?.content.orEmpty())
                            val c = t.jsonObject["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            if (n.isEmpty()) null else n to c
                        }
                        .sortedByDescending { it.second }
                    val at = ranked.indexOfFirst { it.first == key }
                    if (at < 0 || at >= TOP_TAGS) return@mapNotNull null
                    Artist(name, ranked[at].second)
                }
                .sortedByDescending { it.tagWeight }
        }.getOrNull()

        // Кэшируем ТОЛЬКО настоящий ответ. Провал сети или отказ по частоте
        // должен пройти бесследно и быть переспрошен в следующий раз.
        if (out != null) synchronized(cache) { cache[key] = Cached(out, now) }
        out ?: emptyList()
    }
}
