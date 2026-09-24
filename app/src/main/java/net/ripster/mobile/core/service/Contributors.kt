package net.ripster.mobile.core.service

/**
 * Кто РЕАЛЬНО звучит на треке: основные артисты, приглашённые, ремиксеры.
 *
 * Модель чистая — никаких DTO и сети. Каждый сервис сам доносит до неё то, что
 * он вообще умеет сказать: у Deezer это список `contributors[]` с ролью, у
 * Qobuz — строка `performers` вида «Name, MainArtist - Name2, FeaturedArtist»,
 * у SoundCloud — структурных кредитов нет вообще, только заголовок трека.
 * Разница между «сервис дал состав» и «мы догадались по заголовку» здесь не
 * хранится: важнее то, что показывает человек на строке.
 *
 * Правило вывода в одну строку — то самое, которым коллаборации уже помечены
 * приложение-wide: имена в ОДНОЙ строке через «, », совместные через «feat.»,
 * без повторов ([display]). Поэтому [net.ripster.mobile.core.model.Track.artist]
 * остаётся тем полем, которое читает весь интерфейс и разбивает
 * [ChartBoost.namesOf], — ни модели, ни теги файлов переписывать не нужно.
 */
data class Contributors(
    val main: List<String> = emptyList(),
    val featured: List<String> = emptyList(),
    val remixers: List<String> = emptyList(),
) {

    val isEmpty: Boolean get() = main.isEmpty() && featured.isEmpty() && remixers.isEmpty()

    /** Кроме основного состава никто не назван — показывать нечего. */
    val isSingle: Boolean get() = featured.isEmpty() && remixers.isEmpty() && main.size <= 1

    /** Все имена в порядке показа. Пустая модель — пустой список. */
    fun names(): List<String> = dedup(main + featured + remixers, emptyList())

    /**
     * Строка исполнителя так, как её видит весь интерфейс: «A, B feat. C».
     *
     * Ремиксер стоит рядом с приглашёнными: для слушателя это тот же смысл —
     * человек, который не основной артист релиза. Дубли режутся без учёта
     * регистра и диакритики: «A feat. A» из-за того, что сервис записал имя
     * строчными буквами, человек не заслужил.
     */
    fun display(): String {
        val head = dedup(main, emptyList())
        // Основной состав молчит — то, что назвали приглашённым, становится
        // шапкой: одиночное «feat. X» на строке артиста выглядит обрывком.
        if (head.isEmpty()) return dedup(featured + remixers, emptyList()).joinToString(", ")
        // Гость, которого шапка уже назвала («Zedd feat. Foxes» + ещё один
        // список, где Foxes приглашённый), не повторяется хвостом.
        val known = head + head.flatMap { ChartBoost.namesOf(it) }
        val rest = dedup(featured + remixers, known)
        return if (rest.isEmpty()) head.joinToString(", ")
        else head.joinToString(", ") + " feat. " + rest.joinToString(", ")
    }

    /**
     * Богаче ли эти данные той строки, что показана уже.
     *
     * Замена имени артиста — правка на виду у человека, поэтому делаем её
     * только когда имён СТАЛО больше. Иначе любой сбой разбора (пустой ответ
     * сервиса, незнакомая роль) стирал бы состав до одного имени.
     */
    fun richerThan(current: String): Boolean {
        val got = display()
        if (got.isBlank() || isSingle) return false
        val mine = names().mapTo(HashSet()) { key(it) }
        return ChartBoost.namesOf(current).size < mine.size
    }

    private fun dedup(list: List<String>, taken: List<String>): List<String> {
        val seen = taken.mapTo(HashSet()) { key(it) }
        val out = ArrayList<String>(list.size)
        for (raw in list) {
            val name = clean(raw)
            if (name.isEmpty()) continue
            if (seen.add(key(name))) out += name
        }
        return out
    }

    companion object {

        /** Больше этого числа имён на строке не показываем (см. [fromRoles]). */
        const val MAX_NAMES = 6

        val EMPTY: Contributors = Contributors()

        /** Блок в qobuz-строке `performers` делится пробел-дефис-пробелом. */
        private val QB_BLOCK = Regex("""\s+-\s+""")

        /** Роли, которые зовут человека «основным» (точное совпадение после нормализации). */
        private val MAIN_ROLES = setOf(
            "mainartist", "artist", "primaryartist", "main", "leadartist", "performer",
        )

        // Композитор, текст, оркестр, дирижёр, продюсер — не те люди, которых
        // слушатель ищет в строке «исполнитель». Не показываем никогда.
        private val IGNORED_ROLES = setOf(
            "composer", "lyricist", "composerlyricist", "text", "writer", "author",
            "producer", "executiveproducer", "orchestra", "conductor", "ensemble",
            "choir", "chorus", "choirmaster", "arranger", "recording", "recordingengineer",
            "mixed", "mixing", "mastering", "engineer", "instrument", "vocal", "vocals",
            "piano", "guitar", "bass", "drums", "editor", "design", "photography",
            "artdirection", "label", "publisher", "dj", "scratch", "audioengineer",
        )

        /**
         * Названия, где «x» и «&» — часть имени, а не стык двух артистов.
         *
         * Запретить сплит по «&» нельзя: «Macklemore & Ryan Lewis» — один дуэт,
         * а «Tiësto & Charlotte de Witte» — двое. Отличить их по строке нельзя
         * даже человеку, поэтому берём список тех, кого знаем. Не попал в
         * список и выглядит как стык — считаем стыком: так честнее по
         * статистике совместных релизов, и ровно поэтому список короткий.
         */
        private val NAMED_TOGETHER = setOf(
            "simon & garfunkel", "hall & oates", "ashford & simpson",
            "macklemore & ryan lewis", "god & money", "dead & alive",
            "dimitri vegas & like mike", "chloe x halle",
            "the x ambassadors", "xxxentacion", "brutus x",
        ).mapTo(HashSet()) { key(it) }

        /** Ключ имени: без регистра, диакритики и не-букв. */
        private fun key(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .lowercase()
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .filter { it.isLetterOrDigit() }

        /** Имя как есть, но без мусора по краям и сервисных кавычек. */
        private fun clean(s: String): String = s.trim()
            .removePrefix(",").removePrefix("-").removePrefix("·")
            .trim()
            .trim('"', '\'')
            .replace(Regex("""\s+"""), " ")
            .take(80)

        /**
         * Из пар «имя — роль» (Deezer `contributors[]`, Qobuz `album.artists[]`).
         *
         * [fallbackMain] — то единственное имя, которое сервис назвал бы и без
         * нас (`artist.name`, `performer.name`). Если разбор дал целый оркестр
         * (классика: дирижёр, солисты, хор — десятки имён) или не дал ничего,
         * строку не меняем: бедная правда лучше красивого списка.
         */
        fun fromRoles(entries: List<Pair<String, String>>, fallbackMain: String = ""): Contributors {
            val main = ArrayList<String>()
            val feat = ArrayList<String>()
            val rem = ArrayList<String>()
            for ((name, role) in entries) {
                if (name.isBlank()) continue
                if (role.isBlank()) { main += name; continue }
                // «Conductor/Performer», «FeaturedArtist, Vocal» — роли идут
                // слэшем или запятой; ровняем к нижнему регистру и без пробелов.
                val tokens = role.lowercase().split('/', ',').map { t ->
                    t.filter { c -> c.isLetterOrDigit() }
                }
                val flat = tokens.joinToString("")
                when {
                    flat.contains("remix") -> rem += name
                    // «FeaturedArtist» содержит «artist» — приглашённого проверяем
                    // раньше основного, иначе он стал бы основным артистом.
                    tokens.any { it.startsWith("featur") } -> feat += name
                    tokens.any { it in MAIN_ROLES } || flat in MAIN_ROLES -> main += name
                    tokens.any { it in IGNORED_ROLES } -> Unit
                    else -> Unit   // роль незнакомая — выдумывать состав не будем
                }
            }
            // Хозяин строки остаётся основным даже если сервис перечлил только
            // приглашённых: «feat. X» без шапки — это обрывок, а не состав.
            if (main.isEmpty() && fallbackMain.isNotBlank()) main += fallbackMain
            val out = Contributors(main.distinct(), feat.distinct(), rem.distinct())
            val only = if (fallbackMain.isBlank()) EMPTY else Contributors(listOf(clean(fallbackMain)))
            return when {
                out.isEmpty -> only
                out.names().size > MAX_NAMES -> only
                else -> out
            }
        }

        /**
         * Строка `performers` Qobuz: «Linda Ronstadt, FeaturedArtist, Vocal - James Taylor, MainArtist».
         *
         * Внутри блока имя — всё до первой запятой, остальное роли. Блоки
         * разделены пробел-дефис-пробелом, поэтому дефис внутри имени
         * («E-Type») строку не режет.
         */
        fun fromQobuzPerformers(raw: String?, fallbackMain: String = ""): Contributors {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return if (fallbackMain.isBlank()) EMPTY else Contributors(listOf(clean(fallbackMain)))
            val pairs = s.split(QB_BLOCK).mapNotNull { block ->
                val b = block.trim()
                if (b.isEmpty()) return@mapNotNull null
                val i = b.indexOf(',')
                if (i < 0) b to "" else b.substring(0, i) to b.substring(i + 1)
            }
            return fromRoles(pairs, fallbackMain)
        }

        /**
         * SoundCloud: структурных кредитов у сервиса нет вообще.
         *
         * Остаётся заголовок, где люди пишут как умеют. Берём только то, что
         * читается однозначно:
         *  • «feat.»/«ft.»/«featuring»/«with» — приглашённый;
         *  • «(David Bowie Remix)» в скобках — ремиксер; одиночное «(Remix)»
         *    или «(Original Mix)» именем не считается;
         *  • «A x B», «A & B», «A vs. B» — но ТОЛЬКО в префиксе до « - », то есть
         *    в той части заголовка, где стоит строка исполнителя: внутри
         *    названия песни («Rhythm & Blues») делить нельзя, и висящее на конце
         *    «Tiësto x» ничего не делит (после «x» буквы нет).
         *
         * [baseArtist] — то, что сервис уже назвал артистом
         * (`publisher_metadata.artist`, иначе ник загрузчика). Он остаётся
         * шапкой и по «, »/«&» НЕ режется: эта строка уже записана тем самым
         * «, »-форматом, и делить её — значит превратить «Simon & Garfunkel» в
         * «Simon feat. Garfunkel».
         */
        fun fromScNames(baseArtist: String, title: String): Contributors {
            val t = title.trim()
            val head = clean(baseArtist)

            val feat = ArrayList<String>()
            val rem = ArrayList<String>()
            val co = ArrayList<String>()

            FEAT_RUN.findAll(t).forEach { m ->
                val rest = beforeDash(m.groupValues[1].substringBefore('('))
                rest.split(SPLIT_SOFT).mapTo(feat) { clean(it) }
            }
            REMIX_PAREN.findAll(t).forEach { m ->
                rem += clean(m.groupValues[1]).removeRemixTail()
            }
            val prefix = clean(beforeDash(t).let { if (it == t) "" else it })
            if (prefix.isNotBlank() && !FEAT_ANY.containsMatchIn(prefix) && key(prefix) !in NAMED_TOGETHER) {
                val names = SPLIT_HARD.split(prefix).map { clean(it) }.filter { it.length >= 3 }
                if (names.size >= 2 && prefix.length <= 70) co += names
            }

            val main = ArrayList<String>()
            if (head.isNotEmpty()) main += head
            main += co
            val out = Contributors(
                main.distinct().filter { !isNoise(it) },
                feat.distinct().filter { !isNoise(it) && it.length >= 2 },
                rem.distinct().filter { !isNoise(it) },
            )
            return if (out.isEmpty && head.isNotEmpty()) Contributors(listOf(head)) else out
        }

        /** «(David Bowie Remix)», «[Anyma Rework]», «(Skrillex VIP)», «(Fisher Bbconf Remix)». */
        private val REMIX_PAREN = Regex(
            """[\(\[]\s*(.+?)\s*(?:remix|rmx|rmxs|bootleg|refix|rework|mash\s?-?\s?-?up|vip|edit)\b[^\)\]]*[\)\]]""",
            RegexOption.IGNORE_CASE,
        )

        /** «feat. X», «ft. X». «with» сюда не берём: в «Dance With Somebody» это часть названия. */
        private val FEAT_RUN = Regex("""(?i)\b(?:feat\.?|ft\.?|featuring)\b[:.]?\s*([^\(\)\[\]\n]+)""")

        private val FEAT_ANY = Regex("""(?i)\b(?:feat\.?|ft\.?|featuring)\b""")

        /** Разделитель «исполнитель — название»: дефис или тире, обнесённые пробелами. */
        private val DASH = Regex("""\s[-–—]\s""")

        private fun beforeDash(s: String): String =
            DASH.find(s)?.let { s.substring(0, it.range.first) } ?: s

        /** Мягкие стыки — для того, что уже стоит после «feat.» (там «x» не нужен). */
        private val SPLIT_SOFT = Regex("""\s*(?:,|&|\b(?:feat\.?|ft\.?)\b|\bvs\.?\b)\s*""", RegexOption.IGNORE_CASE)

        /**
         * Жёсткие стыки — только для префикса заголовка. Смотрим по букве с
         * обеих сторон: «2 x 2», «Tiësto x» на конце и «x-ambassadors» на начале
         * так не режутся.
         */
        private val SPLIT_HARD = Regex(
            """(?<=[\p{L}])\s*(?:,|&|\bx\b|\bvs\.?\b)\s*(?=[\p{L}])""",
            RegexOption.IGNORE_CASE,
        )

        /** Хвост, который скобка лепит после имени ремиксера. */
        private fun String.removeRemixTail(): String = trimEnd()
            .removeSuffix("Version").removeSuffix("version").removeSuffix("Ver.")
            .removeSuffix("VIP").removeSuffix("vip").trim()

        /** Что SoundCloud пишет в скобках вместо имени. */
        private val NOISE = setOf(
            "remix", "remixes", "original", "original mix", "extended", "extended mix",
            "radio", "radio edit", "club", "club mix", "vip", "official", "official video",
            "official audio", "lyrics", "lirik", "explicit", "clean", "instrumental",
            "acapella", "a capella", "live", "mv", "audio", "video", "type beat",
            "free beat", "slowed", "reverb", "sped up", "nightcore", "acoustic",
            "single version", "album version", "intro", "outro", "skit",
        ).mapTo(HashSet()) { key(it) }

        private fun isNoise(s: String): Boolean {
            val k = clean(s)
            return k.isEmpty() || key(k) in NOISE || k.lowercase().trim() in NOISE || key(k).length < 2
        }
    }
}
