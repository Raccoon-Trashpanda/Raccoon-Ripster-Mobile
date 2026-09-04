package net.ripster.mobile.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track

/**
 * Тот же самый трек на другом сервисе — или ничего.
 *
 * Нужен, когда свой сервис трек не стримит (Tidal отдаёт сегменты) и мы хотим
 * заиграть его из Deezer/Qobuz/Яндекса. Раньше на это шёл обычный поиск по
 * строке «исполнитель название», и он с равным успехом подсовывал ремикс,
 * кавер, лайв или чужую песню с тем же именем: человек жал ▶ на одном треке и
 * слушал другой, ничего об этом не зная.
 *
 * Поэтому здесь подбор строгий, а «похоже» не считается совпадением:
 *
 *  1. **ISRC** — международный код записи. Совпал у обоих — это буквально одна
 *     и та же запись, дальше можно не смотреть. Его отдают Tidal, Qobuz и
 *     Deezer, и это самый надёжный ключ, какой вообще бывает.
 *  2. Нет ISRC хотя бы у одной стороны — требуем совпадения ВСЕГО сразу:
 *     названия, исполнителя, пометки версии и длительности. Порознь любой из
 *     этих признаков обманчив: «Teardrop» у Massive Attack и у кавер-группы
 *     называются одинаково, а ремикс отличается от оригинала только скобками.
 *
 * Не уверены — возвращаем null. Честное «скачай, чтобы послушать» лучше, чем
 * тихо сыгранная не та песня.
 */
object TrackMatch {

    /** Сервисы, которые умеют отдавать поток. Порядок = порядок предпочтения. */
    private val STREAMABLE = listOf(Service.DEEZER, Service.QOBUZ, Service.TIDAL, Service.YANDEX)

    /** Допуск по длительности. Разные мастеринги одной записи гуляют на пару секунд. */
    private const val DURATION_TOLERANCE_MS = 3_000L

    /**
     * Ищет [src] у остальных стриминговых сервисов.
     *
     * @return найденный трек ИЛИ null, если уверенного совпадения нет.
     */
    suspend fun sameTrackElsewhere(
        src: Track,
        timeoutMs: Long = 20_000,
    ): Track? = withContext(Dispatchers.IO) {
        val query = "${src.artist} ${src.title}".trim()
        if (query.isBlank()) return@withContext null
        val candidates = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                STREAMABLE.filter { it != src.service }
                    .mapNotNull { ServiceRegistry.get(it) }
                    .map { c -> async { runCatching { c.search(query).tracks }.getOrDefault(emptyList()) } }
                    .awaitAll()
            }
        }.orEmpty().flatten()
        if (candidates.isEmpty()) return@withContext null

        // 1) ISRC — точное тождество записи.
        val srcIsrc = src.isrc?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (srcIsrc != null) {
            candidates.firstOrNull { it.isrc?.trim()?.uppercase() == srcIsrc }?.let { return@withContext it }
        }
        // 2) Без ISRC — только полное совпадение по всем признакам.
        return@withContext candidates.firstOrNull { looksLikeSameRecording(src, it) }
    }

    /** Совпадает ли ВСЁ: название, исполнитель, пометка версии, длительность. */
    fun looksLikeSameRecording(a: Track, b: Track): Boolean {
        if (normTitle(a.title) != normTitle(b.title)) return false
        if (!artistsOverlap(a.artist, b.artist)) return false
        if (variantTags(a.title) != variantTags(b.title)) return false
        val da = a.durationMs
        val db = b.durationMs
        // Длительность знаем не всегда; если знаем оба — расхождение решает.
        if (da != null && db != null && da > 0 && db > 0) {
            if (kotlin.math.abs(da - db) > DURATION_TOLERANCE_MS) return false
        }
        return true
    }

    /** Слова, отличающие версию записи от оригинала. Их набор обязан совпасть. */
    private val VARIANT_WORDS = setOf(
        "remix", "ремикс", "live", "лайв", "концерт", "acoustic", "акустика",
        "instrumental", "инструментал", "edit", "version", "версия", "mix",
        "cover", "кавер", "demo", "демо", "radio", "extended", "slowed",
        "sped", "reverb", "karaoke", "караоке", "rerecorded", "unplugged",
    )

    private fun variantTags(title: String): Set<String> {
        val low = title.lowercase()
        return VARIANT_WORDS.filter { it in low }.toSet()
    }

    /**
     * Название без того, что не влияет на тождество записи: скобочные пометки
     * убирать НЕЛЬЗЯ (в них и живёт «- Remix»), поэтому чистим только пунктуацию
     * и «feat.», который сервисы пишут кто во что горазд.
     */
    private fun normTitle(s: String): String =
        s.lowercase()
            .replace(Regex("""\b(feat|ft|featuring)\.?\s+.*$"""), " ")
            // Апострофы ВЫБРАСЫВАЕМ, а не заменяем пробелом: иначе «Don't Stop»
            // превращается в «don t stop» и перестаёт совпадать с «Dont Stop»,
            // как его пишет другой сервис (поймано тестом).
            .replace(Regex("""['’`´]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    /**
     * Исполнители «пересекаются»: у сервисов состав пишется по-разному —
     * «Massive Attack», «Massive Attack, Elizabeth Fraser», «Massive Attack &
     * Elizabeth Fraser». Требовать точного равенства значит отвергать верные
     * совпадения, поэтому достаточно, чтобы ПЕРВЫЙ (главный) исполнитель одной
     * стороны встречался у другой.
     */
    private fun artistsOverlap(a: String, b: String): Boolean {
        val na = normTitle(a)
        val nb = normTitle(b)
        if (na.isBlank() || nb.isBlank()) return false
        val leadA = na.split(" , ", ",", " & ", " and ", " feat ").first().trim()
        val leadB = nb.split(" , ", ",", " & ", " and ", " feat ").first().trim()
        if (leadA.isBlank() || leadB.isBlank()) return false
        return na.contains(leadB) || nb.contains(leadA)
    }
}
