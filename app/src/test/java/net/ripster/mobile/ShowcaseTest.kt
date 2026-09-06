
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

/**
 * Одна и та же ВИДИМАЯ подпись — второй раз не показывается.
 *
 * На A31 06.09.2026 в «Недавно добавленном» стояли рядом два «Teardrop»:
 * разные записи, разные строки артистов, потолок соблюдён — а человек видит
 * одно название дважды и читает это как поломку. Считаем то, что он видит.
 */
class ShowcaseTitleTest {

    private data class Row(val id: String, val artist: String, val title: String)

    private fun shelf(rows: List<Row>, take: Int) =
        net.ripster.mobile.ui.screens.Showcase.spread(
            rows, take = take, sameLook = { listOf(it.title) },
        ) { it.artist }

    @org.junit.Test
    fun `the same visible title is not shown twice`() {
        val lib = listOf(
            Row("1", "Massive Attack", "Teardrop"),
            Row("2", "Massive Attack, Trevor", "Teardrop"),
            Row("3", "Portishead", "Sour Times"),
        )
        val got = shelf(lib, 2)
        org.junit.Assert.assertEquals(listOf("Teardrop", "Sour Times"), got.map { it.title })
    }

    @org.junit.Test
    fun `different titles of one artist still both fit`() {
        val lib = listOf(
            Row("1", "Massive Attack", "Teardrop"),
            Row("2", "Massive Attack", "Angel"),
            Row("3", "Portishead", "Sour Times"),
        )
        org.junit.Assert.assertEquals(3, shelf(lib, 3).size)
    }

    @org.junit.Test
    fun `a shelf with nothing else still fills rather than empties`() {
        val lib = (1..4).map { Row("t$it", "X", "Same Song") }
        org.junit.Assert.assertEquals(3, shelf(lib, 3).size)
    }
}

/**
 * Почти-дубли одной песни.
 *
 * Я подходил к этой полке четырежды. Ограничил артиста — рядом встали два
 * «Teardrop». Ограничил подпись — рядом встали «Cyberverse» и «Cyberverse
 * (Over Slowed)» с одной обложкой. Ограничил обложку — те же два «Cyberverse»
 * вернулись уже с РАЗНЫМИ ссылками на картинку.
 *
 * Каждый раз я чинил одну примету и упускал, что человек узнаёт повтор по
 * любой из них. Здесь стережётся последняя и самая важная: та же песня того
 * же автора, чем бы ни отличался её вид.
 */
class ShowcaseNearDuplicateTest {

    private data class Row(val art: String, val title: String, val artist: String)

    private fun look(r: Row): List<String> {
        val base = r.title.substringBefore("(").substringBefore("[").trim().lowercase()
        val who = r.artist.substringBefore(",").substringBefore("&").trim().lowercase()
        return listOf(r.art.lowercase(), r.title.lowercase(), "$base|$who")
    }

    private fun shelf(rows: List<Row>, take: Int) =
        net.ripster.mobile.ui.screens.Showcase.spread(
            rows, take = take, sameLook = { look(it) },
        ) { it.artist }

    @org.junit.Test
    fun `one song in two versions takes one place`() {
        val lib = listOf(
            Row("a.jpg", "Cyberverse", "Navjaxx"),
            Row("b.jpg", "Cyberverse (Over Slowed)", "Navjaxx"),
            Row("c.jpg", "Around the World", "Daft Punk"),
        )
        val got = shelf(lib, 2)
        org.junit.Assert.assertEquals(listOf("Cyberverse", "Around the World"), got.map { it.title })
    }

    @org.junit.Test
    fun `the same song by another artist is a different record`() {
        // Кавер — это не дубль. Сворачивать их вместе значило бы прятать от
        // человека настоящую вещь его фонотеки.
        val lib = listOf(
            Row("a.jpg", "Teardrop", "Massive Attack"),
            Row("b.jpg", "Teardrop", "Das Kabinett, Massive Attack"),
        )
        org.junit.Assert.assertEquals(2, shelf(lib, 2).size)
    }

    @org.junit.Test
    fun `a shared cover is caught even when the words differ`() {
        val lib = listOf(
            Row("same.jpg", "Track One", "A"),
            Row("same.jpg", "Track Two", "B"),
            Row("other.jpg", "Track Three", "C"),
        )
        org.junit.Assert.assertEquals(listOf("Track One", "Track Three"), shelf(lib, 2).map { it.title })
    }
}

/** Между полками сверяются ТЕ ЖЕ приметы, что и внутри полки. */
class ShowcaseSameRuleBothWaysTest {

    private data class Row(val art: String, val title: String, val artist: String)

    private fun look(r: Row) = listOf(r.art.lowercase(), r.title.lowercase())

    @org.junit.Test
    fun `what the neighbour shows is skipped by every facet, not just one`() {
        val lib = listOf(
            Row("a.jpg", "Teardrop", "Massive Attack"),
            Row("z.jpg", "Teardrop", "Massive Attack, Trevor"),   // та же подпись
            Row("c.jpg", "Sour Times", "Portishead"),
        )
        val neighbour = listOf(lib[0])
        val avoid = neighbour.flatMap { look(it) }.toSet()
        val got = net.ripster.mobile.ui.screens.Showcase.spread(
            lib, take = 1, avoid = avoid, sameLook = { look(it) },
        ) { it.artist }
        org.junit.Assert.assertEquals("Sour Times", got.single().title)
    }
}
