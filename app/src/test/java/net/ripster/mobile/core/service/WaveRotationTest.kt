package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ротация станции: узнаваемое ядро плюс меняющаяся периферия.
 *
 * Владелец: «в жанре синтвейв есть куча именитых артистов… предлагать то, что в
 * жанре действительно популярно… за основу бери Яндекс волну».
 *
 * Проверяется не «красиво ли», а два свойства, которые легко потерять: сильных
 * должно быть заметно больше слабых, и при этом эфир обязан меняться.
 */
class WaveRotationTest {

    private fun t(id: String, pop: Double?) = Track(
        id = id, title = id, artist = "A", service = Service.DEEZER, popularity = pop,
    )

    private val pool = (1..40).map { t("hit$it", 0.9) } + (1..40).map { t("dim$it", 0.02) }

    @Test
    fun popularTracksDominateButDoNotMonopolise() {
        val out = WaveRotation.pick(pool, seed = 42, take = 20)
        val hits = out.count { it.id.startsWith("hit") }
        assertTrue("сильных должно быть большинство, а вышло $hits из 20", hits >= 13)
        assertTrue("но не все двадцать — иначе это не ротация, а топ-лист", hits <= 19)
    }

    @Test
    fun theSameVisitAlwaysGivesTheSameOrder() {
        """Иначе список дёргался бы на каждой перерисовке экрана."""
        assertEquals(
            WaveRotation.pick(pool, seed = 7, take = 15).map { it.id },
            WaveRotation.pick(pool, seed = 7, take = 15).map { it.id },
        )
    }

    @Test
    fun thenextVisitPlaysSomethingElse() {
        val a = WaveRotation.pick(pool, seed = 1, take = 15).map { it.id }
        val b = WaveRotation.pick(pool, seed = 2, take = 15).map { it.id }
        assertNotEquals(a, b)
    }

    @Test
    fun nothingIsRepeatedWithinOneStation() {
        val out = WaveRotation.pick(pool, seed = 99, take = 30)
        assertEquals(out.size, out.map { it.id }.toSet().size)
    }

    @Test
    fun aServiceWithoutCountersStillGetsOnAir() {
        """У Qobuz и Apple счётчиков нет. Ноль вместо «не знаю» вычеркнул бы их
        из эфира целиком — а это половина фонотеки."""
        val mixed = (1..20).map { t("known$it", 0.8) } + (1..20).map { t("silent$it", null) }
        val out = WaveRotation.pick(mixed, seed = 5, take = 20)
        assertTrue("вещи без счётчика не попали в эфир", out.any { it.id.startsWith("silent") })
    }

    @Test
    fun aShortPoolIsReturnedWhole() {
        val small = (1..5).map { t("t$it", 0.5) }
        assertEquals(5, WaveRotation.pick(small, seed = 3, take = 20).size)
    }

    @Test
    fun anEmptyPoolIsEmpty() {
        assertTrue(WaveRotation.pick(emptyList(), seed = 1, take = 10).isEmpty())
        assertTrue(WaveRotation.pick(pool, seed = 1, take = 0).isEmpty())
    }

    @Test
    fun deezerRankBecomesAShare() {
        assertEquals(0.5, Popularity.fromDeezerRank(500_000)!!, 1e-9)
        assertEquals(1.0, Popularity.fromDeezerRank(5_000_000)!!, 1e-9)
        assertEquals(null, Popularity.fromDeezerRank(null))
        assertEquals("ноль — это не «непопулярно», это отсутствие данных", null, Popularity.fromDeezerRank(0))
    }

    @Test
    fun soundCloudPlaysAreMeasuredOnALogScale() {
        """Между тысячей и десятью тысячами та же разница, что между миллионом
        и десятью: на линейной шкале всё, кроме мировых хитов, было бы нулём."""
        val small = Popularity.fromSoundCloudCounts(1_000, null)!!
        val mid = Popularity.fromSoundCloudCounts(100_000, null)!!
        val huge = Popularity.fromSoundCloudCounts(10_000_000, null)!!
        assertTrue(small < mid && mid < huge)
        assertTrue("верх шкалы не должен уходить за единицу", huge <= 1.0)
        assertTrue("тысяча прослушиваний — это уже не ноль", small > 0.3)
        assertEquals(null, Popularity.fromSoundCloudCounts(null, 10))
    }

    @Test
    fun likesLiftATrackButCannotInventOne() {
        val plain = Popularity.fromSoundCloudCounts(50_000, null)!!
        val loved = Popularity.fromSoundCloudCounts(50_000, 5_000)!!
        assertTrue(loved > plain)
        assertEquals("без прослушиваний одни лайки ничего не значат", null, Popularity.fromSoundCloudCounts(0, 9_999))
    }
}
