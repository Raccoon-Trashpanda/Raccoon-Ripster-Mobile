package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Track
import kotlin.random.Random

/**
 * Ротация станции: что именно прозвучит в этот раз.
 *
 * Владелец 05.09.2026: «надо бы ещё и ротации учитывать… предлагать то, что в
 * жанре действительно популярно… за основу бери Яндекс волну».
 *
 * Две крайности одинаково плохи. Брать всегда самое популярное — станция
 * превращается в один и тот же список из десяти вещей, и жанр сводится к паре
 * имён. Брать вслепую случайное — половина эфира будет из ничем не известных
 * загрузок, а именитых артистов жанра не окажется вовсе.
 *
 * Поэтому здесь взвешенный выбор без повторов: чем больше вещь слушают, тем
 * выше её шанс попасть в эфир, но шанс есть у каждой. Так работает и «Моя
 * волна»: узнаваемое ядро плюс меняющаяся периферия.
 *
 * Вещи без счётчика (сервис их не отдаёт) получают средний вес, а не нулевой:
 * «не знаю» — это не «никому не нужно». Иначе Qobuz и Apple, у которых
 * счётчиков нет, исчезли бы из эфира целиком.
 *
 * Выбор ДЕТЕРМИНИРОВАН по [seed]: один и тот же заход даёт один и тот же
 * порядок (иначе список бы дёргался на каждой перерисовке), а следующий заход
 * — уже другой.
 */
object WaveRotation {

    /** Вес вещи без счётчика. Середина шкалы: не наказываем за молчание сервиса. */
    const val UNKNOWN_WEIGHT = 0.45

    /** Прибавка к любому весу, чтобы ни у чего не было нулевого шанса. */
    private const val FLOOR = 0.05

    private fun weight(t: Track): Double = (t.popularity ?: UNKNOWN_WEIGHT) + FLOOR

    /**
     * Выбрать [take] вещей, отдавая предпочтение тому, что слушают.
     *
     * Порядок результата — тот, в котором они выбраны: сначала выпавшее с
     * большим весом, дальше как повезёт. Повторов нет.
     */
    fun pick(pool: List<Track>, seed: Long, take: Int): List<Track> {
        if (take <= 0 || pool.isEmpty()) return emptyList()
        if (pool.size <= take) return pool
        val rnd = Random(seed)
        val rest = pool.toMutableList()
        val out = ArrayList<Track>(take)
        var total = rest.sumOf { weight(it) }
        repeat(minOf(take, pool.size)) {
            if (rest.isEmpty()) return@repeat
            var r = rnd.nextDouble() * total
            var idx = rest.lastIndex
            for (i in rest.indices) {
                r -= weight(rest[i])
                if (r <= 0.0) { idx = i; break }
            }
            val picked = rest.removeAt(idx)
            total -= weight(picked)
            out += picked
        }
        return out
    }
}
