package net.ripster.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полка на главном должна показывать РАЗНОЕ.
 *
 * Жалоба владельца 05.09.2026: «массив аттак захватил нашу коллекцию
 * подчистую». Скачали девятнадцать треков одного альбома — и обе полки
 * заполнились одной обложкой. Формально верно, а выглядит сломанным.
 *
 * Вторая половина правила не менее важная: если в фонотеке ПРАВДА один
 * артист, полка не должна опустеть. Пустая полка хуже однообразной, и
 * подставлять чужих артистов ради разнообразия тоже нельзя.
 */
class ShowcaseTest {

    private data class Row(val id: Int, val artist: String)

    private fun spread(rows: List<Row>, take: Int, cap: Int = Showcase.MAX_PER_ARTIST) =
        Showcase.spread(rows, take, cap) { it.artist }

    @Test
    fun oneArtistDoesNotTakeTheWholeShelf() {
        val rows = (1..19).map { Row(it, "Massive Attack") } +
            listOf(Row(20, "Portishead"), Row(21, "Tricky"), Row(22, "Burial"))
        val out = spread(rows, take = 5)
        assertEquals(2, out.count { it.artist == "Massive Attack" })
        assertEquals(5, out.size)
    }

    @Test
    fun theShelfStaysFullEvenWhenVarietyRunsOut() {
        """Просим шесть, а разных артистов под потолком набирается только пять.
        Шестую позицию добираем отложенным — иначе на полке появится дыра из-за
        формальности. Это осознанная уступка: полнота полки важнее строгости
        потолка, и цена ей — один лишний трек того же артиста."""
        val rows = (1..19).map { Row(it, "Massive Attack") } +
            listOf(Row(20, "Portishead"), Row(21, "Tricky"), Row(22, "Burial"))
        val out = spread(rows, take = 6)
        assertEquals(6, out.size)
        assertEquals(3, out.count { it.artist == "Massive Attack" })
    }

    @Test
    fun theCallersOrderIsKept() {
        // У «недавнего» порядок — это свежесть, у «коллекции» — случайная
        // выборка на заход. Витрина не вправе их пересортировывать.
        val rows = listOf(Row(1, "A"), Row(2, "B"), Row(3, "A"), Row(4, "C"))
        assertEquals(listOf(1, 2, 3, 4), spread(rows, take = 4).map { it.id })
    }

    @Test
    fun aNarrowLibraryStillFillsTheShelf() {
        // Один артист на всю фонотеку — это не повод показать пустоту.
        val rows = (1..8).map { Row(it, "Massive Attack") }
        val out = spread(rows, take = 6)
        assertEquals(6, out.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), out.map { it.id })
    }

    @Test
    fun theCapLeadsAndTheRemainderFollows() {
        """Сначала берём по потолку с каждого, и только потом добираем
        отложенным — иначе разнообразия не будет вовсе."""
        val rows = (1..5).map { Row(it, "A") } + (6..7).map { Row(it, "B") }
        val out = spread(rows, take = 5)
        assertEquals(listOf(1, 2, 6, 7, 3), out.map { it.id })
    }

    @Test
    fun featuringsCountAsTheSameArtist() {
        // «Massive Attack» и «Massive Attack, Trevor Jackson» — один и тот же
        // человек на полке, и показывать их как двух разных значит вернуть
        // ровно ту картинку, из-за которой всё затевалось.
        val rows = listOf(
            Row(1, "Massive Attack"),
            Row(2, "Massive Attack, Trevor Jackson"),
            Row(3, "Massive Attack feat. Elizabeth Fraser"),
            Row(4, "Portishead"),
        )
        val out = spread(rows, take = 3)
        assertTrue(out.any { it.artist == "Portishead" })
    }

    @Test
    fun emptyInputIsEmptyOutput() {
        assertTrue(spread(emptyList(), take = 5).isEmpty())
        assertTrue(spread(listOf(Row(1, "A")), take = 0).isEmpty())
    }

    @Test
    fun neverMoreThanAsked() {
        val rows = (1..40).map { Row(it, "art$it") }
        assertEquals(12, spread(rows, take = 12).size)
    }
}
