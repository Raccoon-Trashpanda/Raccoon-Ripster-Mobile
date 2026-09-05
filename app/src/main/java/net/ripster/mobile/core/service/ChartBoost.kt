package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Track

/**
 * Поднять в эфире тех, кто в жанре на слуху.
 *
 * Счётчики сервисов ([Popularity]) говорят, сколько раз вещь послушали ВНУТРИ
 * этого сервиса. Этого мало: у маленького сервиса свои сотни прослушиваний, у
 * большого — миллионы, и сравнивать их между собой нельзя. Чарт жанра у Apple
 * даёт то, чего нет ни у одного счётчика по отдельности, — общий по всей
 * индустрии ответ на вопрос «кто в этом жанре сейчас звучит».
 *
 * Поэтому здесь только сопоставление имён, без чисел: попал артист в жанровый
 * чарт — его вещи получают высокий вес, откуда бы мы их ни взяли. Не попал —
 * ничего не теряет: отсутствие в чарте не признак плохой музыки, а всего лишь
 * отсутствие свидетельства.
 */
object ChartBoost {

    /** Вес того, кто в чарте жанра. Ниже единицы: чарт — сильный довод, но не приговор. */
    const val CHART_WEIGHT = 0.85

    /** Разделители составного исполнителя: «A feat. B», «A & B», «A, B». */
    private val SPLIT = Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bx\b|\bvs\.?\b)\s*""", RegexOption.IGNORE_CASE)

    /**
     * Только буквы и цифры, диакритика снята.
     *
     * Одного и того же артиста сервисы пишут по-разному: «Röyksopp» у одного,
     * «Royksopp» у другого, «Sigur Rós» и «Sigur Ros». Без складывания
     * диакритики чарт и выдача не совпадали бы ровно на тех именах, ради
     * которых всё и затевалось.
     */
    private fun norm(s: String): String =
        // NFD раскладывает «ö» на «o» + знак умляута; знаки выбрасываем и
        // остаёмся с голыми буквами.
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .lowercase()
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .filter { it.isLetterOrDigit() }

    /**
     * Разбить строку исполнителя на отдельные имена.
     *
     * Совместные вещи иначе не сопоставить: в чарте стоит «Fred again..», а у
     * трека — «Fred again.. & Baby Keem», и как одна строка они не совпадут
     * никогда.
     */
    fun namesOf(artist: String): List<String> =
        artist.split(SPLIT).map { norm(it) }.filter { it.length >= 3 }

    /** Есть ли этот исполнитель в чарте жанра. */
    fun isCharting(artist: String, chart: Set<String>): Boolean {
        if (chart.isEmpty()) return false
        return namesOf(artist).any { it in chart }
    }

    /**
     * Поднять популярность тем, кто в чарте. Остальное остаётся как было —
     * в том числе `null` («сервис счётчиков не даёт»), который в подборе
     * трактуется как средний вес, а не как ноль.
     */
    fun apply(tracks: List<Track>, chart: Set<String>): List<Track> {
        if (chart.isEmpty()) return tracks
        return tracks.map { t ->
            if (isCharting(t.artist, chart)) {
                t.copy(popularity = maxOf(t.popularity ?: 0.0, CHART_WEIGHT))
            } else {
                t
            }
        }
    }
}
