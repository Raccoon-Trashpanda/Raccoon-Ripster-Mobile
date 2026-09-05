package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Track

/**
 * Отсеять то, что не трек: часовые сеты, миксы и подкасты.
 *
 * Разбор 05.09.2026. Станция «Melodic Techno» состояла ЦЕЛИКОМ из вещей вида
 * «Melodic Techno - Best Mix 2025», «LIVE @SUNSET HILL (Melodic Techno Set)»,
 * «1 Hour Melodic Techno», «Melodic Techno Mix 2026 🔥». Сверку жанра они
 * проходили честно — слово «melodic techno» в названии есть, — но слушать
 * станцию из часовых миксов невозможно, и это ровно та «шляпа», про которую
 * владелец сказал «никто бы такое не стал слушать».
 *
 * Ошибиться здесь легко в обе стороны, поэтому:
 *
 *  - голое слово «mix» НЕ признак. В электронной музыке «Extended Mix»,
 *    «Original Mix», «Club Mix», «Radio Mix» — это обычные названия треков, и
 *    отсев по «mix» выкосил бы половину настоящего техно;
 *  - длительность — признак сильный и самостоятельный, но `null` означает
 *    «сервис не сказал», а не «короткий». Не знаем — не судим по ней.
 */
object DjSetFilter {

    /** Дольше этого одна вещь в станции жанра — уже не трек, а сет. */
    const val LONG_MINUTES = 16

    /**
     * Обороты, которые встречаются в названиях СБОРОК и не встречаются в
     * названиях треков. Каждый добавлен по живому примеру, а не «на всякий».
     */
    private val MARKERS = listOf(
        "dj set", "live set", "liveset", "full set", "b2b",
        "best mix", "mix 20", "mixtape", "megamix", "continuous mix",
        "episode", "podcast", "radioshow", "radio show", "guest mix",
        "hour of", "hours of", "hour ", "minute mix",
        "@", " set)", " set]", "(set", "compilation", "yearmix",
    )

    /** Похоже ли на сборку по НАЗВАНИЮ. */
    fun looksLikeSetByTitle(title: String): Boolean {
        val t = " " + title.lowercase().replace('—', '-') + " "
        return MARKERS.any { it in t }
    }

    /** Похоже ли на сборку вообще: название либо длительность. */
    fun isSet(track: Track): Boolean {
        if (looksLikeSetByTitle(track.title)) return true
        val ms = track.durationMs ?: return false   // не знаем — не судим
        return ms >= LONG_MINUTES * 60_000L
    }

    /** Оставить только то, что похоже на отдельные треки. */
    fun tracksOnly(list: List<Track>): List<Track> = list.filterNot { isSet(it) }
}
