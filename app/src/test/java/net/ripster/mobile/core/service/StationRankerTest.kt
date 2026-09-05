package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один слой оценки поверх любого источника.
 *
 * Проверяется не «красиво ли», а свойства, которые легко потерять при первой
 * же правке весов: «не знаю» не должно превращаться в «нет», один артист не
 * должен занимать эфир, вкус слушателя обязан влиять, а эфир — меняться от
 * захода к заходу и повторяться внутри одного.
 */
class StationRankerTest {

    private fun t(id: String, artist: String = "A", pop: Double? = null, isrc: String? = null) =
        Track(id = id, title = id, artist = artist, service = Service.DEEZER,
              popularity = pop, isrc = isrc)

    private val S = StationRanker.Signals()

    // ── веса ────────────────────────────────────────────────────────────────

    @Test
    fun anUnknownPopularityIsNotAZero() {
        """Ни Qobuz, ни Apple не отдают счётчик прослушиваний. Если молчание
           сервиса весит ноль, эти сервисы исчезают из эфира целиком."""
        val silent = StationRanker.weight(t("x"), S, StationRanker.Taste.EMPTY, 2026)
        val zero = StationRanker.weight(t("x"), S.copy(popularity = 0.0), StationRanker.Taste.EMPTY, 2026)
        assertTrue("«не знаю» должно весить больше явного нуля", silent > zero)
    }

    @Test
    fun anUnknownYearIsNeutral() {
        val noYear = StationRanker.weight(t("x"), S, StationRanker.Taste.EMPTY, 2026)
        val midAge = StationRanker.weight(t("x"), S.copy(year = 2018), StationRanker.Taste.EMPTY, 2026)
        assertEquals(midAge, noYear, 1e-9)
    }

    @Test
    fun aVettedSourceOutweighsSearchOutput() {
        val canon = StationRanker.weight(t("x"), S.copy(vetted = true), StationRanker.Taste.EMPTY, 2026)
        val found = StationRanker.weight(t("x"), S, StationRanker.Taste.EMPTY, 2026)
        assertTrue(canon > found)
    }

    @Test
    fun chartingLiftsButDoesNotSkipTheQueue() {
        """Прибавка, а не пропуск вперёд: вещь из чарта с нулевой популярностью
           не должна перевешивать всё подряд в разы больше, чем на её вес."""
        val plain = StationRanker.weight(t("x"), S.copy(vetted = true), StationRanker.Taste.EMPTY, 2026)
        val hot = StationRanker.weight(t("x"), S.copy(vetted = true, charting = true), StationRanker.Taste.EMPTY, 2026)
        assertTrue(hot > plain)
        assertTrue("прибавка не должна быть кратной десяткам", hot < plain * 2.5)
    }

    @Test
    fun theListenersOwnArtistWeighsMore() {
        val taste = StationRanker.Taste.of(listOf("Timecop1983", "Timecop1983", "Timecop1983"))
        val known = StationRanker.weight(t("x", "Timecop1983"), S, taste, 2026)
        val other = StationRanker.weight(t("x", "Кто-то"), S, taste, 2026)
        assertTrue(known > other)
    }

    @Test
    fun tasteSaturates() {
        """Десятое прослушивание значит меньше второго — иначе один
           зацикленный артист забил бы станцию целиком."""
        val few = StationRanker.Taste.of(List(2) { "A" })
        val many = StationRanker.Taste.of(List(200) { "A" })
        val w2 = StationRanker.weight(t("x", "A"), S, few, 2026)
        val w200 = StationRanker.weight(t("x", "A"), S, many, 2026)
        assertTrue(w200 > w2)
        assertTrue("прибавка обязана иметь потолок", w200 < w2 * 1.5)
    }

    @Test
    fun anOldTrackIsNudgedNotBuried() {
        val old = StationRanker.weight(t("x"), S.copy(year = 1998), StationRanker.Taste.EMPTY, 2026)
        val fresh = StationRanker.weight(t("x"), S.copy(year = 2026), StationRanker.Taste.EMPTY, 2026)
        assertTrue(fresh > old)
        assertTrue("классика жанра не должна выпадать из эфира", old > fresh * 0.7)
    }

    @Test
    fun artistNormalisationIgnoresFeaturesAndExtraNames() {
        assertEquals(StationRanker.normArtist("Burial, Four Tet"), StationRanker.normArtist("Burial"))
        assertEquals(StationRanker.normArtist("Burial feat. Someone"), StationRanker.normArtist("burial"))
    }

    @Test
    fun pcTasteIsASeparateSignalFromLocalPlays() {
        """Вкус с ПК (Раскопки + подписки) и местные прослушивания — разные
        величины в разных шкалах. Сложить их в одно поле значило бы выдать
        пересчёт за измерение, поэтому они складываются как два признака."""
        val local = StationRanker.Taste.of(List(5) { "A" })
        val fromPc = StationRanker.Taste.fromWeights(mapOf("B" to 88.0))
        val both = local.plus(fromPc)
        val wa = StationRanker.weight(t("x", "A"), S, both, 2026)
        val wb = StationRanker.weight(t("x", "B"), S, both, 2026)
        val wc = StationRanker.weight(t("x", "C"), S, both, 2026)
        assertTrue("местный любимец весит больше незнакомца", wa > wc)
        assertTrue("любимец с ПК тоже весит больше незнакомца", wb > wc)
    }

    @Test
    fun pcWeightsAreNormalisedByTheTopOne() {
        """Абсолютные числа Раскопок на телефоне ничего не значат — значимо
        отношение. Замер 05.09.2026: от 3 у подписки до 88 у самого
        слушаемого."""
        val t1 = StationRanker.Taste.fromWeights(mapOf("верх" to 88.0, "низ" to 3.0))
        assertEquals(1.0, t1.affinityOf("верх"), 1e-9)
        assertTrue(t1.affinityOf("низ") < 0.1)
        // Та же расстановка в других единицах даёт тот же результат.
        val t2 = StationRanker.Taste.fromWeights(mapOf("верх" to 880.0, "низ" to 30.0))
        assertEquals(t1.affinityOf("низ"), t2.affinityOf("низ"), 1e-9)
    }

    @Test
    fun anEmptyPcProfileChangesNothing() {
        """ПК не в сети — станция обязана строиться как раньше, а не хуже."""
        val empty = StationRanker.Taste.fromWeights(emptyMap())
        assertEquals(StationRanker.Taste.EMPTY, empty)
        assertEquals(
            StationRanker.weight(t("x"), S, StationRanker.Taste.EMPTY, 2026),
            StationRanker.weight(t("x"), S, empty, 2026), 1e-9,
        )
    }

    // ── отбор ───────────────────────────────────────────────────────────────

    private val pool = buildList {
        repeat(12) { add(t("hit$it", "Известный$it", pop = 0.9) to StationRanker.Signals(vetted = true, popularity = 0.9)) }
        repeat(30) { add(t("dim$it", "Ноунейм$it", pop = 0.02) to StationRanker.Signals(popularity = 0.02)) }
    }

    @Test
    fun strongCandidatesDominateWithoutMonopolising() {
        val out = StationRanker.rank(pool, seed = 42, size = 20)
        val hits = out.count { it.id.startsWith("hit") }
        assertTrue("сильных должно быть заметно больше: было $hits", hits >= 8)
        assertTrue("но не только они: было $hits", hits < 20)
    }

    @Test
    fun theSameSeedGivesTheSameAir() {
        assertEquals(
            StationRanker.rank(pool, seed = 7, size = 15).map { it.id },
            StationRanker.rank(pool, seed = 7, size = 15).map { it.id },
        )
    }

    @Test
    fun aNewSeedGivesADifferentAir() {
        assertNotEquals(
            StationRanker.rank(pool, seed = 1, size = 15).map { it.id },
            StationRanker.rank(pool, seed = 2, size = 15).map { it.id },
        )
    }

    @Test
    fun oneArtistDoesNotTakeOverTheStation() {
        val hogging = (1..40).map { t("t$it", "Один и тот же") to StationRanker.Signals(vetted = true) }
        val out = StationRanker.rank(hogging, seed = 3, size = 20, maxPerArtist = 2)
        assertEquals("больше двух вещей одного артиста в эфир не пускаем", 2, out.size)
    }

    @Test
    fun theSameArtistIsNotPlayedBackToBack() {
        val mixed = (1..10).flatMap { a ->
            (1..3).map { n -> t("a$a-$n", "Артист$a") to StationRanker.Signals(vetted = true) }
        }
        val out = StationRanker.rank(mixed, seed = 11, size = 20, maxPerArtist = 2, artistGap = 2)
        val artists = out.map { it.artist }
        artists.windowed(2).forEach { (x, y) ->
            assertNotEquals("два трека одного артиста подряд", x, y)
        }
    }

    @Test
    fun theSameRecordFromTwoServicesArrivesOnce() {
        """Один сервис отдал ISRC, другой — нет. Без второго ключа
           («название|артист») запись приезжала в станцию дважды."""
        val dupes = listOf(
            t("a", "Calyx & TeeBee", isrc = "GB1234567890") to StationRanker.Signals(vetted = true),
            t("a", "Calyx & TeeBee", isrc = null) to StationRanker.Signals(vetted = true),
            t("b", "Другой") to StationRanker.Signals(vetted = true),
        )
        val out = StationRanker.rank(dupes, seed = 5, size = 10)
        assertEquals(2, out.size)
    }

    @Test
    fun anEmptyPoolIsNotACrash() {
        assertTrue(StationRanker.rank(emptyList(), size = 10).isEmpty())
        assertTrue(StationRanker.rank(pool, size = 0).isEmpty())
    }

    @Test
    fun itNeverReturnsMoreThanAsked() {
        assertTrue(StationRanker.rank(pool, seed = 9, size = 5).size <= 5)
    }
}
