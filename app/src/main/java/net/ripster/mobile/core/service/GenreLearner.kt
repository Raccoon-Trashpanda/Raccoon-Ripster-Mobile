package net.ripster.mobile.core.service

/**
 * Синонимы жанров, которые модуль выучивает САМ.
 *
 * Посевная таблица в [GenreKey] — временные леса, а не источник правды.
 * Она стареет молча и не переживает смену локали: замер 05.09.2026 показал,
 * что Deezer по гео-локали отдаёт словарь целиком на болгарском. Завтра будет
 * турецкий, и никакое пополнение таблицы руками за этим не успеет.
 *
 * Связи не надо выдумывать — они уже лежат в данных, которые и так проходят
 * через приложение. Один и тот же артист приходит из разных сервисов с разными
 * ярлыками жанра. Совпадение по АРТИСТУ связывает эти ярлыки между собой: если
 * у «Portishead» Apple говорит «Electronic», а какой-то сервис — «Трип-хоп», и
 * первое мы уже узнаём, то второе выучивается без единой строки в таблице.
 *
 * Наблюдение — не приговор. Копим ВЕСА: один источник слабее трёх. Ярлык
 * становится синонимом только после [MIN_EVIDENCE] независимых подтверждений,
 * и только если у артиста нет разнобоя (см. ниже).
 *
 * Чего модуль НЕ делает намеренно:
 *  · не выучивает связь по одному наблюдению — у артиста бывает разовый
 *    случайный ярлык, и он не должен становиться правилом;
 *  · не выучивает ничего, когда у артиста подтверждённые ярлыки расходятся:
 *    у сборников и у артистов на стыке жанров это норма, а не сведение;
 *  · не трогает посев. Выученное только ДОПОЛНЯЕТ его, поэтому ошибка обучения
 *    не может испортить то, что и так работало.
 */
class GenreLearner(
    /** Уже выученное: сырой ярлык (нормализованный) → ключ → вес. */
    private val learned: MutableMap<String, MutableMap<String, Int>> = HashMap(),
) {

    /** Сколько независимых подтверждений превращают наблюдение в синоним. */
    companion object {
        const val MIN_EVIDENCE = 3
    }

    /** Что видели у одного артиста: нормализованные сырые ярлыки. */
    private val perArtist = HashMap<String, MutableSet<String>>()

    /**
     * Пары «артист + ярлык», уже пошедшие в зачёт.
     *
     * Без этого набора один артист, попавшийся трижды, «доказывал» синоним
     * сам себе: каждое наблюдение добавляло вес заново. Поймано тестом
     * halfProvenIsStillUnknown. Свидетельство — это РАЗНЫЕ артисты, а не
     * повторные встречи одного.
     */
    private val credited = HashSet<String>()

    private fun normArtist(s: String) =
        s.lowercase().substringBefore(",").substringBefore(" feat").trim()

    /**
     * Показать модулю ещё одну пару «артист — ярлык жанра».
     *
     * Вызывается на всём, что и так проходит мимо: выдача поиска, треки
     * станции, библиотека. Ничего не запрашивает дополнительно.
     */
    fun observe(artist: String, rawGenre: String?) {
        val a = normArtist(artist)
        val g = GenreKey.norm(rawGenre.orEmpty())
        if (a.isEmpty() || g.isEmpty()) return

        val seen = perArtist.getOrPut(a) { HashSet() }
        seen += g

        // Опорой считаем ярлыки, которые модуль УЖЕ узнаёт. Если у артиста их
        // несколько и они говорят разное — учить нечему: это либо сборник,
        // либо артист на стыке, и любая догадка тут будет подменой.
        val anchors = seen.mapNotNull { GenreKey.of(it) }.toSet()
        if (anchors.size != 1) return
        val key = anchors.first()

        for (raw in seen) {
            if (GenreKey.of(raw) != null) continue          // посев уже знает
            if (!credited.add("$a|$raw")) continue          // этот артист уже зачтён
            val row = learned.getOrPut(raw) { HashMap() }
            row[key] = (row[key] ?: 0) + 1
        }
    }

    /**
     * Ключ жанра: сначала посев, затем выученное.
     *
     * `null` — по-прежнему «не знаю». Выученное с недобором подтверждений
     * ответом НЕ считается: половина доказательства хуже отсутствия, потому
     * что выглядит как знание.
     */
    fun of(raw: String?): String? {
        GenreKey.of(raw)?.let { return it }
        val row = learned[GenreKey.norm(raw.orEmpty())] ?: return null
        val best = row.maxByOrNull { it.value } ?: return null
        return if (best.value >= MIN_EVIDENCE) best.key else null
    }

    /** Что выучено — для журнала и для сохранения между запусками. */
    fun snapshot(): Map<String, Map<String, Int>> =
        learned.mapValues { it.value.toMap() }

    /** Сколько ярлыков уже стали синонимами. */
    fun learnedCount(): Int = learned.count { row ->
        (row.value.maxByOrNull { it.value }?.value ?: 0) >= MIN_EVIDENCE
    }

    /**
     * Сериализовать выученное в компактную строку.
     *
     * Свой формат, а не JSON-библиотека: данные плоские (ярлык → ключ → число),
     * читаются одним проходом и не тянут за собой зависимость ради трёх полей.
     * Разделители выбраны так, чтобы не встречаться в нормализованных ярлыках:
     * после [GenreKey.norm] там остаются только буквы и цифры.
     */
    fun serialize(): String = learned.entries.joinToString(";") { (raw, counts) ->
        raw + "=" + counts.entries.joinToString(",") { "${it.key}:${it.value}" }
    }

    /** Восстановить выученное (из хранилища). */
    fun restore(data: Map<String, Map<String, Int>>) {
        learned.clear()
        data.forEach { (raw, counts) -> learned[raw] = HashMap(counts) }
    }

    /** Разобрать то, что записал [serialize]. Мусор молча пропускаем: битая
     *  строка хранилища не повод терять всё остальное выученное. */
    fun restoreFrom(text: String?) {
        if (text.isNullOrBlank()) return
        val out = HashMap<String, MutableMap<String, Int>>()
        for (chunk in text.split(";")) {
            val raw = chunk.substringBefore("=", "")
            val tail = chunk.substringAfter("=", "")
            if (raw.isEmpty() || tail.isEmpty()) continue
            val row = HashMap<String, Int>()
            for (pair in tail.split(",")) {
                val k = pair.substringBefore(":", "")
                val n = pair.substringAfter(":", "").toIntOrNull() ?: continue
                if (k.isNotEmpty()) row[k] = n
            }
            if (row.isNotEmpty()) out[raw] = row
        }
        learned.clear()
        learned.putAll(out)
    }
}
