package net.ripster.mobile.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Спуск к родительскому жанру — на случай, когда тега поджанра нет вовсе.
 *
 * Замер 05.09.2026: по `melodic techno` канон приходил пустым, и станция
 * собиралась целиком из выдачи поиска — часовых миксов «Melodic Techno - Best
 * Mix 2025». Спуск к «techno» даёт настоящих артистов, а уточняющее слово
 * продолжает работать дальше, в сверке жанра.
 *
 * Сам отбор по MusicBrainz сетевой и проверялся замером живьём (правило
 * «тег среди трёх главных тегов артиста» описано в GenreCanon.TOP_TAGS);
 * здесь закреплена та часть, что считается без сети.
 */
class GenreCanonTest {

    @Test
    fun aSubgenreFallsBackToItsHead() {
        assertEquals("techno", GenreCanon.parentGenre("melodic techno"))
        assertEquals("techno", GenreCanon.parentGenre("dub techno"))
        assertEquals("house", GenreCanon.parentGenre("deep house"))
        assertEquals("bass", GenreCanon.parentGenre("liquid drum and bass"))
    }

    @Test
    fun aSingleWordGenreHasNowhereToFall() {
        // Ниже спускаться некуда, и притворяться, что есть, нельзя.
        assertNull(GenreCanon.parentGenre("techno"))
        assertNull(GenreCanon.parentGenre("synthwave"))
        assertNull(GenreCanon.parentGenre("  idm  "))
    }

    @Test
    fun emptyInputIsNotAGenre() {
        assertNull(GenreCanon.parentGenre(""))
        assertNull(GenreCanon.parentGenre("   "))
    }

    @Test
    fun extraSpacesDoNotInventAParent() {
        assertEquals("techno", GenreCanon.parentGenre("melodic   techno"))
    }
}
