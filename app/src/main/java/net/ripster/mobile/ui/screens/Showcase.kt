package net.ripster.mobile.ui.screens

/**
 * Витрина на главном экране: показать РАЗНОЕ, а не всё подряд.
 *
 * Жалоба владельца 05.09.2026: «посмотри что на домашнем экране в самом верху,
 * массив аттак захватил нашу коллекцию подчистую». Так и было: скачали
 * девятнадцать треков одного альбома — и обе полки, «Из вашей коллекции» и
 * «Недавно добавленное», целиком заполнились одной обложкой. Формально всё
 * верно: это и есть коллекция, это и есть недавнее. Но полка, где двенадцать
 * раз подряд одна и та же картинка, выглядит сломанной и ничего не показывает.
 *
 * Отбираем с потолком на артиста, сохраняя ПОРЯДОК, который задал вызывающий
 * (у «недавнего» это свежесть, у «коллекции» — случайная выборка на заход).
 * Если разнообразия не хватает — в фонотеке правда один артист, — добираем
 * остатком: пустая полка хуже однообразной, а врать про чужих артистов
 * нельзя.
 */
object Showcase {

    /** Больше этого числа вещей одного артиста подряд полка не показывает. */
    const val MAX_PER_ARTIST = 2

    private fun norm(s: String): String =
        s.lowercase().substringBefore(",").substringBefore(" feat").trim()

    /**
     * [take] вещей, не больше [maxPerArtist] от одного артиста.
     *
     * @param artistOf как достать артиста — витрина не знает типа строки.
     */
    fun <T> spread(
        rows: List<T>,
        take: Int,
        maxPerArtist: Int = MAX_PER_ARTIST,
        artistOf: (T) -> String,
    ): List<T> {
        if (take <= 0 || rows.isEmpty()) return emptyList()
        val used = HashMap<String, Int>()
        val picked = ArrayList<T>(take)
        val rest = ArrayList<T>()
        for (r in rows) {
            if (picked.size >= take) break
            val k = norm(artistOf(r))
            val n = used[k] ?: 0
            if (n < maxPerArtist) {
                used[k] = n + 1
                picked += r
            } else {
                rest += r
            }
        }
        // Не набрали — добираем отложенным. Полка не должна пустеть только
        // потому, что у человека узкая фонотека.
        if (picked.size < take) picked += rest.take(take - picked.size)
        return picked
    }
}
