
/**
 * Полки должны знать друг о друге.
 *
 * Потолок на артиста действовал ВНУТРИ полки, а между полками не действовало
 * ничего. На снимке 06.09.2026 «Sour Times» и «Teardrop» стояли сразу в двух
 * разделах главной — «Из вашей коллекции» и «Недавно добавленное». Формально
 * обе полки правы, но вместе они показывают одно и то же: две полки, а вещей
 * как на одной.
 */
class ShowcaseNeighbourTest {

    private data class Row(val id: String, val artist: String)

    private fun shelf(rows: List<Row>, take: Int, avoid: Set<String> = emptySet()) =
        net.ripster.mobile.ui.screens.Showcase.spread(
            rows, take = take, avoid = avoid, keyOf = { it.id },
        ) { it.artist }

    @org.junit.Test
    fun `a rich library never repeats the neighbour shelf`() {
        val lib = (1..20).map { Row("t$it", "artist${it % 7}") }
        val recent = shelf(lib, 6)
        val collection = shelf(lib.reversed(), 6, avoid = recent.map { it.id }.toSet())
        val overlap = recent.map { it.id }.toSet() intersect collection.map { it.id }.toSet()
        org.junit.Assert.assertTrue("полки пересеклись: $overlap", overlap.isEmpty())
    }

    @org.junit.Test
    fun `a narrow library gets a full shelf rather than an empty one`() {
        // Три вещи всего. Обойти соседку нечем — и полка всё равно должна
        // быть полной: пустое место хуже повтора, а выдумывать чужих
        // артистов нельзя.
        val lib = listOf(Row("a", "X"), Row("b", "X"), Row("c", "Y"))
        val recent = shelf(lib, 3)
        val collection = shelf(lib, 3, avoid = recent.map { it.id }.toSet())
        org.junit.Assert.assertEquals(3, collection.size)
    }

    @org.junit.Test
    fun `what the neighbour does not show comes first`() {
        // Пять вещей, соседка заняла две. Полка на три места обязана начать
        // с трёх свободных, а не с занятых.
        val lib = (1..5).map { Row("t$it", "a$it") }
        val taken = setOf("t1", "t2")
        val got = shelf(lib, 3, avoid = taken)
        org.junit.Assert.assertTrue("взяла занятое: ${got.map { it.id }}",
            got.none { it.id in taken })
    }

    @org.junit.Test
    fun `without a neighbour nothing changes`() {
        val lib = (1..8).map { Row("t$it", "a${it % 3}") }
        org.junit.Assert.assertEquals(shelf(lib, 5), shelf(lib, 5, avoid = emptySet()))
    }
}

/**
 * Потолок считает ВСЕХ указанных, а не первого.
 *
 * Снимок 06.09.2026: полка «Из вашей коллекции» целиком в Massive Attack при
 * формально соблюдённом потолке — потому что «Das Kabinett, Massive Attack»
 * считался посторонним артистом. Полка врала не человеку, а сама себе: она
 * считала то, чего он не видит.
 */
class ShowcaseCreditsTest {

    private data class Row(val id: String, val artist: String)

    private fun shelf(rows: List<Row>, take: Int) =
        net.ripster.mobile.ui.screens.Showcase.spread(rows, take = take) { it.artist }

    @org.junit.Test
    fun `a co-credit does not slip past the cap`() {
        val lib = listOf(
            Row("1", "Massive Attack"),
            Row("2", "Massive Attack"),
            Row("3", "Das Kabinett, Massive Attack"),
            Row("4", "Massive Attack, Elizabeth Fraser"),
            Row("5", "Portishead"),
        )
        val got = shelf(lib, 3)
        val ma = got.count { it.artist.lowercase().contains("massive attack") }
        org.junit.Assert.assertTrue("Massive Attack занял $ma мест из 3", ma <= 2)
        org.junit.Assert.assertTrue("Portishead не попал: ${got.map { it.id }}",
            got.any { it.id == "5" })
    }

    @org.junit.Test
    fun `an ampersand credit counts too`() {
        val lib = listOf(Row("1", "Daft Punk"), Row("2", "Daft Punk"),
                         Row("3", "Pharrell & Daft Punk"), Row("4", "Justice"))
        val got = shelf(lib, 3)
        org.junit.Assert.assertTrue(got.any { it.id == "4" })
    }

    @org.junit.Test
    fun `a narrow library still fills the shelf`() {
        // Один артист на всю фонотеку — полка обязана остаться полной.
        val lib = (1..5).map { Row("t$it", "Massive Attack") }
        org.junit.Assert.assertEquals(4, shelf(lib, 4).size)
    }
}
