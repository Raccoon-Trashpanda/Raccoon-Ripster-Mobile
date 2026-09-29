package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Track
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.abs
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

    /**
     * Вес признака, о котором сервис промолчал. Середина шкалы, а не ноль.
     *
     * Было 0,45. Причина в истории существует, но она формулирует правило, а не
     * число: KDoc этого же файла (коммит `2607a1af`, 21.09, импорт всей мобилки)
     * говорит «неизвестное получает середину шкалы», и ни один тест 0,45 числами
     * не держит — `anUnknownPopularityIsNotAZero` проверяет только «молчание
     * сервиса весит больше явного нуля», что верно при любом положительном
     * значении. А в этой формуле множитель popularity — `0.55 + P`, то есть
     * настоящая середина шкалы `P` это ровно 0,5: при 0,45 молчание сервиса
     * оказывалось чуть НАКАЗАНО (множитель 1,00 против 1,05 у трека с реальной
     * средней популярностью), чего правило Сола («нет данных — нейтрально») не
     * просит. Ставим 0,5 — теперь код делает то, что пишет комментарий.
     */
    const val UNKNOWN = 0.5

    /** Чтобы ни у чего не было нулевого шанса. */
    private const val FLOOR = 0.05

    /** Столько же, сколько даёт [ChartBoost] — прибавка, а не пропуск без очереди. */
    private const val CHART = 0.85

    /** Потолок прибавки за «этого артиста человек слушает». */
    private const val TASTE_MAX = 0.60

    /**
     * Скользящее окно сессии: 3 часа. Квоты считаются по ВСЕМУ эфиру, а не по
     * очередной пачке из 30 — иначе станция, добранная тремя заходами, спокойно
     * ставила бы пятого подряд одного и того же артиста.
     *
     * Принятое проектное правило (по образцу 17 U.S.C. §114(j)(13)), а не
     * юридическое требование к этому приложению.
     */
    const val SESSION_WINDOW_MS = 3 * 60 * 60 * 1000L

    /** Не больше четырёх записей артиста за окно. */
    const val ARTIST_CAP = 4

    /** ...и не больше трёх его записей подряд. */
    const val ARTIST_MAX_RUN = 3

    /** Не больше трёх записей с одного альбома за окно. */
    const val ALBUM_CAP = 3

    /** ...и не больше двух подряд. */
    const val ALBUM_MAX_RUN = 2

    /**
     * Полупериод свежести, ДНЕЙ. Спецификация Сола давала 45, Claude заменил на
     * 180: при 45 днях трек полугодовой давности весит 1/16, и станция
     * превращается в витрину новинок — а жалоба владельца была про «канон и
     * откровенно старьё», а не про «мало новинок».
     */
    const val FRESH_HALF_LIFE_DAYS = 180.0

    /** Пол свежести: классика изредка обязана звучать, но без права тонуть. */
    const val FRESH_FLOOR = 0.15

    /** Неизвестная дата — середина шкалы. «Не знаю» не равно «вышло сегодня». */
    const val FRESH_UNKNOWN = 0.5

    /**
     * Насколько свежесть влияет на вес. Одно слагаемое, не главное: множитель
     * `(1 − W) + W·F` даёт при F=1 ровно ×1.0, а при F=пол — ×0.7375, то есть
     * старьё остаётся в эфире (проверено тестом `anOldTrackIsNudgedNotBuried`).
     */
    const val FRESH_WEIGHT = 0.25

    /** Мягкий разнос: `score = λ·R − (1−λ)·похожесть` (MMR, Carbonell 1998). */
    const val MMR_LAMBDA = 0.75

    /** Окно разноса — последние столько РЕАЛЬНО сыгранных вещей. */
    const val MMR_WINDOW = 10

    /**
     * Потолок прибавки за вкус, ПРИШЕДШИЙ С ПК (Раскопки + подписки Spotify).
     *
     * Отдельно от [TASTE_MAX] намеренно. Это две разные величины: местные
     * прослушивания считаются в штуках, а вес с ПК — в своей шкале (замер
     * 05.09.2026: от 3 у подписки до 88 у самого слушаемого). Сложить их в
     * одно поле значило бы выдать пересчёт одной шкалы в другую за
     * измерение. Держим врозь, складываем как два независимых признака.
     */
    private const val PC_TASTE_MAX = 0.50

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
        /**
         * Точная дата релиза, ISO `YYYY-MM-DD`. Если она есть, возраст считается
         * по ней (в днях), а не грубо по году: полупериод 180 дней иначе не
         * различил бы май и декабрь одного года. `null` — берём [year].
         */
        val releaseDate: String? = null,
    )

    /**
     * Вкус слушателя: сколько раз он включал каждого артиста.
     * Ключи нормализованы — см. [normArtist].
     */
    data class Taste(
        /** Сколько раз включали ЗДЕСЬ, на телефоне. Штуки. */
        val plays: Map<String, Int> = emptyMap(),
        /**
         * Расположение к артисту по данным ПК — Раскопки и подписки Spotify.
         * Уже приведено к 0..1, потому что исходная шкала ПК своя и на
         * телефоне ничего не значит.
         */
        val affinity: Map<String, Double> = emptyMap(),
    ) {
        fun playsOf(artist: String): Int = plays[normArtist(artist)] ?: 0
        fun affinityOf(artist: String): Double = affinity[normArtist(artist)] ?: 0.0

        /** Слить местное и пришедшее с ПК, не смешивая шкалы. */
        fun plus(other: Taste): Taste = Taste(
            plays = plays + other.plays,
            affinity = affinity + other.affinity,
        )

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

            /**
             * Собрать из весов ПК. Нормируем по САМОМУ большому весу в наборе:
             * абсолютные числа Раскопок на телефоне смысла не имеют, а
             * отношение «этот вдвое любимее того» — имеет.
             */
            fun fromWeights(weights: Map<String, Double>): Taste {
                val top = weights.values.maxOrNull() ?: return EMPTY
                if (top <= 0.0) return EMPTY
                val m = HashMap<String, Double>()
                weights.forEach { (name, w) ->
                    val k = normArtist(name)
                    if (k.isNotEmpty() && w > 0.0) m[k] = (w / top).coerceIn(0.0, 1.0)
                }
                return Taste(affinity = m)
            }
        }
    }

    fun normArtist(s: String): String =
        s.lowercase().substringBefore(",").substringBefore(" feat").trim()

    /** Ключ поля для сравнения: пустое и «пробелы» = поля нет (вклад 0). */
    private fun metaKey(s: String?): String? =
        s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Ключ альбома; пустой = «альбома нет» — такие вещи НЕ сливаются в один. */
    private fun albumKey(t: Track): String? = metaKey(t.albumTitle)

    private fun labelKey(t: Track): String? = metaKey(t.label)

    private fun genreKey(t: Track): String? = metaKey(t.genre)

    /**
     * Возраст записи в днях по [Signals]. Будущая дата ограничена снизу нулём
     * (релиз «на следующей неделе» не должен выглядеть древним), а `null`
     * означает «сервис не сказал» — это не ноль и не «новинка».
     */
    fun ageDays(s: Signals, nowYear: Int): Double? {
        s.releaseDate?.let { raw ->
            val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(raw.trim())
            if (m != null) {
                val (y, mo, d) = m.destructured
                return daysSince(y.toInt(), mo.toInt(), d.toInt(), nowYear).coerceAtLeast(0.0)
            }
        }
        val y = s.year ?: return null
        // Года хватает, чтобы отличить «год назад» от «двадцать лет назад».
        // Считаем по 1 июля указанного года — середина, а не выдуманная точность.
        return (nowYear - y) * 365.25
    }

    /** Грубый перевод «год-месяц-день» в дни относительно 1 июля nowYear. */
    private fun daysSince(year: Int, month: Int, day: Int, nowYear: Int): Double =
        (civilDays(nowYear, 7, 1) - civilDays(year, month, day)).toDouble()

    /** Число суток от условной эпохи (алгоритм Howard Hinnant, целые дни). */
    private fun civilDays(y0: Int, m0: Int, d0: Int): Long {
        val y = if (m0 <= 2) y0 - 1 else y0
        val era = ((if (y >= 0) y else y - 399) / 400).toLong()
        val yoe = y.toLong() - era * 400
        val mp = (m0 + 9) % 12
        val doy = (153 * mp + 2) / 5 + d0 - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /**
     * Свежесть `2^(−возраст/180)` с полом [FRESH_FLOOR]; неизвестная дата —
     * [FRESH_UNKNOWN] (кейсы 2 и 3 спецификации).
     */
    fun freshness(ageDays: Double?, halfLifeDays: Double = FRESH_HALF_LIFE_DAYS): Double = when {
        ageDays == null -> FRESH_UNKNOWN
        else -> 2.0.pow(-ageDays / halfLifeDays.coerceAtLeast(1.0)).coerceAtLeast(FRESH_FLOOR)
    }

    /**
     * Похожесть двух записей без аудио: артист `0,50`, лейбл `0,15`, альбом
     * `0,15`, жанр-тег `0,15`, близкий год (±1) `0,05`.
     *
     * Отсутствие данных даёт НОЛЬ, а не «похожи наверняка» и не «точно разные»
     * (кейс 18): у половины выдачи ни лейбла, ни года нет, и молчание сервиса не
     * должно ни склеивать вещи, ни объявлять их несравнимыми.
     */
    fun similarity(a: Sig, b: Sig): Double {
        var s = 0.0
        val ka = normArtist(a.artist)
        val kb = normArtist(b.artist)
        if (ka.isNotEmpty() && kb.isNotEmpty() && ka == kb) s += 0.50
        a.label?.let { if (it == b.label) s += 0.15 }
        a.album?.let { if (it == b.album) s += 0.15 }
        a.genre?.let { if (it == b.genre) s += 0.15 }
        val ya = a.year
        val yb = b.year
        if (ya != null && yb != null && abs(ya - yb) <= 1) s += 0.05
        return s
    }

    fun similarity(a: Track, b: Track): Double = similarity(Sig.of(a), Sig.of(b))

    /** Похожесть кандидата с тем, что УЖЕ прозвучало. */
    fun similarity(t: Track, p: Played): Double = similarity(Sig.of(t), Sig.of(p))

    /** Нормализованные поля, по которым меряется похожесть. */
    data class Sig(
        val artist: String,
        val album: String? = null,
        val label: String? = null,
        val genre: String? = null,
        val year: Int? = null,
    ) {
        companion object {
            fun of(t: Track) = Sig(t.artist, albumKey(t), labelKey(t), genreKey(t), t.year)
            fun of(p: Played) = Sig(p.artist, metaKey(p.album), metaKey(p.label),
                metaKey(p.genre), p.year)
        }
    }

    /**
     * Одна РЕАЛЬНО прозвучавшая запись: автор, момент начала (мс) и то, что о
     * ней помнит история.
     *
     * Поля — ровно те, что лежат в `play_history` (артист, альбом, жанр);
     * лейбла и года там нет, и отсутствующее не даёт вклада в похожесть — это
     * правило кейса 18, а не догадка о данных.
     */
    data class Played(
        val artist: String,
        val at: Long,
        val album: String? = null,
        val label: String? = null,
        val genre: String? = null,
        val year: Int? = null,
    )

    /**
     * Что станция уже отдала слушателю в ЭТОЙ сессии.
     *
     * Отдельный тип, а не список треков, потому что квоты оконные: «4 за 3 часа»
     * без времени невозможно посчитать. Считается по всей истории сессии, а не по
     * очередной пачке, — иначе три добора подряд дали бы шесть записей одного
     * артиста, и никто бы это не заметил.
     */
    data class Session(val played: List<Played> = emptyList()) {

        companion object {
            val EMPTY = Session()
        }

        /** По возрастанию времени: «подряд» надо смотреть с конца. */
        private val ordered: List<Played> by lazy { played.sortedBy { it.at } }

        /** Записи внутри скользящего окна, оканчивающегося в [nowMs]. */
        private fun inWindow(nowMs: Long): List<Played> =
            ordered.filter { it.at <= nowMs && nowMs - it.at < SESSION_WINDOW_MS }

        fun artistCount(artist: String, nowMs: Long): Int {
            val a = normArtist(artist)
            if (a.isEmpty()) return 0
            return inWindow(nowMs).count { normArtist(it.artist) == a }
        }

        fun albumCount(album: String, nowMs: Long): Int =
            inWindow(nowMs).count { metaKey(it.album) == album }

        /** Сколько записей подряд (с конца эфира) одного артиста. */
        fun artistRun(artist: String): Int {
            val a = normArtist(artist)
            if (a.isEmpty()) return 0
            var n = 0
            for (p in ordered.asReversed()) {
                if (normArtist(p.artist) != a) break
                n++
            }
            return n
        }

        /** Сколько записей подряд с одного альбома (с конца эфира). */
        fun albumRun(album: String?): Int {
            if (album == null) return 0
            var n = 0
            for (p in ordered.asReversed()) {
                if (metaKey(p.album) != album) break
                n++
            }
            return n
        }

        /**
         * ЖЁСТКАЯ допустимость: проверяется ДО всякой оценки. Ни `R`, ни MMR не
         * могут обойти квоту (кейсы 6–10), и молча нарушать её ранкер не вправе.
         *
         * [nowMs] `<= 0` — «сессии нет», и тогда ничего не запрещается: это
         * законное состояние для первого эфира и для тестов, считавших поведение
         * до появления окна.
         */
        fun eligible(t: Track, nowMs: Long): Boolean {
            if (nowMs <= 0L) return true
            val a = normArtist(t.artist)
            if (a.isNotEmpty()) {
                if (artistCount(a, nowMs) >= ARTIST_CAP) return false
                if (artistRun(a) >= ARTIST_MAX_RUN) return false
            }
            val alb = albumKey(t)
            if (alb != null) {
                if (albumCount(alb, nowMs) >= ALBUM_CAP) return false
                if (albumRun(alb) >= ALBUM_MAX_RUN) return false
            }
            return true
        }

        /** Наибольшая похожесть на последние [MMR_WINDOW] РЕАЛЬНО сыгранных записей. */
        fun redundancy(t: Track): Double =
            ordered.takeLast(MMR_WINDOW).maxOfOrNull { similarity(t, it) } ?: 0.0
    }

    /**
     * Вес одной вещи. Множители, а не слагаемые: признаки усиливают друг
     * друга — знакомый артист в чарте должен звучать заметно чаще случайной
     * находки, а не на пару процентов.
     */
    fun weight(
        track: Track,
        s: Signals,
        taste: Taste,
        nowYear: Int,
        /**
         * Полупериод свежести, ДНЕЙ. 180 — общий («характер станции» по
         * поправке Claude); «незнакомое» — 90, «популярное» — 365. Параметр, а не
         * константа, потому что крутилка без провода была бы настройкой, которая
         * ничего не меняет (AGENTS.md), а экран для неё ещё не решён.
         */
        freshHalfLifeDays: Double = FRESH_HALF_LIFE_DAYS,
    ): Double {
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
        // Расположение с ПК — отдельный множитель, а не добавка к штукам.
        val aff = taste.affinityOf(track.artist)
        if (aff > 0.0) w *= 1.0 + PC_TASTE_MAX * aff

        // Свежесть — ОДНО слагаемое, а не главный признак: половина выдачи не
        // знает даты, и «нет года» не имеет права звучать как «старьё».
        // Множитель при F=1 равен ×1.0, при поле — ×0.7375: классика остаётся
        // в эфире (это и проверяет anOldTrackIsNudgedNotBuried).
        w *= (1.0 - FRESH_WEIGHT) + FRESH_WEIGHT * freshness(ageDays(s, nowYear), freshHalfLifeDays)
        return w + FLOOR
    }

    /**
     * Веса одного броска.
     *
     * Без сыгранной истории — ровно прежние [weight]: считать разнос не по чему,
     * а выдумывать её нельзя. С историей — `R` нормируется по сильнейшему
     * кандидату (0..1) и применяется мягкий разнос
     * `score = 0,75·R − 0,25·похожесть на последние сыгранные` (кейс 11), но не
     * ниже [FLOOR]: похожий кандидат не получает нулевой шанс, он просто
     * уступает менее похожему.
     */
    /**
     * Оценка кандидата для одного броска: `0,75·R − 0,25·похожесть` (кейс 11),
     * где `R` — вес, нормированный по сильнейшему кандидату захода. Отдельная
     * функция потому, что именно эти числа обещает спецификация: при похожести 1
     * кандидат должен остаться с 0,5, а при 0 — с 0,75, и chosen быть менее
     * похожим.
     */
    fun mmrScore(base: Double, top: Double, redundancy: Double): Double =
        (MMR_LAMBDA * (if (top > 0.0) base / top else 0.0)
            - (1.0 - MMR_LAMBDA) * redundancy).coerceAtLeast(FLOOR)

    private fun pickWeights(
        rest: List<Pair<Track, Signals>>,
        taste: Taste,
        nowYear: Int,
        session: Session,
        freshHalfLifeDays: Double,
    ): DoubleArray {
        val base = DoubleArray(rest.size) { i ->
            weight(rest[i].first, rest[i].second, taste, nowYear, freshHalfLifeDays)
        }
        if (session.played.isEmpty()) return base
        val top = base.maxOrNull() ?: return base
        return DoubleArray(rest.size) { i ->
            mmrScore(base[i], top, session.redundancy(rest[i].first))
        }
    }

    /**
     * Отобрать [size] вещей.
     *
     * [maxPerArtist] и [artistGap] — против того, чем портится любая станция:
     * одного артиста подряд. Первый ограничивает, сколько его всего, второй —
     * насколько близко его вещи могут стоять.
     *
     * [session] и [nowMs] — то, что УЖЕ прозвучало в этом эфире. Тогда сверху
     * накладываются жёсткие оконные квоты ([Session.eligible]: артист ≤4 за 3 часа
     * и ≤3 подряд, альбом ≤3 и ≤2 подряд) и мягкий разнос MMR по последним
     * десяти сыгранным. Пустая сессия — ровно прежнее поведение: считать окно
     * не по чем, а выдумывать историю нельзя.
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
        /** Что реально прозвучало в этом эфире (оконные квоты и разнос). */
        session: Session = Session.EMPTY,
        /** Момент подбора, мс. `0` — окно не считается (первый эфир, тесты). */
        nowMs: Long = 0L,
        /** Полупериод свежести этого эфира: 90 — «незнакомое», 180 — общее, 365 — «популярное». */
        freshHalfLifeDays: Double = FRESH_HALF_LIFE_DAYS,
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

        // Жёсткие квоты — ДО оценки: кандидат, нарушающий окно сессии, не
        // участвует ни в каком броске, каким бы сильным он ни был. Пустой
        // остаток при этом остаётся пустым: станция честна (кейс 17), а не
        // тайно снимает ограничение.
        if (nowMs > 0L && session.played.isNotEmpty()) {
            rest.removeAll { (t, _) -> !session.eligible(t, nowMs) }
            if (rest.isEmpty()) return emptyList()
        }

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
            val weights = pickWeights(rest, taste, nowYear, session, freshHalfLifeDays)
            var r = rnd.nextDouble() * weights.sum()
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
