package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track

/**
 * Кандидат в станцию с ЧЕСТНЫМ ответом, что это за музыка.
 *
 * [truth] — настоящий жанр (канонический ключ [GenreKey]), которого в реальных
 * данных нет ни у одного сервиса: сверка видит только ярлык. Стенд держит правду
 * отдельно именно затем, чтобы мерить утечку, а не перекладывать weights из
 * одного поля в другое.
 *
 * [declared] — то, что сервис ДЕЙСТВИТЕЛЬНО отдаёт в поле жанра: `null` у
 * Deezer-топа и у половины выдачи, «Electronic» у Apple там, где трек на самом
 * деле брейкбит, болгарское «Техно» у локализованного Deezer. Разные написания
 * одного жанра — не украшение теста, а главный источник ошибок сверки
 * (см. [GenreKey]).
 */
class Cand(
    val artist: String,
    val title: String,
    /** Объявленный сервисом жанр; `null` — сервис молчит. */
    val declared: String?,
    /** Настоящий жанр: ключ GenreKey. Правде сверка не видна. */
    val truth: String,
    val service: Service = Service.DEEZER,
    val pop: Double? = null,
    val year: Int? = null,
    val label: String? = null,
    /** Часовая сборка, притворившаяся треком (ловит [DjSetFilter]). */
    val djSet: Boolean = false,
) {
    fun track(): Track = Track(
        id = "$service|$artist|$title",
        title = if (djSet) "$title (1 Hour Continuous Mix)" else title,
        artist = artist,
        service = service,
        genre = declared,
        label = label,
        popularity = pop,
        year = year,
    )
}

/**
 * Реалистичные пулы-кандидаты для стенда точности станции.
 *
 * Набор повторяет живую сборку ([StationBuilder.build]): курируемый канон
 * артистов жанра с Deezer-топом (жанр трека НЕ отдаётся), станции Яндекс и
 * SoundCloud, редакторская подборка Apple с ПК и поиск по названию жанра у
 * каждого сервиса. Пропорции сняты с живых жалоб 05.09 и 13.09.2026:
 *
 *  · Deezer-топ каноновых артистов — всегда без жанра, и часть артистов тега
 *    «breakbeat» на деле делает прог-хаус или рэп (жалоба 13.09.2026);
 *  · Apple отдаёт ШИРОКИЙ жанр: «Electronic» там, где нужен «Breakbeat»;
 *  · Яндекс и SoundCloud объявляют жанр, но пишут его по-своему и иногда мимо;
 *  · выдача поиска по слову «breakbeat» на четверть состоит из чужого — именно
 *    поэтому она не является станцией сама по себе.
 *
 * Имена артистов и лейблы выдуманы: стенд не утверждает ничего про живых людей,
 * он проверяет правило отбора.
 */
object StationPools {

    /** Что мерим: слаг плитки → точный запрос сборки (см. [WAVE_STATIONS]). */
    val SEEDS: List<Pair<String, String>> = listOf(
        "breakbeat" to "breakbeat",
        "techno" to "techno",
        "deephouse" to "deep house",
        "dnb" to "drum and bass",
        "hiphop" to "hip hop",
        "ambient" to "ambient",
    )

    /** Колода: источники как их видит [StationAssembly] плюс карта правды. */
    class Deck(
        val station: String,
        val sources: List<StationAssembly.Source>,
        /** id трека → настоящий жанр. */
        val truth: Map<String, String>,
        /** Сколько кандидатов в колоде и сколько из них своих по правде. */
        val supply: Int,
        val ownSupply: Int,
    )

    /** Как ЭТОТ жанр объявляет каждый из сервисов (написания живые). */
    private fun spellings(key: String): List<String> = when (key) {
        GenreKey.BREAKBEAT -> listOf("Breakbeat", "breaks", "Big Beat", "Nu Skool Breaks", "Брейкбит")
        GenreKey.TECHNO -> listOf("Techno", "Minimal Techno", "Техно", "Melodic Techno", "Hard Techno")
        GenreKey.HOUSE -> listOf("House", "Deep House", "Tech House", "Soulful House", " хаус")
        GenreKey.DNB -> listOf("Drum and Bass", "Drum & Bass", "Jungle", "Liquid Funk", "драм-н-бэйс")
        GenreKey.RAP -> listOf("Rap", "Hip-Hop/Rap", "хип-хоп", "Rap and Hip-Hop", "Phonk")
        GenreKey.AMBIENT -> listOf("Ambient", "New Age", "Эмбиент", "Drone", "Meditation")
        else -> listOf(key.replaceFirstChar { it.uppercase() })
    }

    /** Соседи-обманщики: то, что подмешивается к этому жанру в живых пулах. */
    private fun confusers(key: String): List<String> = when (key) {
        GenreKey.BREAKBEAT -> listOf(GenreKey.HOUSE, GenreKey.RAP, GenreKey.POP, GenreKey.TRANCE)
        GenreKey.TECHNO -> listOf(GenreKey.HOUSE, GenreKey.TRANCE, GenreKey.POP, GenreKey.DUBSTEP)
        GenreKey.HOUSE -> listOf(GenreKey.TECHNO, GenreKey.DISCO, GenreKey.POP, GenreKey.RNB)
        GenreKey.DNB -> listOf(GenreKey.BREAKBEAT, GenreKey.DUBSTEP, GenreKey.RAP, GenreKey.TECHNO)
        GenreKey.RAP -> listOf(GenreKey.RNB, GenreKey.POP, GenreKey.HOUSE, GenreKey.ROCK)
        GenreKey.AMBIENT -> listOf(GenreKey.CLASSICAL, GenreKey.FOLK, GenreKey.POP, GenreKey.DUBSTEP)
        else -> listOf(GenreKey.POP, GenreKey.ROCK)
    }

    private fun labelFor(key: String): String = when (key) {
        GenreKey.HOUSE -> "Progressive House"
        GenreKey.RAP -> "Rap"
        GenreKey.POP -> "Pop"
        GenreKey.TRANCE -> "Trance"
        GenreKey.DUBSTEP -> "Bass"
        GenreKey.CLASSICAL -> "Classical"
        GenreKey.DISCO -> "Disco"
        GenreKey.RNB -> "R&B"
        GenreKey.ROCK -> "Rock"
        GenreKey.FOLK -> "Folk"
        GenreKey.TECHNO -> "Techno"
        GenreKey.BREAKBEAT -> "Breakbeat"
        GenreKey.DNB -> "Drum and Bass"
        else -> "Other"
    }

    private val LABELS = listOf(
        "Kaleophon Records", "Nachtwerk", "Sirena Tapes", "Blackline Music",
        "Rothandel", "Izolde recordings", "Melodiya", "Northpoint",
    )

    private val STEMS = listOf(
        "Kaspar", "Volna", "Nitro", "Aurel", "Deep Field", "Shtorm", "Lumen",
        "Cassette", "Orbit", "Vetro", "Kiloton", "Fonarik", "Mirage", "Blokha",
    )

    private fun artistName(tag: String, i: Int): String =
        "${STEMS[(i + tag.length) % STEMS.size]} ${tag.take(3).uppercase()}${i % 9}"

    /**
     * Курируемый канон: топ-треки артистов, которых MusicBrainz метит тегом
     * жанра. Deezer в топе артиста жанр НЕ отдаёт — вот главная дыра сверки:
     * два из десяти «каноновых» артистов на деле делают соседний жанр.
     */
    private fun canonCands(station: String, key: String, offKey: String): List<Cand> {
        val out = ArrayList<Cand>()
        for (a in 0 until 10) {
            val name = artistName("canon$a", a)
            // Артист жанрового тега может быть артистом НА СТЫКЕ: тег
            // MusicBrainz это допускает, и тогда его топовые треки — чужое.
            val real = if (a == 2 || a == 7) offKey else key
            for (n in 0 until 3) {
                out += Cand(
                    artist = name,
                    title = "$name tune ${n + 1}",
                    declared = null,
                    truth = real,
                    service = Service.DEEZER,
                    pop = listOf(0.9, 0.55, 0.2)[n],
                    year = 2016 + (a + n) % 10,
                    label = LABELS[(a + n) % LABELS.size],
                )
            }
        }
        // Один артист канола виден и в другой выдаче этого же захода — там его
        // жанр объявлен. Именно так дирижёр узнаёт правду о Deezer-топе.
        // Второго «стыкового» артиста так не видно ничем: он и остаётся дырой,
        // которую стенд считает честно.
        out += Cand(
            artist = out[2 * 3].artist,
            title = "same artist elsewhere",
            declared = labelFor(offKey),
            truth = offKey,
            service = Service.SOUNDCLOUD,
            pop = 0.4,
            year = 2021,
        )
        return out
    }

    /** Курируемая станция сервиса: жанр объявлен, но не всегда верен. */
    private fun curatedCands(key: String, service: Service, n: Int, offShare: Int, sloppy: Boolean): List<Cand> {
        val offs = confusers(key)
        val own = spellings(key)
        return (0 until n).map { i ->
            val wrong = i % offShare == offShare - 1
            val confKey = offs[(i / offShare) % offs.size]
            Cand(
                artist = artistName("${service.name}$i", i),
                title = "${if (sloppy) "roller " else ""}track ${i + 1}",
                declared = if (wrong) labelFor(confKey) else own[i % own.size],
                truth = if (wrong) confKey else key,
                service = service,
                pop = 0.3 + 0.02 * (i % 30),
                year = 2012 + i % 14,
                label = LABELS[i % LABELS.size],
                djSet = sloppy && i % 9 == 0,
            )
        }
    }

    /**
     * Редакторская подборка Apple: жанр ШИРОКИЙ — «Electronic» вместо того
     * жанра, который обещает плитка. Это не ложь и не подтверждение: просто
     * сервис ручается только за половину правды.
     */
    private fun appleCands(key: String, n: Int = 14): List<Cand> {
        val wide = if (key == GenreKey.RAP) "Hip-Hop/Rap" else "Electronic"
        val offs = confusers(key)
        return (0 until n).map { i ->
            val wrong = i % 7 == 3
            val confKey = offs[i % offs.size]
            Cand(
                artist = artistName("ap$i", i),
                title = "editorial ${i + 1}",
                declared = if (wrong) labelFor(confKey) else wide,
                truth = if (wrong) confKey else key,
                service = Service.APPLE,
                pop = 0.6 + 0.02 * (i % 15),
                year = 2020 + i % 6,
                label = LABELS[i % LABELS.size],
            )
        }
    }

    /**
     * Поиск по названию жанра: четверть выдачи — чужое, половина молчит о
     * жанре, и один кандидат из восьми объявляет ВЕРНЫЙ жанр, оставаясь чужим
     * (ярлык навесил сам загрузчик). Последний случай исправить нечем —
     * стенд держит его в цене, чтобы проверка не превратилась в самоподтверждение.
     */
    private fun searchCands(key: String, service: Service, n: Int): List<Cand> {
        val offs = confusers(key)
        val own = spellings(key)
        return (0 until n).map { i ->
            val confKey = offs[i % offs.size]
            val lied = i % 8 == 5
            val mode = when {
                lied -> 4
                else -> i % 4
            }
            val (declared, truth) = when (mode) {
                0 -> own[i % own.size] to key
                1 -> labelFor(confKey) to confKey
                2 -> null to key
                3 -> null to confKey
                else -> own[i % own.size] to confKey        // ярлык врёт
            }
            Cand(
                artist = artistName("${service.name}$i", i),
                title = "$key vocals ${i + 1}",
                declared = declared,
                truth = truth,
                service = service,
                pop = if (service == Service.SOUNDCLOUD) 0.02 + 0.01 * (i % 20) else null,
                year = if (i % 5 == 0) null else 2005 + i % 21,
                label = LABELS[(i + service.ordinal) % LABELS.size],
                djSet = service == Service.SOUNDCLOUD && i % 6 == 0,
            )
        }
    }

    /** Один источник колоды: кандидаты плюс его свойства из [StationAssembly.Source]. */
    private class Group(val cands: List<Cand>, val vetted: Boolean, val lead: Boolean)

    /** Колода источников для одного сида станции. */
    fun deck(station: String): Deck {
        val key = GenreKey.of(station) ?: error("стенд работает на узнаваемых жанрах: $station")
        val offKey = confusers(key).first()
        val groups = listOf(
            Group(canonCands(station, key, offKey), vetted = true, lead = true),
            Group(curatedCands(key, Service.YANDEX, 24, offShare = 5, sloppy = false), vetted = true, lead = true),
            Group(appleCands(key), vetted = true, lead = true),
            Group(curatedCands(key, Service.SOUNDCLOUD, 26, offShare = 4, sloppy = true), vetted = true, lead = false),
            Group(searchCands(key, Service.QOBUZ, 32), vetted = false, lead = false),
            Group(searchCands(key, Service.DEEZER, 24), vetted = false, lead = false),
            Group(searchCands(key, Service.YANDEX, 28), vetted = false, lead = false),
        )
        val sources = groups.map {
            StationAssembly.Source(it.cands.map { c -> c.track() }, vetted = it.vetted, lead = it.lead)
        }
        val truth = HashMap<String, String>()
        var supply = 0
        var own = 0
        groups.forEach { g ->
            g.cands.forEach {
                truth[it.track().id] = it.truth
                supply++
                if (it.truth == key) own++
            }
        }
        return Deck(station, sources, truth, supply, own)
    }
}
