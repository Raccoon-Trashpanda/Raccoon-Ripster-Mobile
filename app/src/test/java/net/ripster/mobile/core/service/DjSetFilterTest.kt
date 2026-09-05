package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сет — не трек, а «Extended Mix» — трек.
 *
 * Живой разбор 05.09.2026: станция «Melodic Techno» состояла целиком из
 * часовых миксов, которые честно прошли сверку жанра. Здесь закрепляется
 * граница, которую очень легко перейти: отсев по голому слову «mix» выкосил бы
 * половину настоящего техно, где «Extended Mix» и «Original Mix» — обычные
 * названия треков.
 */
class DjSetFilterTest {

    private fun t(title: String, ms: Long? = null) =
        Track(id = title, title = title, artist = "A", service = Service.DEEZER, durationMs = ms)

    @Test
    fun realCompilationsFromTheLiveCaseAreCaught() {
        listOf(
            "Melodic Techno - Best Mix 2025 Miss Monique, Korolova",
            "LIVE @SUNSET HILL (Melodic Techno Set)",
            "Kopf & Hörer @ Drinnen tanzt der Bär Schwerin - Live Melodic Techno",
            "Melodic Techno Mix 2026 🔥 Best Melodic Techno DJ Set",
            "Cyro Chamber: 1 Hour Melodic Techno",
            "Arabic Melodic Techno Live Set With Electric Guitar",
        ).forEach { assertTrue(it, DjSetFilter.looksLikeSetByTitle(it)) }
    }

    @Test
    fun ordinaryClubTitlesSurvive() {
        // Ради этого «mix» и не попал в список маркеров.
        listOf(
            "Come On People (Extended Mix)",
            "Belfast (Original Mix)",
            "Skyscrapers (Club Mix)",
            "Born Slippy (Nuxx) (Radio Edit)",
            "Natural Blues (Radio Mix)",
            "Hunting (Ben Böhmer Remix)",
        ).forEach { assertFalse(it, DjSetFilter.looksLikeSetByTitle(it)) }
    }

    @Test
    fun aLongItemIsASetEvenWithAPlainTitle() {
        assertTrue(DjSetFilter.isSet(t("Untitled", 62 * 60_000L)))
    }

    @Test
    fun aNormalTrackLengthPasses() {
        assertFalse(DjSetFilter.isSet(t("Belfast", 9 * 60_000L)))
    }

    @Test
    fun anUnknownDurationIsNotJudged() {
        // «Не знаю» — это не «час». Половина сервисов длительность не отдаёт,
        // и по ней их выкидывать нельзя.
        assertFalse(DjSetFilter.isSet(t("Belfast", null)))
    }

    @Test
    fun theBoundaryIsWhereItSays() {
        assertFalse(DjSetFilter.isSet(t("x", (DjSetFilter.LONG_MINUTES - 1) * 60_000L)))
        assertTrue(DjSetFilter.isSet(t("x", DjSetFilter.LONG_MINUTES * 60_000L)))
    }

    @Test
    fun filteringKeepsOrderAndDropsOnlySets() {
        val list = listOf(
            t("Belfast (Original Mix)", 8 * 60_000L),
            t("Melodic Techno Mix 2026", 70 * 60_000L),
            t("Skyscrapers", 6 * 60_000L),
        )
        val out = DjSetFilter.tracksOnly(list)
        assertEquals(listOf("Belfast (Original Mix)", "Skyscrapers"), out.map { it.title })
    }
}
