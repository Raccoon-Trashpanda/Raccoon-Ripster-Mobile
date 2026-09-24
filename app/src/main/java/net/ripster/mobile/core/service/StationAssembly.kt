package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Track

/**
 * Сборка эфира из опрошенных источников — БЕЗ сети и без сервисов.
 *
 * Это вторая половина того, что делает [StationBuilder]: первая спрашивает
 * источники, вторая решает, что из пришедшего играть и в каком порядке. Раньше
 * обе были в одном `build()`, и проверить решение можно было только включив
 * станцию на телефоне: жалоба владельца 13.09.2026 («брейкбит-станция подмешала
 * прог-хаус и франц-реп») разбиралась глазами по живому эфиру, потому что
 * offline-замера у отбора не было. Здесь та же логика одним чистым вызовом —
 * тот же сверочный фильтр, та же двуходовая очередь [StationRanker].
 *
 * Ничего не решает про сеть, кэш и дирижёра жанров; получает уже собранные
 * пулы и [GenreLearner], которого сами пулы и кормят (см. [observe]).
 */
object StationAssembly {

    /**
     * Один опрошенный источник.
     *
     * [vetted] — жанр уже гарантирован источником, сверять его не нужно (и
     * нечем: у топ-треков артиста Deezer жанра не отдаёт).
     * [lead] — идёт в начало эфира. Это РАЗНЫЕ свойства: чарт SoundCloud по
     * жанру верен жанрово, но по качеству неровен, и во главу станции его не
     * ставят.
     */
    data class Source(
        val tracks: List<Track>,
        val vetted: Boolean,
        val lead: Boolean = vetted,
    )

    /** Жанр, приведённый к сравнимому виду: только буквы и цифры в нижнем регистре. */
    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Слова, по которым трек считается принадлежащим жанру станции. */
    private fun genreWords(query: String): List<String> =
        query.lowercase().split(' ', '-', '/').filter { it.length >= 3 }

    /** Жанр, объявленный САМИМ сервисом. Одно поле на всех: Qobuz и Apple
     *  заполняли его и раньше, SoundCloud и Яндекс — теперь тоже, Deezer в
     *  поиске жанр не отдаёт, и его треки идут как «жанр неизвестен». */
    fun declaredGenre(t: Track): String? =
        (t.genre ?: t.raw["genre"])?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Показать дирижёру всё, что и так прошло через руки: ярлыки жанров от
     * разных сервисов на одних и тех же артистах. Никаких лишних запросов —
     * учимся на том, что уже скачано.
     *
     * Это ПЕРВЫЙ шаг сборки, а не услуга вызывающему: без этих наблюдений у
     * трека из Deezer-топа не было бы ни одного свидетельства о жанре, хотя
     * тот же артист в этом же заходе приходит из Яндекс-чарта со своим ярлыком.
     */
    private fun observe(genres: GenreLearner, sources: List<Source>) {
        sources.forEach { pool ->
            pool.tracks.forEach { t ->
                declaredGenre(t)?.let { genres.observe(t.artist, it) }
            }
        }
    }

    /**
     * Что метаданные о вещи говорят относительно жанра станции. Три ответа, а не
     * два: молчание и противоречие — это разные новости, и реагировать на них
     * одинаково нельзя.
     */
    private enum class Verdict {
        /** Явно чужое: известный жанр, не совпадающий со станцией и не её зонтик. */
        OFF,
        /** Ни подтверждения, ни опровержения: жанра нет, он не узнался или это зонтик. */
        UNKNOWN,
        /** Ровно жанр станции — объявлен самим сервисом или всем миром про этого артиста. */
        ON,
    }

    /**
     * Вердикт по треку: сначала его собственный объявленный жанр, потом — что
     * об этом артисте говорят остальные источники этого же захода.
     *
     * Второй шаг и есть главный смысл [observe]: у трека из Deezer-топа поля
     * жанра нет, зато у того же артиста в Яндекс-чарте оно есть, и «стыковой»
     * артист перестаёт быть слепым пятном станции.
     *
     * Возвращает [Verdict.OFF] ТОЛЬКО когда есть узнанный жанр и он противоречит
     * станции. Нет жанра / ярлык не выучен → [Verdict.UNKNOWN]: такие не трогаем,
     * иначе вылетел бы весь канон и Deezer-топ (у них жанра в поиске нет) и
     * станция снова опустела бы. То есть это осторожное «убрать заведомо
     * чужое», а не «оставить только доказанно своё».
     */
    private fun verdict(t: Track, stationKey: String?, genres: GenreLearner): Verdict {
        if (stationKey == null) return Verdict.UNKNOWN
        val key = declaredGenre(t)?.let { genres.of(it) } ?: genres.artistGenre(t.artist)
            ?: return Verdict.UNKNOWN
        return when {
            key == stationKey -> Verdict.ON
            GenreKey.contradicts(key, stationKey) -> Verdict.OFF
            else -> Verdict.UNKNOWN       // зонтик: «Electronic» над брейкбитом
        }
    }

    private fun onGenre(t: Track, station: String, words: List<String>, genres: GenreLearner): Boolean {
        val raw = declaredGenre(t) ?: return false
        val g = norm(raw)
        if (g.isEmpty()) return false

        // Сначала спрашиваем дирижёра: он сводит ярлыки РАЗНЫХ сервисов к
        // одному ключу и умеет то, чего сравнение строк не умеет в принципе —
        // например, что болгарское «Блус» от Deezer и «Blues» от Apple это
        // один жанр. Если оба ключа известны, ответ однозначен, и гадать по
        // подстрокам уже незачем.
        val tk = genres.of(raw)
        val sk = genres.of(station)
        if (tk != null && sk != null) return tk == sk

        // Ключа нет — значит «не знаю», и работает прежняя сверка по словам.
        val q = norm(station)
        if (q.isNotEmpty() && (g.contains(q) || q.contains(g))) return true
        return words.any { w -> norm(w).length >= 4 && g.contains(norm(w)) }
    }

    /**
     * Что получилось из одного захода: эфир и сколько вещей осталось от каждого
     * источника ПОСЛЕ сверки жанра. Второе нужно разбору — без него видно
     * только итог, а итог не говорит, чей это вклад: канона жанра, курируемой
     * станции сервиса или выдачи поиска. Ровно на этом 05.09.2026 разбор
     * встал: «Melodic Techno» заиграла Lana Del Rey, и понять, откуда она,
     * было нечем.
     */
    data class Air(val tracks: List<Track>, val kept: List<Int>, val dropped: List<Int> = emptyList())

    /**
     * Отдать эфир на [size] вещей из [sources].
     *
     * Пустой список — законный ответ: своего набралось меньше порога, и экран
     * скажет правду вместо случайной музыки (см. [StationBuilder.Outcome]).
     */
    fun assemble(
        station: String,
        sources: List<Source>,
        genres: GenreLearner,
        chart: Set<String> = emptySet(),
        taste: StationRanker.Taste = StationRanker.Taste.EMPTY,
        seed: Long = 0L,
        size: Int = 30,
        nowYear: Int = 2026,
    ): Air {
        if (sources.isEmpty() || size <= 0) return Air(emptyList(), emptyList())

        observe(genres, sources)
        val words = genreWords(station)
        val stationKey = genres.of(station)
        val kept = sources.map { pool ->
            val byGenre =
                // Курируемые пулы (станции сервисов, канон, Apple) больше не
                // проходят СЛЕПО: из них убирается ЯВНО чужое (известный жанр,
                // противоречащий станции) — так «чужое» перестаёт течь из
                // vetted-источников, но треки без жанра остаются (канон цел).
                if (pool.vetted) pool.tracks.filterNot { verdict(it, stationKey, genres) == Verdict.OFF }
                else pool.tracks.filter { onGenre(it, station, words, genres) }
            // Часовые сборки проходят сверку жанра ЧЕСТНО — слово «melodic
            // techno» в названии у них есть, — но станция из DJ-сетов
            // неслушаема. См. DjSetFilter.
            DjSetFilter.tracksOnly(byGenre)
        }

        // Оценка и отбор — одним слоем (StationRanker), но НЕСКОЛЬКИМИ заходами:
        // доказанное своим жанром → молчаливое → добор из поиска.
        //
        // Деление на «курируемое» и «добор» остаётся ЖЁСТКИМ, а не превращается
        // в очередное слагаемое веса. Когда выдача поиска шла вперемешку с
        // каноном жанра, половину эфира занимали любительские «Dub Techno
        // Sessions Episode 98» рядом с DeepChord и Monolake — ровно то, про что
        // владелец сказал «никто бы такое не стал слушать». Мягкий вес при
        // удачном броске вернул бы именно это.
        //
        // Подъём чартом больше не отдельная перестановка списков: «артист в
        // чарте» — это признак для ранжирования, там ему и место.
        fun signalsFor(sourceIndex: Int, t: Track) = StationRanker.Signals(
            vetted = sources[sourceIndex].vetted,
            charting = ChartBoost.isCharting(t.artist, chart),
            popularity = t.popularity,
            year = t.year,
        )

        fun candidatesOf(
            wantLead: Boolean,
            /**
             * Оставить только вещи с ЯВНЫМ жанровым свидетельством (true), только
             * без него (false) или всё подряд (null). См. три захода ниже.
             */
            wantProven: Boolean? = null,
        ): List<Pair<Track, StationRanker.Signals>> =
            kept.flatMapIndexed { i, list ->
                if (sources[i].lead != wantLead) emptyList()
                else list.mapNotNull { t ->
                    if (wantProven != null &&
                        (verdict(t, stationKey, genres) == Verdict.ON) != wantProven
                    ) null else t to signalsFor(i, t)
                }
            }

        val seedOrOne = if (seed == 0L) 1L else seed
        val out = ArrayList<Track>()
        // Курируемое идёт ДВУМЯ ярусами. Обещание станции — жанр, поэтому первым
        // слоем идёт то, про что СКАЗАНО, что это он (ярлык сервиса или, если
        // сервис молчит, другой сервис про того же артиста), и только после
        // этого — вещи без свидетельства. Молчание мы не наказываем отсевом (это
        // не подмена), но и ставить его впереди доказанного нельзя: ровно так и
        // выглядела жалоба 13.09.2026 — «стыковой» артист из Deezer-топа с самой
        // высокой популярностью забирал начало эфира чужим жанром.
        out += StationRanker.rank(
            candidatesOf(wantLead = true, wantProven = true), taste = taste,
            seed = seedOrOne, size = size, nowYear = nowYear,
        )
        if (out.size < size) out += StationRanker.rank(
            candidatesOf(wantLead = true, wantProven = false), taste = taste,
            seed = seedOrOne + 691L, size = size - out.size, nowYear = nowYear, already = out,
        )
        if (out.size < size) {
            out += StationRanker.rank(
                candidatesOf(wantLead = false), taste = taste, seed = seedOrOne + 7919L,
                size = size - out.size, nowYear = nowYear, already = out,
            )
        }
        return Air(
            tracks = out,
            kept = kept.map { it.size },
            dropped = sources.indices.map { sources[it].tracks.size - kept[it].size },
        )
    }
}
