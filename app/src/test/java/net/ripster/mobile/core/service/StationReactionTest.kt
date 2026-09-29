package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.reactions.Reaction
import net.ripster.mobile.core.reactions.ReactionKind
import net.ripster.mobile.core.reactions.ReactionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сессионные реакции: скипы, лайки, дослушанное (кейсы 12–14 и 16 задачи 2 из
 * `ops/qwen/gpt-sol/002-answer.md`).
 *
 * Проверяются ЧИСЛА спецификации, а не «похоже, стало лучше»: −0,12 за ранний
 * скип, −0,03 за поздний, лимит ±0,20, затухание вдвое за 12 СЛЕДУЮЩИХ
 * прослушанных треков, добавочные −0,08 за два ранних скипа подряд — и то, что
 * всё это остаётся ВРЕМЕННОЙ поправкой: постоянного «не мой» здесь не
 * заводится (вето живёт в радаре, и кейс 16 это проверяет).
 */
class StationReactionTest {

    private val MIN = 60 * 1000L
    private val HOUR = 60 * MIN
    private val NOW = 1_000_000_000_000L
    private val FOUR_MIN = 240_000L

    private fun tr(id: String, artist: String = "Артист", pop: Double? = 0.5) =
        Track(id = id, title = id, artist = artist, service = Service.DEEZER, popularity = pop)

    private fun play(at: Long, artist: String = "Артист") =
        StationRanker.Played(artist = artist, at = at)

    private fun react(
        kind: ReactionKind,
        artist: String = "Артист",
        at: Long = NOW,
        positionMs: Long? = null,
        durationMs: Long? = null,
    ) = Reaction(
        artist = artist, kind = kind, at = at,
        positionMs = positionMs, durationMs = durationMs,
    )

    private fun sessionOf(
        played: List<StationRanker.Played> = emptyList(),
        reactions: List<Reaction> = emptyList(),
    ) = StationRanker.Session(played = played, reactions = reactions)

    // ── кейс 12: где именно оборвали ────────────────────────────────────────

    @Test
    fun aSkipAtFiveSecondsCostsMoreThanASkipAtTheHalfwayMark() {
        """Кейс 12: скип через 5 с и скип через 2 мин при длительности 4 мин дают
        −0,12 и −0,03 (2 мин = ровно 50%, поздний скип начинается включительно с
        половины)."""
        val early = react(ReactionKind.EARLY_SKIP, positionMs = 5_000, durationMs = FOUR_MIN)
        val late = react(ReactionKind.LATE_SKIP, positionMs = 120_000, durationMs = FOUR_MIN)
        assertEquals(-0.12, StationRanker.reactionValue(early), 1e-9)
        assertEquals(-0.03, StationRanker.reactionValue(late), 1e-9)
    }

    @Test
    fun theEarlyBoundIsTheSmallerOfFifteenSecondsAndTenPercent() {
        """Короткий трек (60 с): 10% — это 6 с, и «ранним» считается скип до 6 с,
        а не до 15. Длинный (3 мин): 10% = 18 с, но потолок — 15 с."""
        assertEquals(-0.12, StationRanker.reactionValue(
            react(ReactionKind.EARLY_SKIP, positionMs = 5_000, durationMs = 60_000)), 1e-9)
        assertTrue(
            "скип на 10 с из 60 не должен стоить столько же, что и на 5 с",
            StationRanker.reactionValue(
                react(ReactionKind.EARLY_SKIP, positionMs = 10_000, durationMs = 60_000)) > -0.12,
        )
        assertEquals(-0.12, StationRanker.reactionValue(
            react(ReactionKind.EARLY_SKIP, positionMs = 15_000, durationMs = 180_000)), 1e-9)
    }

    @Test
    fun betweenEarlyAndLateThePenaltyGlidesNotJumps() {
        """Спецификация: «для промежутка штраф плавно интерполируется» — значит у
        скипов на 25% и на 45% пути штрафы РАЗНЫЕ и оба лежат между −0,12 и −0,03."""
        val a = StationRanker.reactionValue(
            react(ReactionKind.EARLY_SKIP, positionMs = 60_000, durationMs = FOUR_MIN))
        val b = StationRanker.reactionValue(
            react(ReactionKind.EARLY_SKIP, positionMs = 108_000, durationMs = FOUR_MIN))
        assertTrue("оба в коридоре: $a / $b", a in -0.12..-0.03 && b in -0.12..-0.03)
        assertTrue("чем дольше слушал, тем мягче: $a против $b", a < b)
    }

    // ── кейс 13: два ранних скипа подряд, и только подряд ────────────────────

    @Test
    fun twoEarlySkipsInARowAddATemporaryExtraPenalty() {
        """Кейс 13: сумма двух ранних скипов (−0,24) упирается в лимит −0,20, и
        уже ПОСЛЕ ограничения добавляется −0,08 → итого −0,28."""
        val s = sessionOf(reactions = listOf(
            react(ReactionKind.EARLY_SKIP, at = NOW - 2 * MIN, positionMs = 4_000, durationMs = FOUR_MIN),
            react(ReactionKind.EARLY_SKIP, at = NOW - MIN, positionMs = 5_000, durationMs = FOUR_MIN),
        ))
        assertEquals(-0.28, s.reactionFor("Артист"), 1e-9)
    }

    @Test
    fun theExtraPenaltyDiesAsSoonAsTheSkipsStopBeingConsecutive() {
        """Ранний, потом ЛАЙК, потом ранний — «двух подряд» уже нет: остаётся
        обычная сумма −0,12 + 0,15 − 0,12 = −0,09. Так штраф остаётся
        временным, а не превращается в вечное «не мой»."""
        val s = sessionOf(reactions = listOf(
            react(ReactionKind.EARLY_SKIP, at = NOW - 3 * MIN, positionMs = 4_000, durationMs = FOUR_MIN),
            react(ReactionKind.LIKE, at = NOW - 2 * MIN),
            react(ReactionKind.EARLY_SKIP, at = NOW - MIN, positionMs = 5_000, durationMs = FOUR_MIN),
        ))
        assertEquals(-0.09, s.reactionFor("Артист"), 1e-9)
    }

    // ── кейс 14: затухание по СЛЕДУЮЩИМ прослушанным, не по часам ────────────

    @Test
    fun twelveMoreTracksHalveAReactionAndTwelveHoursAloneDoNot() {
        """Кейс 14: после раннего скипа проиграно 12 треков → вклад стал −0,06.
        И обратное: двенадцать ЧАСОВ без новых прослушанных не убирают ничего —
        «пауза на ночь» вкус не стирает."""
        val overnight = sessionOf(reactions = listOf(
            react(ReactionKind.EARLY_SKIP, at = NOW - 12 * HOUR, positionMs = 4_000, durationMs = FOUR_MIN),
        ))
        assertEquals(-0.12, overnight.reactionFor("Артист"), 1e-9)

        val afterTwelve = sessionOf(
            played = (1..12).map { play(NOW - it * MIN) },
            reactions = listOf(
                react(ReactionKind.EARLY_SKIP, at = NOW - 13 * MIN, positionMs = 4_000, durationMs = FOUR_MIN),
            ),
        )
        assertEquals(-0.06, afterTwelve.reactionFor("Артист"), 1e-9)
    }

    // ── форма поправки ───────────────────────────────────────────────────────

    @Test
    fun theSumIsClampedAtBothEnds() {
        val likes = sessionOf(reactions = (1..10).map { react(ReactionKind.LIKE, at = NOW - it * MIN) })
        assertEquals(StationRanker.REACTION_LIMIT, likes.reactionFor("Артист"), 1e-9)

        val skips = sessionOf(reactions = (1..10).map {
            react(ReactionKind.EARLY_SKIP, at = NOW - it * MIN, positionMs = 3_000, durationMs = FOUR_MIN)
        })
        // −0,20 — потолок СУММЫ, и поверх него добавочные −0,08 за пару: лимит
        // ограничивает реакции, а не отменяет правило кейса 13.
        assertEquals(-0.28, skips.reactionFor("Артист"), 1e-9)
    }

    @Test
    fun anArtistWithoutReactionsIsUntouched() {
        val s = sessionOf(reactions = listOf(react(ReactionKind.LIKE, artist = "Другой")))
        assertEquals(0.0, s.reactionFor("Артист"), 1e-9)
        assertEquals(0.0, s.reactionFor(""), 1e-9)
    }

    @Test
    fun aLikeOutweighsAFullListenAndDownloadSitsBetween() {
        """Спецификация: явный лайк сильнее дослушивания; скачивание — плюс, но
        слабее лайка (скачал не всегда значит «одобряю»)."""
        assertTrue(StationRanker.REACT_LIKE > StationRanker.REACT_FULL)
        assertTrue(StationRanker.REACT_DOWNLOAD in StationRanker.REACT_FULL..StationRanker.REACT_LIKE)
    }

    @Test
    fun theReactionMovesRPrimeInsideTheUnitScale() {
        """R' = clip(R + Δ, 0, 1), и уже R' идёт в разнос: поправка не выносит
        оценку за границы шкалы и не перемножается с весом."""
        assertEquals(0.7125, StationRanker.mmrScore(0.8, 1.0, 0.0, reaction = 0.15), 1e-9)
        assertEquals(0.75, StationRanker.mmrScore(0.95, 1.0, 0.0, reaction = 0.5), 1e-9)
        // сильная поправка вниз не делает шанс нулевым — остаётся пол FLOOR
        assertEquals(0.05, StationRanker.mmrScore(0.1, 1.0, 0.0, reaction = -0.5), 1e-9)
    }

    @Test
    fun aLikedTrackWinsMoreOftenThanTheSameTrackWithoutTheLike() {
        """Два равных кандидата, у одного — лайк. Порог НЕ подгонян: сравниваются
        два замера на одних и тех же seed, и утверждается только «реакция
        двигает эфир в правильную сторону»."""
        val items = listOf(
            tr("liked", "Любимый") to StationRanker.Signals(vetted = true),
            tr("plain", "Обычный") to StationRanker.Signals(vetted = true),
        )
        val seeds = (1L..200L)
        val s = sessionOf(reactions = listOf(react(ReactionKind.LIKE, artist = "Любимый")))
        val withReaction = seeds.count {
            StationRanker.rank(items, seed = it, size = 1, nowMs = NOW, session = s)
                .firstOrNull()?.id == "liked"
        }
        val without = seeds.count {
            StationRanker.rank(items, seed = it, size = 1).firstOrNull()?.id == "liked"
        }
        assertTrue(
            "лайк не двигает эфир: с реакцией $withReaction, без $without",
            withReaction > without,
        )
    }

    @Test
    fun twentyEarlySkipsNeverBecomeAPermanentVeto() {
        """Кейс 16 рядом: сколько бы скипов ни накопилось, артист остаётся
        ДОПУСТИМЫМ (вето «не мой» живёт в `RadarRules`, не здесь) и не теряет
        шанс вовсе: поправка ограничена, а пол FLOOR остаётся."""
        val s = sessionOf(reactions = (1..20).map {
            react(ReactionKind.EARLY_SKIP, at = NOW - it * MIN, positionMs = 3_000, durationMs = FOUR_MIN)
        })
        val track = tr("x", "Артист")
        assertTrue("артист стал недопустимым без решения владельца", s.eligible(track, NOW))
        val score = StationRanker.mmrScore(1.0, 1.0, 0.0, reaction = s.reactionFor("Артист"))
        assertTrue("поправка обнулила кандидата: $score", score > 0.0)
    }

    @Test
    fun aReactionFromThePcCountsTheSameAsOneFromThePhone() {
        """Поле `origin` заведено заранее (импорт реакций с ПК пойдёт в тот же
        буфер). Проверяем, что ранкер на него НЕ смотрит: важен факт реакции, а
        не то, с какого устройства её поставили."""
        val phone = sessionOf(reactions = listOf(react(ReactionKind.LIKE)))
        val pc = sessionOf(reactions = listOf(
            Reaction("Артист", ReactionKind.LIKE, NOW, origin = ReactionOrigin.PC),
        ))
        assertEquals(phone.reactionFor("Артист"), pc.reactionFor("Артист"), 1e-9)
    }
}
