package net.ripster.mobile.core.service

/**
 * Один жанр — один ключ, чем бы его ни назвал сервис.
 *
 * Владелец 05.09.2026: «согласовываю все жанры во всех потоковых сервисах,
 * единый модуль на все потоки». Без этого слоя любые рассуждения о жанрах
 * сравнивают строки разных словарей, а они не совпадают ни в чём.
 *
 * Замер 05.09.2026 — 395 живых строк из четырёх словарей:
 *  · Deezer  — 22 названия, И ОНИ ЛОКАЛИЗОВАНЫ: по гео-локали пришёл
 *    болгарский список («Блус», «Рок», «Метъл», «Ритъм енд блус»). То есть
 *    сравнивать с английским «blues» бессмысленно в принципе;
 *  · Apple   — 22 широких, часть составные: «Hip-Hop/Rap», «R&B/Soul»;
 *  · Яндекс  — 190 названий вместе с поджанрами и НЕ-музыкой («Audio Fairy
 *    Tales», «Business», «Biographies and memoirs»);
 *  · ротор Яндекса — 161 слаг без пробелов («alternativemetal», «triphopgenre»).
 *
 * Отсюда три правила, каждое родилось из этих данных, а не из головы:
 *
 *  1. Составное имя разбираем и берём ПЕРВОЕ узнанное: «Rap/Hip Hop» → rap,
 *     «Soul & Funk» → soul. Порядок в имени сервиса не случаен — первым стоит
 *     главное.
 *  2. Слаги ротора несут служебный хвост «genre» («triphopgenre», «bassgenre»,
 *     «idmgenre»). Снимаем его, иначе половина станций Яндекса не узнаётся.
 *  3. Не узнали — `null`, и это «не знаю», а не «неизвестный жанр». Подставить
 *     похожий значит вернуть ту самую подмену, из-за которой синтвейв играл
 *     ликвид-фанк.
 */
object GenreKey {

    /** Канонические ключи. Список закрытый: он — словарь всего приложения. */
    const val TECHNO = "techno"
    const val HOUSE = "house"
    const val TRANCE = "trance"
    const val DNB = "dnb"
    const val DUBSTEP = "dubstep"
    const val BREAKBEAT = "breakbeat"
    const val AMBIENT = "ambient"
    const val IDM = "idm"
    const val DOWNTEMPO = "downtempo"
    const val TRIPHOP = "triphop"
    const val ELECTRONIC = "electronic"
    const val DANCE = "dance"
    const val DISCO = "disco"
    const val ROCK = "rock"
    const val METAL = "metal"
    const val PUNK = "punk"
    const val INDIE = "indie"
    const val ALTERNATIVE = "alternative"
    const val POP = "pop"
    const val RAP = "rap"
    const val RNB = "rnb"
    const val SOUL = "soul"
    const val FUNK = "funk"
    const val JAZZ = "jazz"
    const val BLUES = "blues"
    const val CLASSICAL = "classical"
    const val REGGAE = "reggae"
    const val DUB = "dub"
    const val LATIN = "latin"
    const val COUNTRY = "country"
    const val FOLK = "folk"
    const val WORLD = "world"
    const val SOUNDTRACK = "soundtrack"
    const val KIDS = "kids"
    const val CHRISTIAN = "christian"
    const val HOLIDAY = "holiday"

    /**
     * Синонимы. Ключи здесь уже НОРМАЛИЗОВАНЫ (см. [norm]): только строчные
     * буквы и цифры, без пробелов и знаков. Русские и болгарские строки —
     * не «на всякий случай», а прямо из замера словаря Deezer.
     */
    /**
     * ПОСЕВ, а не словарь.
     *
     * Здесь только латинские написания — то, чем оперирует большинство
     * сервисов. Локализованных названий тут намеренно НЕТ, хотя замер их и
     * нашёл (Deezer отдал болгарский список целиком): вписывать чужие
     * словари руками — это ровно «носить в себе то, что можно выучить».
     * Завтра придёт турецкий, и таблица снова отстанет.
     *
     * Локализованные ярлыки выучивает [GenreLearner] из того, что и так
     * проходит через приложение: один артист приносит ярлык на своём языке от
     * одного сервиса и узнаваемый — от другого, и связь получается сама.
     *
     * Ключи здесь уже НОРМАЛИЗОВАНЫ (см. [norm]).
     */
    private val SYN: Map<String, String> = buildMap {
        // Имя НЕ `put`: локальная функция с таким именем затеняет `Map.put`
        // и начинает звать саму себя — класс падает на инициализации со
        // StackOverflowError. Поймано первым же прогоном тестов.
        fun of(key: String, vararg names: String) {
            names.forEach { this[norm(it)] = key }
        }
        of(TECHNO, "techno", "minimal techno", "hard techno", "acid techno")
        of(HOUSE, "house", "deep house", "tech house", "progressive house")
        of(TRANCE, "trance", "psytrance", "goa trance")
        of(DNB, "dnb", "drum and bass", "drum n bass", "drum & bass", "drumandbass",
            "jungle", "liquid funk")
        of(DUBSTEP, "dubstep", "bass")
        of(BREAKBEAT, "breakbeat", "breaks")
        of(AMBIENT, "ambient", "new age", "meditation", "relax")
        of(IDM, "idm", "experimental")
        of(DOWNTEMPO, "downtempo", "lounge", "chillout", "chill out")
        of(TRIPHOP, "trip hop", "triphop")
        of(ELECTRONIC, "electronic", "electronica", "electro", "edm")
        of(DANCE, "dance")
        of(DISCO, "disco")
        of(ROCK, "rock", "hard rock", "classic rock", "post rock", "stoner rock", "psychedelic rock")
        of(METAL, "metal", "heavy metal", "black metal", "death metal", "thrash metal",
            "doom metal", "metalcore")
        of(PUNK, "punk", "hardcore", "post punk")
        of(INDIE, "indie", "indie rock", "indie pop", "local indie")
        of(ALTERNATIVE, "alternative", "alternative rock")
        of(POP, "pop", "pop music", "synthpop", "k-pop", "kpop")
        of(RAP, "rap", "hip hop", "hiphop", "hip-hop")
        of(RNB, "rnb", "r&b", "r and b", "rhythm and blues")
        of(SOUL, "soul", "motown")
        of(FUNK, "funk")
        of(JAZZ, "jazz", "smooth jazz", "nu jazz", "vocal jazz", "bebop", "big band")
        of(BLUES, "blues")
        of(CLASSICAL, "classical", "classical music", "opera")
        of(REGGAE, "reggae", "reggaeton", "ska")
        of(DUB, "dub")
        of(LATIN, "latin", "latin music", "bossa nova", "salsa", "tango")
        of(COUNTRY, "country")
        of(FOLK, "folk", "bard", "singer songwriter", "celtic")
        of(WORLD, "world", "worldwide", "african", "african music", "asian music",
            "indian music", "brazilian music", "arabic", "balkan")
        of(SOUNDTRACK, "soundtrack", "films games", "films", "musical", "videogame")
        of(KIDS, "kids", "children", "childrens music", "for children")
        of(CHRISTIAN, "christian", "gospel", "worship")
        of(HOLIDAY, "holiday", "christmas", "christmas repetoire", "christmas repertoire")
    }
    /**
     * Слова, которые означают НЕ МУЗЫКУ.
     *
     * В словаре Яндекса аудиокниги и подкасты лежат вперемешку с жанрами, и
     * «Children’s Poems» ни в коем случае не должно стать «детской музыкой»:
     * такая вещь попадёт в станцию и будет там читать стихи. Список короткий и
     * закрытый — по головным словам, которые у музыки не встречаются.
     */
    private val NON_MUSIC = setOf(
        "book", "books", "poem", "poems", "tale", "tales", "fairy",
        "biography", "biographies", "memoir", "memoirs", "business",
        "podcast", "podcasts", "audiobook", "lecture", "lectures",
    )

    /** Только буквы и цифры, в нижнем регистре: «Hip-Hop/Rap» → «hiphoprap». */
    fun norm(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Части составного имени, в порядке значимости.
     * «Rap/Hip Hop» → [«Rap», «Hip Hop»]; «Soul & Funk» → [«Soul», «Funk»].
     *
     * По амперсанду режем ТОЛЬКО когда он окружён пробелами. Иначе «R&B/Soul»
     * распадалось на «R», «B», «Soul», первые две части ничего не значили, и
     * жанром объявлялся Soul вместо R&B — поймано первым же прогоном.
     */
    fun parts(raw: String): List<String> =
        raw.replace(" & ", "/").split('/', ',', '|', '+')
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** Ключи по убыванию длины — чтобы «triphop» победил «pop» в «triphop». */
    private val KEYS_BY_LEN: List<String> by lazy {
        SYN.values.distinct().sortedByDescending { it.length }
    }

    /**
     * Канонический ключ жанра, или `null` если не узнали.
     *
     * `null` возвращается и для НЕ-музыки — «Business», «Audio Fairy Tales»,
     * «Biographies and memoirs» приходят в том же словаре Яндекса, и назвать
     * их жанром было бы неправдой.
     */
    fun of(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // Целиком — самый точный случай.
        SYN[norm(s)]?.let { return it }
        // Слаг ротора: «triphopgenre», «bassgenre», «idmgenre».
        val slug = norm(s)
        if (slug.endsWith("genre")) SYN[slug.removeSuffix("genre")]?.let { return it }
        val words = s.split(' ', '-', '_', '\'', '\u2019').filter { it.isNotBlank() }
        // Не музыка — сразу и без разговоров, ДО любых догадок по словам.
        if (words.any { norm(it) in NON_MUSIC }) return null

        // Составное имя: первое узнанное.
        val ps = parts(s)
        if (ps.size > 1) {
            for (p in ps) of(p)?.let { return it }
        }

        // Уточнение перед главным словом: «Alternative metal» → metal,
        // «Contemporary Classical Music» → classical. Идём с конца, но не
        // дальше предпоследнего слова: у названий жанра главное слово стоит
        // в хвосте, а совпадение где-то в начале длинной фразы — это уже не
        // жанр, а случайность.
        if (words.size > 1) {
            for (w in words.reversed().take(2)) SYN[norm(w)]?.let { return it }
            SYN[norm(words.first())]?.let { return it }
        }

        // Слипшийся слаг ротора: «allrock», «classicmetal», «alternativemetal».
        // Разделителя в них нет вовсе, поэтому ищем канонический ключ в конце
        // строки. Самые длинные первыми, иначе «triphop» проиграет «pop».
        for (k in KEYS_BY_LEN) {
            if (slug.length > k.length && slug.endsWith(k)) return k
        }
        return null
    }

    /** Узнаём ли мы этот жанр вообще. */
    fun known(raw: String?): Boolean = of(raw) != null
}
