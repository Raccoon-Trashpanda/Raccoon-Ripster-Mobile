package net.ripster.mobile.ui.premium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблица пружинного движения и состояний строки текста.
 *
 * Как и [PremiumVisualsTest]: эти ветки не проверяются глазами на приборе
 * владельца (Android 9, эмулятор API 30 — ни размытия, ни меша там нет), а
 * ошибиться в них значит либо «текст поехал» на ровном месте, либо пустить
 * анимацию там, где человек попросил телефон не анимировать.
 */
class PremiumMotionTest {

    // ── обложка и play/pause ────────────────────────────────────────────────

    @Test
    fun `artwork sits down on pause and back to full size on play`() {
        assertEquals(1f, PremiumMotion.artworkScale(springMotion = true, isPlaying = true), 0f)
        assertEquals(PremiumMotion.ARTWORK_PAUSED,
            PremiumMotion.artworkScale(springMotion = true, isPlaying = false), 0f)
    }

    @Test
    fun `paused squeeze stays a hint, not a jump`() {
        // Ниже 0.9 обложка в плашке и в плеере расходятся настолько, что переход
        // читается как прыжок, а не как «пластинка села».
        assertTrue(PremiumMotion.ARTWORK_PAUSED in 0.9f..0.999f)
    }

    @Test
    fun `no spring motion means no scale change at all`() {
        // Режим выключен, «без анимации» или экономия заряда: обложка обязана
        // стоять на 1f в обоих состояниях — сжатие без пружины = дёрганье.
        for (playing in listOf(true, false)) {
            assertEquals("isPlaying=$playing", 1f,
                PremiumMotion.artworkScale(springMotion = false, isPlaying = playing), 0f)
        }
    }

    @Test
    fun `cover flies only when both the spring and the mini bounds are there`() {
        assertTrue(PremiumMotion.coverEnter(springMotion = true, miniCoverBoundsKnown = true))
        assertFalse(PremiumMotion.coverEnter(springMotion = false, miniCoverBoundsKnown = true))
        assertFalse(PremiumMotion.coverEnter(springMotion = true, miniCoverBoundsKnown = false))
        assertFalse(PremiumMotion.coverEnter(springMotion = false, miniCoverBoundsKnown = false))
    }

    // ── какая строка звучит ─────────────────────────────────────────────────

    private val starts = listOf(0L, 1_000L, 2_500L, 4_000L)

    @Test
    fun `sounding line is the last one that already started`() {
        assertEquals(0, LyricsLineState.activeIndex(starts, 0L))
        assertEquals(0, LyricsLineState.activeIndex(starts, 999L))
        assertEquals(1, LyricsLineState.activeIndex(starts, 1_000L))
        assertEquals(1, LyricsLineState.activeIndex(starts, 2_499L))
        assertEquals(3, LyricsLineState.activeIndex(starts, 90_000L))
    }

    @Test
    fun `before the first line the text waits on the first one`() {
        // Отрицательного индекса быть не может: «пустая подсветка» выглядела бы
        // как сломанный синхронный текст.
        assertEquals(0, LyricsLineState.activeIndex(starts, -5L))
        assertEquals(0, LyricsLineState.activeIndex(emptyList(), 1_000L))
    }

    @Test
    fun `unsorted timings still pick a real line`() {
        // Данные из LRCLIB и от Apple приходят не всегда по возрастанию;
        // бинарный поиск на таких данных подсвечивает мимо строки.
        assertEquals(1, LyricsLineState.activeIndex(listOf(2_000L, 500L), 600L))
    }

    // ── фокус и размытие строк ──────────────────────────────────────────────

    @Test
    fun `sounding line is in full focus, everything else dimmer`() {
        assertEquals(1f, LyricsLineState.focus(0), 0f)
        assertTrue(LyricsLineState.focus(1) < 1f)
        assertTrue(LyricsLineState.focus(-1) < 1f)
    }

    @Test
    fun `focus fades monotonically away from the sounding line`() {
        for (sign in listOf(-1, 1)) {
            for (d in 1..6) {
                assertTrue("d=${sign * d}", LyricsLineState.focus(sign * (d + 1)) <= LyricsLineState.focus(sign * d))
            }
        }
    }

    @Test
    fun `upcoming lines stay brighter than the ones already sung`() {
        // Человек читает вперёд, а то, что уже спето, можно глушить сильнее.
        for (d in 1..5) {
            assertTrue("d=$d", LyricsLineState.focus(d) > LyricsLineState.focus(-d))
        }
    }

    @Test
    fun `focus never dims a line into invisibility`() {
        for (d in -40..40) {
            assertTrue("d=$d", LyricsLineState.focus(d) >= 0.10f)
        }
    }

    @Test
    fun `depth blurs neighbours but never the sounding line`() {
        val here = LyricsLineState.lineAt(5, 5, depthEnabled = true)
        assertEquals("звучащая строка резкая", 0f, here.blurDp, 0f)
        assertTrue(here.active)
        assertTrue(LyricsLineState.lineAt(4, 5, depthEnabled = true).blurDp > 0f)
        assertTrue(LyricsLineState.lineAt(6, 5, depthEnabled = true).blurDp > 0f)
    }

    @Test
    fun `blur grows per line and stops at the cap`() {
        val base = LyricsLineState.lineAt(10, 10, depthEnabled = true)
        assertEquals("текущая резкая", 0f, base.blurDp, 0f)
        for (d in 1..3) {
            assertEquals("d=$d", LyricsLineState.BLUR_PER_LINE * d,
                LyricsLineState.lineAt(10 + d, 10, depthEnabled = true).blurDp, 0.001f)
        }
        val capped = LyricsLineState.BLUR_PER_LINE * LyricsLineState.BLUR_MAX_LINES
        for (d in 3..9) {
            assertEquals("d=$d", capped,
                LyricsLineState.lineAt(10 + d, 10, depthEnabled = true).blurDp, 0.001f)
        }
    }

    @Test
    fun `depth off means no blur anywhere but the dimming stays`() {
        for (d in 0..5) {
            val line = LyricsLineState.lineAt(10 + d, 10, depthEnabled = false)
            assertEquals("d=$d", 0f, line.blurDp, 0f)
            assertEquals("d=$d", LyricsLineState.focus(d), line.focus, 0f)
        }
    }

    @Test
    fun `only the sounding line grows - otherwise the whole list jumps`() {
        // Если бы размер зависел от расстояния, каждая смена строки меняла бы
        // высоту всех видимых строк, и текст подпрыгивал бы на каждом слове.
        var grown = 0
        for (i in 0..7) {
            if (LyricsLineState.lineAt(i, 3, depthEnabled = true).grow) grown++
        }
        assertEquals(1, grown)
        assertTrue(LyricsLineState.lineAt(3, 3, depthEnabled = true).grow)
        assertFalse(LyricsLineState.lineAt(4, 3, depthEnabled = true).grow)
    }

    @Test
    fun `line above and below the sounding one share the same blur`() {
        assertEquals(
            LyricsLineState.lineAt(2, 3, depthEnabled = true).blurDp,
            LyricsLineState.lineAt(4, 3, depthEnabled = true).blurDp,
            0.001f,
        )
    }

    // ── план и движение: одно не противоречит другому ────────────────────────

    @Test
    fun `whatever the plan forbids, the motion layer really stops`() {
        // Прогон по всем версиям и обоим отказам (без анимации, экономия
        // заряда): план сказал «пружин нет» — значит обложка не двигается, а
        // план сказал «глубины нет» — значит ни одна строка не размыта. Если
        // кто-то из них начнёт решать сам, этот тест поймает разошедшиеся
        // обещания настроек и плеера.
        for (sdk in 26..36) {
            for (anim in listOf(false, true)) {
                for (saver in listOf(false, true)) {
                    val p = PremiumVisuals.resolve(
                        enabled = true, sdkInt = sdk, animationsOff = anim,
                        batterySaver = saver, adaptiveColors = true,
                    )
                    for (playing in listOf(true, false)) {
                        val scale = PremiumMotion.artworkScale(p.springMotion, playing)
                        if (!p.springMotion) {
                            assertEquals("sdk=$sdk anim=$anim saver=$saver playing=$playing",
                                1f, scale, 0f)
                        }
                    }
                    for (d in -6..6) {
                        val line = LyricsLineState.lineAt(10 + d, 10, p.lyricsDepth)
                        if (!p.lyricsDepth) {
                            assertEquals("sdk=$sdk anim=$anim saver=$saver d=$d",
                                0f, line.blurDp, 0f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `disabled plan moves and blurs nothing`() {
        val off = PremiumPlan.Disabled
        assertEquals(1f, PremiumMotion.artworkScale(off.springMotion, isPlaying = false), 0f)
        assertFalse(PremiumMotion.coverEnter(off.springMotion, miniCoverBoundsKnown = true))
        for (d in 0..4) {
            assertEquals(0f, LyricsLineState.lineAt(d, 0, off.lyricsDepth).blurDp, 0f)
        }
    }

    @Test
    fun `a phone that got the spring also gets the cover flight`() {
        // Пружина есть, плашка замерена — обложка летит; на Android 9 (нет
        // блюра) движение при этом остаётся: оно дешёвое и не требует новых API.
        val old = PremiumVisuals.resolve(
            enabled = true, sdkInt = 28, animationsOff = false,
            batterySaver = false, adaptiveColors = true,
        )
        assertTrue(PremiumMotion.coverEnter(old.springMotion, miniCoverBoundsKnown = true))
        assertFalse(old.lyricsDepth)
    }
}
