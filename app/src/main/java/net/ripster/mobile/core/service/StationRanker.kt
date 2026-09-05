package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Track
import kotlin.math.log10
import kotlin.random.Random

/**
 * Что и в каком порядке прозвучит — один слой поверх ЛЮБОГО источника.
 *
 * До этого каждый признак жил сам по себе: WaveRotation крутила по
 * популярности, ChartBoost переписывала полю popularity, а порядок слияния
 * решал очерёдность списков. Три механизма ничего не знали друг о друге, и
 * добавить четвёртый — вкус слушателя — было некуда. Здесь они сходятся в одну
 * оценку, и добавление пятого не трогает остальные. Оба прежних модуля после
 * этого остались бы мёртвым кодом, дублирующим ту же логику, — поэтому
 * WaveRotation удалён, а от ChartBoost осталось только распознавание артиста
 * (`isCharting`), которым ранкер и пользуется.
 *
 * Что учитывается:
 *  - ручается ли источник за жанр (канон против выдачи поиска);
 *  - в чарте ли артист прямо сейчас;
 *  - насколько вещь слушают вообще;
 *  - слушает ли ЭТОТ человек этого артиста;
 *  - свежесть — лёгкий кивок новому, но не приговор старому.
 *
 * Чего сознательно НЕ делаем:
 *  - не наказываем за «не знаю». Ни у Qobuz, ни у Apple нет счётчика
 *    прослушиваний, у половины выдачи нет года. Считать молчание сервиса
 *    нулём — значит вычистить эти сервисы из эфира целиком, что уже
 *    случалось. Неизвестное получает середину шкалы;
 *  - не превращаем свежесть в главный признак. Станция жанра, где нет
 *    ничего старше двух лет, — это не жанр, а витрина новинок.
 *
 * Выбор ДЕТЕРМИНИРОВАН по seed: один заход — один эфир (иначе список
 * дёргался бы на каждой перерисовке), следующий — уже другой.
 */
object StationRanker {

    /** Вес признака, о котором сервис промолчал. Середина, а не ноль. */
    const val UNKNOWN = 0.45

    /** Чтобы ни у чего не было нулевого шанса. */
    private const val FLOOR = 0.05

    /** Столько же, сколько даёт [ChartBoost] — прибавка, а не пропуск без очереди. */
    private const val CHART = 0.85

    /** Потолок прибавки за «этого артиста человек слушает». */
    private const val TASTE_MAX = 0.60

    /** Признаки одной вещи. Всё, чего не знаем, — `null`, и это не «нет». */
    data class Signals(
        /** Источник ручается за жанр (курируемая станция, канон жанра). */
        val vetted: Boolean = false,
        /** Артист сейчас в чарте этого жанра. */
        val charting: Boolean = false,
        /** 0..1 — насколько вещь слушают. `null` — сервис не сказал. */
        val popularity: Double? = null,
        /** Год выхода. `null` — не знаем. */
        val year: Int? = null,
    )

    /**
     * Вкус слушателя: сколько раз он включал каждого артиста.
     * Ключи нормализованы — см. [normArtist].
     */
    data class Taste(val plays: Map<String, Int> = emptyMap()) {
        fun playsOf(artist: String): Int = plays[normArtist(artist)] ?: 0

        companion object {
            val EMPTY = Taste()

            /** Собрать из истории прослушиваний (свежих сверху). */
            fun of(artists: List<String>): Taste {
                val m = HashMap<String, Int>()
                artists.forEach { a ->
                    val k = normArtist(a)
                    if (k.isNotEmpty()) m[k] = (m[k] ?: 0) + 1
                }
                return Taste(m)
            }
        }
    }

    fun normArtist(s: String): String =
        s.lowercase().substringBefore(",").substringBefore(" feat").trim()

    /**
     * Вес одной вещи. Множители, а не слагаемые: признаки усиливают друг
     * друга — знакомый артист в чарте должен звучать заметно чаще случайной
     * находки, а не на пару процентов.
     */
    fun weight(track: Track, s: Signals, taste: Taste, nowYear: Int): Double {
        var w = if (s.vetted) 1.0 else 0.62
        if (s.charting) w *= (1.0 + CHART)
        w *= 0.55 + (s.popularity ?: UNKNOWN)
        val plays = taste.playsOf(track.artist)
        if (plays > 0) {
            // log, а не линейно: десятое прослушивание значит куда меньше
            // второго, иначе один зацикленный артист забил бы всю станцию.
            val t = (log10(1.0 + plays) / log10(11.0)).coerceAtMost(1.0)
            w *= 1.0 + TASTE_MAX * t
        }
        s.year?.let { y ->
            val age = nowYear - y
            w *= when {
                age <= 1 -> 1.15
                age <= 3 -> 1.07
                age >= 15 -> 0.95   // кивок, а не приговор: классика жанра остаётся
                else -> 1.0
            }
        }
        return w + FLOOR
    }

    /**
     * Отобрать [size] вещей.
     *
     * [maxPerArtist] и [artistGap] — против того, чем портится любая станция:
     * одного артиста подряд. Первый ограничивает, сколько его всего, второй —
     * насколько близко его вещи могут стоять.
     *
     * Дедуп И по ISRC, И по «название|артист»: разные сервисы отдают одну
     * запись то с кодом, то без, и без второго ключа она приезжает дважды.
     */
    fun rank(
        items: List<Pair<Track, Signals>>,
        taste: Taste = Taste.EMPTY,
        seed: Long = 0L,
        size: Int = 30,
        nowYear: Int = 2026,
        maxPerArtist: Int = 2,
        artistGap: Int = 3,
        /**
         * Что уже взято раньше — когда эфир собирается в два захода
         * (сначала курируемое, потом добор). Без этого второй заход не
         * знал бы ни про дубли первого, ни про его артистов, и на стыке
         * дважды подряд шёл бы один и тот же артист.
         */
        already: List<Track> = emptyList(),
    ): List<Track> {
        if (size <= 0 || items.isEmpty()) return emptyList()
        val rnd = Random(if (seed == 0L) 1L else seed)
        val rest = items.toMutableList()
        val out = ArrayList<Track>(size)
        val seen = HashSet<String>()
        val perArtist = HashMap<String, Int>()

        fun keys(t: Track): List<String> = listOfNotNull(
            t.isrc?.takeIf { it.isNotBlank() }?.lowercase(),
            (t.title.trim() + "|" + t.artist.trim()).lowercase(),
        )

        already.forEach { t ->
            seen.addAll(keys(t))
            val k = normArtist(t.artist)
            perArtist[k] = (perArtist[k] ?: 0) + 1
        }

        fun blockedNow(artist: String): Boolean {
            val a = normArtist(artist)
            if ((perArtist[a] ?: 0) >= maxPerArtist) return true
            // Хвост предыдущего захода тоже считается соседством.
            val tail = (already + out).takeLast(artistGap)
            return tail.any { normArtist(it.artist) == a }
        }

        // Взвешенная выборка без возврата. Кандидат, отклонённый только из-за
        // соседства с тем же артистом, из мешка НЕ выбрасывается: дальше по
        // списку он снова станет допустимым.
        while (out.size < size && rest.isNotEmpty()) {
            val weights = rest.map { (t, s) -> weight(t, s, taste, nowYear) }
            val total = weights.sum()
            var r = rnd.nextDouble() * total
            var idx = rest.lastIndex
            for (i in rest.indices) {
                r -= weights[i]
                if (r <= 0.0) { idx = i; break }
            }
            val (track, _) = rest[idx]
            val k = keys(track)
            when {
                k.any { it in seen } -> rest.removeAt(idx)          // дубль — вон совсем
                (perArtist[normArtist(track.artist)] ?: 0) >= maxPerArtist -> rest.removeAt(idx)
                blockedNow(track.artist) -> {
                    // Слишком близко к тому же артисту. Ищем следующего, кто
                    // сейчас допустим; если таких нет вовсе — снимаем зазор,
                    // иначе станция оборвётся на середине из-за формальности.
                    val alt = rest.indices.firstOrNull { i -> !blockedNow(rest[i].first.artist) }
                    if (alt == null) {
                        val (t2, _) = rest.removeAt(idx)
                        seen.addAll(keys(t2))
                        perArtist[normArtist(t2.artist)] = (perArtist[normArtist(t2.artist)] ?: 0) + 1
                        out += t2
                    } else {
                        val (t2, _) = rest.removeAt(alt)
                        seen.addAll(keys(t2))
                        perArtist[normArtist(t2.artist)] = (perArtist[normArtist(t2.artist)] ?: 0) + 1
                        out += t2
                    }
                }
                else -> {
                    rest.removeAt(idx)
                    seen.addAll(k)
                    perArtist[normArtist(track.artist)] = (perArtist[normArtist(track.artist)] ?: 0) + 1
                    out += track
                }
            }
        }
        return out
    }
}
