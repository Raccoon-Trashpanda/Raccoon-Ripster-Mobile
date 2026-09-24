package net.ripster.mobile.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Глубокий состав исполнителей: чистая модель и разбор кредитов.
 *
 * Жалоба владельца 13.09.2026: «вижу 1 артиста, а их несколько». Совместные
 * вещи сжимались до одного имени, потому что строка артиста — ОДНА, и каждый
 * сервис пишет в неё то, что у него есть. Здесь проверяется ровно то, что
 * нельзя проверить на живом поиске: правило вывода «A, B feat. C», отбор ролей
 * и — главное — границы догадки: чего разбор НЕ должен касаться никогда.
 */
class ContributorsTest {

    // ---- правило строки ----

    @Test
    fun displayJoinsMainAndFeaturedIntoOneLine() {
        val c = Contributors(main = listOf("Fred again..", "Bicep"), featured = listOf("Clara La San"))
        assertEquals("Fred again.., Bicep feat. Clara La San", c.display())
    }

    @Test
    fun remixerStandsWithTheGuests() {
        val c = Contributors(main = listOf("Dua Lipa"), remixers = listOf("The Blessed Madonna"))
        assertEquals("Dua Lipa feat. The Blessed Madonna", c.display())
    }

    @Test
    fun repeatedNamesAreDroppedIgnoringCaseAndAccents() {
        // «Röyksopp» и "royksopp" — одни и те же буквы: «A feat. A» человек не заслужил.
        val c = Contributors(main = listOf("Röyksopp"), featured = listOf("royksopp ", "Varg"))
        assertEquals("Röyksopp feat. Varg", c.display())
    }

    @Test
    fun withoutMainGuestsBecomeTheHeadline() {
        val c = Contributors(featured = listOf("Charli XCX"), remixers = listOf("A. G. Cook"))
        assertEquals("Charli XCX, A. G. Cook", c.display())
    }

    @Test
    fun emptyModelGivesAnEmptyLine() {
        assertEquals("", Contributors.EMPTY.display())
        assertTrue(Contributors.EMPTY.isEmpty)
    }

    @Test
    fun oneNameIsNotACollaboration() {
        val c = Contributors(main = listOf("Moderat"))
        assertTrue(c.isSingle)
        assertFalse(c.richerThan("Moderat"))
    }

    @Test
    fun richerMeansMoreNamesNotALongerString() {
        // «Chloe x Halle» длиннее «Chloe», но артистов там двое против одного —
        // сверка идёт по составу, а не по длине.
        assertTrue(Contributors(main = listOf("Chloe", "Halle")).richerThan("Chloe"))
        assertFalse(Contributors(main = listOf("Chloe")).richerThan("Chloe x Halle"))
        assertFalse(Contributors.EMPTY.richerThan("Bicep"))
    }

    // ---- Deezer: pairs «имя — роль» ----

    @Test
    fun deezerRolesSplitMainFromFeatured() {
        val c = Contributors.fromRoles(
            listOf("Fred again.." to "Main", "Baby Keem" to "Featured", "Some Writer" to "Writer"),
            fallbackMain = "Fred again..",
        )
        assertEquals("Fred again.. feat. Baby Keem", c.display())
    }

    @Test
    fun unknownRolesAreNotInventedIntoArtists() {
        val c = Contributors.fromRoles(
            listOf("Rick Rubin" to "Producer", "Adele" to "MainArtist"),
            fallbackMain = "Adele",
        )
        assertEquals("Adele", c.display())
        assertTrue(c.isSingle)
    }

    @Test
    fun roleWithoutAnyNameFallsBackToTheSingleArtist() {
        val c = Contributors.fromRoles(emptyList(), fallbackMain = "Bicep")
        assertEquals("Bicep", c.display())
    }

    @Test
    fun classicalLineUpDoesNotFloodTheArtistLine() {
        // Оркестр с хором и солистами — это не «исполнитель» строки: состав из
        // десятка имён отбрасывается целиком, остаётся то, что назвал сервис.
        val many = (1..9).map { "Instrumentalist $it" to "MainArtist" }
        val c = Contributors.fromRoles(many, fallbackMain = "Herbert von Karajan")
        assertEquals("Herbert von Karajan", c.display())
    }

    // ---- Qobuz: строка performers ----

    @Test
    fun qobuzPerformersStringIsParsedByRoles() {
        val c = Contributors.fromQobuzPerformers(
            "Daft Punk, MainArtist - Julia Holter, FeaturedArtist",
            fallbackMain = "Daft Punk",
        )
        assertEquals("Daft Punk feat. Julia Holter", c.display())
    }

    @Test
    fun qobuzSeveralRolesAndHyphenatedNamesSurvive() {
        // «E-Type» — одно имя с дефисом: блоки делятся пробел-дефис-пробелом,
        // поэтому резать его нельзя. Ролей на человека бывает несколько.
        val c = Contributors.fromQobuzPerformers(
            "Linda Ronstadt, FeaturedArtist, Vocal - E-Type, MainArtist - Dr. Alban, MainArtist",
            fallbackMain = "E-Type",
        )
        assertEquals("E-Type, Dr. Alban feat. Linda Ronstadt", c.display())
    }

    @Test
    fun qobuzClassicalCreditsStayOffTheArtistLine() {
        val c = Contributors.fromQobuzPerformers(
            "Ludwig van Beethoven, Composer - Vienna Philharmonic Orchestra, Orchestra - Georg Solti, Conductor",
            fallbackMain = "Georg Solti",
        )
        assertEquals("Georg Solti", c.display())
        assertTrue(c.isSingle)
    }

    @Test
    fun qobuzRemixerRoleGoesToTheGuests() {
        val c = Contributors.fromQobuzPerformers(
            "Moby, MainArtist - Richard X, Remixer",
            fallbackMain = "Moby",
        )
        assertEquals("Moby feat. Richard X", c.display())
    }

    @Test
    fun missingPerformersStringKeepsTheSingleName() {
        assertEquals("Bicep", Contributors.fromQobuzPerformers(null, "Bicep").display())
        assertEquals("Bicep", Contributors.fromQobuzPerformers("   ", "Bicep").display())
    }

    // ---- SoundCloud: только заголовок ----

    @Test
    fun soundCloudFeatInBracketsBecomesAGuest() {
        val c = Contributors.fromScNames("Zedd", "Clarity (feat. Foxes)")
        assertEquals("Zedd feat. Foxes", c.display())
    }

    @Test
    fun soundCloudRemixNameBecomesAGuest() {
        val c = Contributors.fromScNames("Dua Lipa", "Levitating (The Blessed Madonna Remix)")
        assertEquals("Dua Lipa feat. The Blessed Madonna", c.display())
    }

    @Test
    fun soundCloudXCollaborationInTheCreditPrefixIsTwoNames() {
        val c = Contributors.fromScNames("Tiësto", "Tiësto x Charli XCX - The Business")
        assertEquals("Tiësto, Charli XCX", c.display())
    }

    @Test
    fun danglingXDoesNotInventASecondArtist() {
        // «Tiësto x» на конце — мусор вёрстки, а не стык: после «x» буквы нет.
        val c = Contributors.fromScNames("Tiësto", "Tiësto x - Adagio")
        assertEquals("Tiësto", c.display())
        assertTrue(c.isSingle)
    }

    @Test
    fun bandNamesWithAmpersandStayOneName() {
        val c = Contributors.fromScNames("Simon & Garfunkel", "Simon & Garfunkel - Bridge Over Troubled Water")
        assertEquals("Simon & Garfunkel", c.display())
        assertTrue("дуэт распался на двоих: " + c.display(), c.isSingle)
    }

    @Test
    fun ampersandInsideASongTitleIsNotACollaboration() {
        // Делим ТОЛЬКО префикс до « - »: в «Rhythm & Blues» стыка исполнителей нет.
        val c = Contributors.fromScNames("Mabel", "Mabel - I Feel Like Rhythm & Blues")
        assertEquals("Mabel", c.display())
        assertTrue(c.isSingle)
    }

    @Test
    fun alreadyJoinedArtistStringIsNotReSplit() {
        // `publisher_metadata.artist` у SoundCloud уже записан нашим же
        // «, »-форматом: резать его ещё раз — значит получить «A feat. B» там,
        // где сервис сказал «A, B».
        val c = Contributors.fromScNames("Art Department, Lane 8", "Alternate Reality")
        assertEquals("Art Department, Lane 8", c.display())
    }

    @Test
    fun bracketNoiseIsNotAPerson() {
        for (title in listOf("Sunflower (Original Mix)", "Sunflower (Remix)", "Sunflower (Extended Edit)")) {
            val c = Contributors.fromScNames("Swæde", title)
            assertTrue("из «$title» выдумали состав: ${c.display()}", c.isSingle)
        }
    }

    @Test
    fun plainSoundCloudTitleStaysSingle() {
        val c = Contributors.fromScNames("Fred again..", "Delilah (pull me out of this)")
        assertEquals("Fred again..", c.display())
        assertTrue(c.isSingle)
    }

    @Test
    fun withoutAnyCreditNameTheBaseArtistSurvives() {
        val c = Contributors.fromScNames("", "Just A Song")
        assertEquals("", c.display())
    }
}
