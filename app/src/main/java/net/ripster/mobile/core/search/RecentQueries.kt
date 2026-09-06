package net.ripster.mobile.core.search

import android.content.Context

/**
 * Что человек уже искал.
 *
 * Просьба владельца 06.09.2026: «сделай в поиске возможность вводить последние
 * запросы, плашками или в контексте; надо запомнить последние запросы, частые
 * тоже».
 *
 * Хранится и порядок (когда искали в последний раз), и счёт (сколько раз) —
 * это разные вопросы. «Последнее» отвечает «на чём я остановился», «частое» —
 * «что я слушаю вообще». Показываем сперва частое, потом свежее: список из
 * пяти случайных вчерашних запросов помогает меньше, чем один, который человек
 * набирает каждый день.
 */
object RecentQueries {

    private const val PREFS = "search_recent"
    private const val KEY = "items"
    private const val SEP = "\u0001"
    private const val PAIR = "\u0002"

    /** Сколько всего помним. Больше — список перестаёт быть подсказкой. */
    const val LIMIT = 12

    /** Сколько показываем плашками. */
    const val SHOWN = 6

    data class Entry(val query: String, val count: Int, val lastMs: Long)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Entry> =
        prefs(context).getString(KEY, "").orEmpty()
            .split(SEP).filter { it.isNotBlank() }
            .mapNotNull { row ->
                val p = row.split(PAIR)
                if (p.size < 3) null
                else Entry(p[0], p[1].toIntOrNull() ?: 1, p[2].toLongOrNull() ?: 0L)
            }

    /**
     * Что показать под строкой поиска.
     *
     * Порядок: сперва то, что искали чаще, при равном счёте — то, что искали
     * позже. Ровно так подсказка отвечает на вопрос «что я обычно ищу».
     */
    fun suggestions(context: Context, limit: Int = SHOWN): List<String> =
        all(context)
            .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastMs })
            .take(limit)
            .map { it.query }

    /** Запомнить запрос. Ссылки не запоминаем: подсказывать URL бессмысленно. */
    fun remember(context: Context, raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || q.length > 80 || q.startsWith("http")) return
        val now = System.currentTimeMillis()
        val cur = all(context).toMutableList()
        val i = cur.indexOfFirst { it.query.equals(q, ignoreCase = true) }
        if (i >= 0) cur[i] = cur[i].copy(count = cur[i].count + 1, lastMs = now)
        else cur.add(Entry(q, 1, now))
        // Вытесняем по давности, а не по счёту: редкий, но свежий запрос
        // человеку сейчас нужнее давно забытого частого.
        val kept = cur.sortedByDescending { it.lastMs }.take(LIMIT)
        prefs(context).edit()
            .putString(KEY, kept.joinToString(SEP) { "${it.query}$PAIR${it.count}$PAIR${it.lastMs}" })
            .apply()
    }

    fun forget(context: Context, raw: String) {
        val kept = all(context).filterNot { it.query.equals(raw.trim(), ignoreCase = true) }
        prefs(context).edit()
            .putString(KEY, kept.joinToString(SEP) { "${it.query}$PAIR${it.count}$PAIR${it.lastMs}" })
            .apply()
    }
}
