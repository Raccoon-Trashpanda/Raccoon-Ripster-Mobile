package net.ripster.mobile.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.errors.attempt
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track

/**
 * Глубокий состав исполнителей — ленивая догрузка кредитов к тому, что уже
 * показывает интерфейс.
 *
 * Идея ровно одна: строка «A feat. B» стоит дороже, чем Services-выдача, в
 * которой у совместной вещи одно имя, но докупается она ОДИН запросом на трек.
 * Поэтому здесь только то, что уже на экране: ряд выдачи, трек в треклисте
 * альбома, играющая вещь. Целиком страницу выдачи мы не спрашиваем никогда —
 * [net.ripster.mobile.core.service.ServiceClient.contributorsFor] зовётся через
 * этот фильтр, и он же держит кэш, очередь и бюджет.
 *
 * Правила, без которых догрузка стала бы ухудшением:
 *  • один и тот же трек за сессию спрашивается НЕ больше одного раза — и когда
 *    сервис ничего не дал тоже (иначе палец на прокрутке раскрутил бы сеть);
 *  • в сеть идёт не больше [MAX_PARALLEL] запросов одновременно — десять видимых
 *    рядов не превращаются в десять параллельных вызовов;
 *  • на сервис — не больше [MAX_REQUESTS] за сессию процесса: долгая прокрутка
 *    поиска не должна превращаться в скрейп каталога;
 *  • ответ, в котором имён НЕ больше, чем уже показано, отбрасывается
 *    ([Contributors.richerThan]) — догрузка не имеет права стирать то, что было.
 */
object ArtistDepth {

    /** Сколько треков держим в памяти (и успешные, и «сервис молчит»). */
    private const val MAX_CACHED = 400

    /** Запросов в полёте на весь процесс — столько же и на прокрутке. */
    private const val MAX_PARALLEL = 2

    /** Потолок запросов к ОДНОМУ сервису за сессию процесса. */
    private const val MAX_REQUESTS = 40

    /** key(tracks) → строка состава; пустая строка — «спрашивали, составов нет». */
    private val done = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > MAX_CACHED
    }

    private val inFlight = HashSet<String>()
    private val spent = HashMap<Service, Int>()
    private val gate = Semaphore(MAX_PARALLEL)
    private val lock = Any()

    /**
     * То, что кэш уже знает про этот трек, — строкой для показа, или null, если
     * менять нечего. Сетевой вызов НЕ делает: читается ряд треклиста альбома,
     * где спрашивать каждый трек нельзя.
     */
    fun cached(track: Track): String? {
        val line = synchronized(lock) { done[key(track)] }.orEmpty()
        return line.ifEmpty { null }?.takeIf { showsMoreThanIt(line, track.artist) }
    }

    /**
     * Состав трека, если его удалось достать (или он уже в кэше). Возвращает
     * строку, КОГДА она богаче текущей; `null` — оставить как есть.
     */
    suspend fun deeper(client: ServiceClient, track: Track): String? {
        // Строка уже составная — спрашивать не о чём: тот же запрос не обещает
        // добавить к «A, B» их приглашённых, а бюджет на сервис конечен.
        if (ChartBoost.namesOf(track.artist).size >= 2) return null
        val key = key(track)
        // «Уже спрашивали» — включая случай, когда сервис ничего не дал: пустая
        // строка в кэше значит ровно это, и повторного запроса не будет.
        if (synchronized(lock) { done.containsKey(key) }) return cached(track)
        val mine = synchronized(lock) {
            if (key in inFlight) return null
            if ((spent[client.service] ?: 0) >= MAX_REQUESTS) return null
            inFlight += key
            spent[client.service] = (spent[client.service] ?: 0) + 1
            true
        }
        if (!mine) return null
        val line = try {
            // attempt, а не runCatching: отменённый ряд (уехал с экрана) обязан
            // остаться отменой, а не превратиться в «состава нет».
            val got = gate.withPermit {
                withContext(Dispatchers.IO) { attempt { client.contributorsFor(track) }.getOrNull() }
            }
            if (got != null && got.richerThan(track.artist)) got.display() else ""
        } finally {
            // Отмена (ряд уехал с экрана) сюда тоже приходит: трек не
            // запоминаем — он так и остался неспрошенным, budget не возвращаем.
            synchronized(lock) { inFlight -= key }
        }
        synchronized(lock) { done[key] = line }
        return line.ifEmpty { null }?.takeIf { showsMoreThanIt(it, track.artist) }
    }

    /**
     * Тот же трек, но с полным составом — для тех, кто держит [Track] целиком
     * (карточка трека, теги файла).
     */
    suspend fun enrich(client: ServiceClient, track: Track): Track =
        deeper(client, track)?.let { track.copy(artist = it) } ?: track

    /**
     * Есть ли в новой строке имён больше, чем в старой. Сверяем по разбитому
     * составу ([ChartBoost.namesOf]), а не по длине: «Chloe x Halle» длиннее
     * «Chloe», но артистов там всё равно два против одного.
     */
    private fun showsMoreThanIt(line: String, current: String): Boolean =
        ChartBoost.namesOf(line).size > ChartBoost.namesOf(current).size

    /** Id трека живёт в своём сервисе, поэтому ключ — пара «сервис : id». */
    private fun key(track: Track): String = track.service.name + ':' + track.id
}
