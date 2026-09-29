package net.ripster.mobile.core.reactions

import net.ripster.mobile.core.service.StationRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кольцевой буфер реакций и его JSON.
 *
 * Проверяется то, что глазами не починить: потолок, порядок вытеснения и —
 * главное — что битая строка из любой прошлой версии превращается в законное
 * «не знаю», а не в падение и не в половину списка.
 */
class ReactionBufferTest {

    private fun r(artist: String, at: Long, kind: ReactionKind = ReactionKind.LIKE) =
        Reaction(artist = artist, kind = kind, at = at)

    @Test
    fun theBufferKeepsTheNewestThreeHundred() {
        var items: List<Reaction> = emptyList()
        for (i in 1..305) items = Reactions.add(items, r("Артист$i", at = i.toLong()))
        assertEquals(Reactions.CAP, items.size)
        assertEquals("Артист305", items.last().artist)
        assertEquals("первые пять вытеснены", "Артист6", items.first().artist)
    }

    @Test
    fun aRoundTripKeepsEveryField() {
        val items = listOf(
            Reaction("Артист", ReactionKind.EARLY_SKIP, 111L, album = "Диск",
                positionMs = 5_000, durationMs = 240_000),
            Reaction("Другой", ReactionKind.LIKE, 222L, origin = ReactionOrigin.PC),
        )
        assertEquals(items, Reactions.Codec.decode(Reactions.Codec.encode(items)))
    }

    @Test
    fun aBrokenPayloadMeansIDontKnowNotACrash() {
        """Хранилище переживает обновления приложения: мусор, не-массив, пустая
        строка и незнакомый вид реакции должны давать «не знаю», а не падение и
        не молчаливую потерю всего буфера."""
        assertEquals(0, Reactions.Codec.decode(null).size)
        assertEquals(0, Reactions.Codec.decode("   ").size)
        assertEquals(0, Reactions.Codec.decode("это не json").size)
        assertEquals(0, Reactions.Codec.decode("""{"не":"массив"}""").size)
        val mixed = """[{"artist":"Хороший","kind":"LIKE","at":1},""" +
            """{"artist":"Сломанный","kind":"КОГДА-НИБУДЬ","at":2}]"""
        val ok = Reactions.Codec.decode(mixed)
        assertEquals(1, ok.size)
        assertEquals("Хороший", ok.single().artist)
    }

    @Test
    fun anEmptyBufferChangesNothingInTheRanker() {
        assertEquals(0.0, StationRanker.Session.EMPTY.reactionFor("Кто угодно"), 1e-9)
        assertTrue("пустая сессия не должна ничего запрещать",
                   StationRanker.Session.EMPTY.reactionFor("").let { it == 0.0 })
    }
}
