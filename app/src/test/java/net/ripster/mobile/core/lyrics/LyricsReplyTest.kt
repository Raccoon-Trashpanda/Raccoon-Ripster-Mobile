package net.ripster.mobile.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Текста нет» и «мы не достучались» — разные ответы экрана.
 *
 * ПОВТОР БАГА (раунд 2). `ReferencePlayerScreen` уже был готов различать эти
 * два случая: он оборачивал вызов в `runCatching` и на провале показывал
 * `ref.lyrics_failed`. Но `LyricsClient.fetch` сам ловил ВСЕ исключения
 * (`runCatching { get(...) }.getOrNull()`) и возвращал null — то есть
 * `attempt.isFailure` не становился истинным НИКОГДА, и человек при 429, при
 * выключенном Wi-Fi и при умершем lrclib.net читал «у этой песни нет текста».
 *
 * Тесты ниже проверяют решение (три состояния вместо двух) и вторую находку
 * того же слоя: размытый `/api/search` брал ПЕРВУЮ непустую находку без
 * сверки, и под своим треком показывал слова другого артиста.
 */
class LyricsReplyTest {

    private val text = LyricsClient.Lyrics("первая строка\nвторая", emptyList())

    // ── 1. три состояния вместо двух ─────────────────────────────────────────

    @Test
    fun transportFailureIsNotAbsence() {
        // Сбой сети раньше давал РОВНО тот же ответ, что и «текста нет».
        assertTrue(LyricsClient.replyFor(null, "HTTP 429") is LyricsClient.Reply.Failed)
        assertEquals(
            "причину отдаём как есть — по ней и чинят (429 ≠ нет сети)",
            "HTTP 429",
            (LyricsClient.replyFor(null, "HTTP 429") as LyricsClient.Reply.Failed).reason,
        )
        // Свидетельство бага: «чистый» промах выглядит иначе, чем сбой.
        assertTrue(LyricsClient.replyFor(null, null) is LyricsClient.Reply.Absent)
    }

    @Test
    fun foundTextWinsEvenIfSomethingElseFailed() {
        // Первый вариант названия отвалился по сети, второй дал текст — это успех.
        val reply = LyricsClient.replyFor(text, "HTTP 503")
        assertTrue(reply is LyricsClient.Reply.Found)
        assertEquals(text, (reply as LyricsClient.Reply.Found).lyrics)
    }

    @Test
    fun emptyResultIsAbsenceNotFailure() {
        val blank = LyricsClient.Lyrics(null, emptyList())
        assertTrue(LyricsClient.replyFor(blank, null) is LyricsClient.Reply.Absent)
    }

    @Test
    fun bothNotFoundAnswersCarryNoText() {
        assertTrue(LyricsClient.Reply.Absent.lyrics.isEmpty)
        assertTrue(LyricsClient.Reply.Failed("нет сети").lyrics.isEmpty)
    }

    // ── 2. находка из поиска обязана быть ЭТОЙ песней ────────────────────────

    @Test
    fun otherArtistsSongIsRejected() {
        assertFalse(
            "тот же заголовок, другой артист — это ЧУЖИЕ слова",
            LyricsClient.accepts("Come Together", "Iowa Batchelor", "The Beatles", "Come Together"),
        )
    }

    @Test
    fun sameArtistSameTitleAccepted() {
        assertTrue(LyricsClient.accepts("Come Together", "The Beatles", "The Beatles", "Come Together"))
        assertTrue(
            "регистр и пробелы — не аргумент",
            LyricsClient.accepts("come   together", "the  beatles", "The Beatles", "Come Together"),
        )
    }

    @Test
    fun titleVariantsStillMatch() {
        // «Bare» у нас, «Bare (Redux)» у сервиса — и наоборот.
        assertTrue(LyricsClient.accepts("Bare (Redux)", "Kiasmos", "Kiasmos", "Bare"))
        assertTrue(LyricsClient.accepts("Bare", "Kiasmos", "Kiasmos", "Bare - Redux"))
    }

    @Test
    fun unrelatedTitleIsRejected() {
        assertFalse(LyricsClient.accepts("Hey Jude", "The Beatles", "The Beatles", "Come Together"))
    }

    @Test
    fun missingFieldsDoNotBlock() {
        // Сервис не назвал артиста — спорить нечем; называет — проверяем строго.
        assertTrue(LyricsClient.accepts("Come Together", "", "The Beatles", "Come Together"))
        assertTrue(LyricsClient.accepts(null, null, "The Beatles", "Come Together"))
    }

    @Test
    fun lrclib404IsAMissNotAFailure() {
        // Живой ответ LRCLIB 23.09.2026 на трек, которого нет в базе:
        // HTTP 404 {"name":"TrackNotFound"}. Это «текста нет», а не «сервис не ответил».
        assertTrue(LyricsClient.isMiss(404))
        assertFalse(LyricsClient.isMiss(429))   // лимит — это сбой, повторять стоит
        assertFalse(LyricsClient.isMiss(500))
        assertFalse(LyricsClient.isMiss(200))
        // Промах везде + ни одного сбоя → «текста нет», без «попробуй позже».
        assertEquals(LyricsClient.Reply.Absent, LyricsClient.replyFor(null, null))
    }
}
