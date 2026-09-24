package net.ripster.mobile.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один жанр — один ключ, чем бы его ни назвал сервис.
 *
 * Все строки ниже ВЗЯТЫ ИЗ ЖИВЫХ СЛОВАРЕЙ (замер 05.09.2026): 22 у Deezer,
 * 22 у Apple, 190 у Яндекса и 161 слаг ротора. Ничего не придумано — в том
 * числе болгарские названия Deezer, которые пришли по гео-локали и стали
 * главным доводом за существование этого модуля.
 */
class GenreKeyTest {

    private fun same(vararg raw: String) {
        val keys = raw.map { GenreKey.of(it) }
        assertTrue("не узнано: " + raw.zip(keys).filter { it.second == null }.map { it.first },
                   keys.all { it != null })
        assertEquals("разные сервисы дали разные ключи: ${raw.toList()} → $keys",
                     1, keys.toSet().size)
    }

    // ── ради чего всё затевалось ───────────────────────────────────────────

    @Test
    fun theSameGenreFromFourServicesGivesOneKey() {
        same("Rock", "rock", "Hard Rock", "allrock")
        same("Metal", "metal", "Alternative metal", "classicmetal")
        same("Classical", "classical", "Contemporary Classical Music")
        same("Jazz", "Contemporary Jazz", "Vocal jazz", "smoothjazz")
    }

    @Test
    fun localisedNamesAreNotInTheSeedOnPurpose() {
        """Замер нашёл болгарский словарь Deezer целиком, и соблазн вписать его
        сюда был первым же решением — неправильным. Вписывать чужие словари
        руками значит носить в себе то, что можно выучить: завтра придёт
        турецкий, и таблица снова отстанет.

        Поэтому посев латинский, а локализованные ярлыки выучивает
        GenreLearner из того, что и так проходит через приложение. Здесь
        закреплено именно это: посев их НЕ знает, и это не пробел, а решение."""
        assertNull(GenreKey.of("Ритъм енд блус"))
        assertNull(GenreKey.of("Реге"))
        assertNull(GenreKey.of("Латино музика"))
    }

    @Test
    fun compoundNamesTakeTheLeadingPart() {
        """«Rap/Hip Hop» и «Soul & Funk» — порядок в имени сервиса не
        случаен, первым стоит главное."""
        assertEquals(GenreKey.RAP, GenreKey.of("Rap/Hip Hop"))
        assertEquals(GenreKey.RAP, GenreKey.of("Hip-Hop/Rap"))
        assertEquals(GenreKey.RNB, GenreKey.of("R&B/Soul"))
        assertEquals(GenreKey.SOUL, GenreKey.of("Soul & Funk"))
    }

    @Test
    fun rotorSlugsLoseTheirServiceTail() {
        """Слаги ротора несут хвост «genre»: без его снятия половина станций
        Яндекса не узнаётся."""
        assertEquals(GenreKey.TRIPHOP, GenreKey.of("triphopgenre"))
        assertEquals(GenreKey.IDM, GenreKey.of("idmgenre"))
        assertEquals(GenreKey.DUBSTEP, GenreKey.of("bassgenre"))
        assertEquals(GenreKey.BREAKBEAT, GenreKey.of("breakbeatgenre"))
    }

    @Test
    fun aQualifierDoesNotChangeTheGenre() {
        assertEquals(GenreKey.METAL, GenreKey.of("Black metal"))
        assertEquals(GenreKey.METAL, GenreKey.of("Doom metal"))
        assertEquals(GenreKey.JAZZ, GenreKey.of("Vocal jazz"))
        assertEquals(GenreKey.JAZZ, GenreKey.of("Smooth jazz"))
        assertEquals(GenreKey.HOUSE, GenreKey.of("Deep House"))
        assertEquals(GenreKey.TECHNO, GenreKey.of("Minimal techno"))
    }

    // ── «не знаю» остаётся «не знаю» ───────────────────────────────────────

    @Test
    fun nonMusicIsNotAGenre() {
        """В словаре Яндекса рядом с музыкой лежат аудиокниги и подкасты.
        Назвать их жанром — неправда, а не мелочь: они попадут в станцию."""
        listOf("Business", "Audio Fairy Tales", "Biographies and memoirs",
               "Books for kids and teenagers", "Children's Poems").forEach {
            assertNull(it, GenreKey.of(it))
        }
    }

    @Test
    fun unknownStaysUnknown() {
        assertNull(GenreKey.of(null))
        assertNull(GenreKey.of(""))
        assertNull(GenreKey.of("   "))
        assertNull(GenreKey.of("совершенно новый жанр 2031"))
    }

    @Test
    fun weNeverGuessANeighbour() {
        """Подставить похожий жанр — ровно та подмена, из-за которой синтвейв
        играл ликвид-фанк. Лучше не знать."""
        assertNull(GenreKey.of("Shanson"))
        assertNull(GenreKey.of("Tarab"))
    }

    // ── охват живых словарей ───────────────────────────────────────────────

    @Test
    fun theMeasuredServiceVocabulariesAreCovered() {
        """Гоняем ЗАМЕР целиком, а не подобранные примеры: 22 строки Deezer,
        22 Apple, 190 Яндекса и 161 слаг ротора — ровно то, что сервисы отдали
        05.09.2026 (см. GenreVocabularies).

        Порог не «сколько получилось», а сколько нужно, чтобы жанровый слой
        имел смысл. Английские словари Deezer и Apple обязаны покрываться почти
        целиком — на них строятся станции. У Яндекса 190 названий вперемешку с
        аудиокнигами, поэтому порог там ниже и это честно."""
        fun cover(list: List<String>) = list.count { GenreKey.of(it) != null } * 100 / list.size

        val deezer = cover(GenreVocabularies.deezer)
        val apple = cover(GenreVocabularies.apple)
        val rotor = cover(GenreVocabularies.yandexRotor)
        val ya = cover(GenreVocabularies.yandex)

        println("ОХВАТ deezer=$deezer apple=$apple rotor=$rotor yandex=$ya")
        println("НЕ УЗНАНО deezer: " + GenreVocabularies.deezer.filter { GenreKey.of(it) == null })
        println("НЕ УЗНАНО apple: " + GenreVocabularies.apple.filter { GenreKey.of(it) == null })
        assertTrue("Deezer покрыт на $deezer%: " +
            GenreVocabularies.deezer.filter { GenreKey.of(it) == null }, deezer >= 90)
        assertTrue("Apple покрыт на $apple%: " +
            GenreVocabularies.apple.filter { GenreKey.of(it) == null }, apple >= 90)
        assertTrue("ротор покрыт на $rotor%", rotor >= 65)
        assertTrue("Яндекс покрыт на $ya%", ya >= 50)
    }

    // ── противоречие ≠ соседство ───────────────────────────────────────────

    @Test
    fun aNeighbourGenreContradictsTheStation() {
        """Собственно жалоба 13.09.2026: в брейкбите заиграло хаус-соседство и
        французский рэп. Соседний жанр — это НЕ «не знаю», это другой жанр."""
        assertTrue(GenreKey.contradicts(GenreKey.HOUSE, GenreKey.BREAKBEAT))
        assertTrue(GenreKey.contradicts(GenreKey.RAP, GenreKey.BREAKBEAT))
        assertTrue(GenreKey.contradicts(GenreKey.TECHNO, GenreKey.HOUSE))
        assertTrue(GenreKey.contradicts(GenreKey.CLASSICAL, GenreKey.AMBIENT))
    }

    @Test
    fun aParentTagOverTheSameGenreIsNotAContradiction() {
        """Apple в редакторской подборке ставит «Electronic» поверх любого
        электронного жанра. Считать это противоречием — значит выбросить из
        станции весь Apple (наполнение падает), а считать подтверждением —
        налить в брейкбит любую электронику. Это третий ответ:
        «ручается наполовину»."""
        for (child in listOf(
            GenreKey.TECHNO, GenreKey.HOUSE, GenreKey.BREAKBEAT, GenreKey.DNB,
            GenreKey.AMBIENT, GenreKey.IDM,
        )) {
            assertFalse("зонтик над $child", GenreKey.contradicts(GenreKey.ELECTRONIC, child))
            assertFalse("то же самое наоборот", GenreKey.contradicts(child, GenreKey.ELECTRONIC))
        }
    }

    @Test
    fun theSameKeyAndAnUnknownOneNeverContradict() {
        """Одинаковый ключ — не противоречие; то, чего мы не узнали, — тоже.
        Молчание сервиса не может быть доказательством чужого жанра."""
        assertFalse(GenreKey.contradicts(GenreKey.TECHNO, GenreKey.TECHNO))
        assertFalse(GenreKey.contradicts(null, GenreKey.TECHNO))
        assertFalse(GenreKey.contradicts(GenreKey.TECHNO, null))
    }
}
