package net.ripster.mobile.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Сейчас не вышло» и «не выйдет никогда» — разные новости, и совет разный.
 *
 * Живой случай 05.09.2026: тринадцать треков альбома отвалились с
 * «Apple: 429 Too Many Requests» и записались как окончательный отказ, хотя
 * достаточно было подождать. Здесь закрепляется и обратное свойство: причина,
 * которая сама не пройдёт, повторяться не должна — иначе настоящая новость
 * («нет подписки», «регион») спрячется за бесконечными попытками.
 */
class TransientFailureTest {

    @Test
    fun rateLimitingPasses() {
        assertTrue(
            TransientFailure.isTransient(
                "Apple: Failed to rip album: error getting album response: 429 Too Many Requests",
            ),
        )
        assertTrue(TransientFailure.isTransient("rate limit exceeded"))
    }

    @Test
    fun serverHiccupsPass() {
        listOf("502 Bad Gateway", "503 Service Unavailable", "504 Gateway Timeout").forEach {
            assertTrue(it, TransientFailure.isTransient(it))
        }
    }

    @Test
    fun networkBreaksPass() {
        listOf(
            "SocketTimeoutException: timeout", "Connection reset by peer",
            "software caused connection abort", "unexpected end of stream",
        ).forEach { assertTrue(it, TransientFailure.isTransient(it)) }
    }

    @Test
    fun realVerdictsAreNotRetried() {
        listOf(
            "нет активной подписки Apple Music",
            "Territory Restricted",
            "404 Not Found",
            "no codec found",
            "__e.pc_unpaired__",
        ).forEach { assertFalse(it, TransientFailure.isTransient(it)) }
    }

    @Test
    fun unknownIsNotTreatedAsTransient() {
        // Не знаем — не крутим. Ошибиться в сторону «повторим» дороже:
        // настоящая причина спрячется за попытками.
        assertFalse(TransientFailure.isTransient(null))
        assertFalse(TransientFailure.isTransient(""))
        assertFalse(TransientFailure.isTransient("что-то пошло не так"))
    }

    @Test
    fun retriesAreCapped() {
        assertTrue(TransientFailure.shouldRetry("429", 0))
        assertTrue(TransientFailure.shouldRetry("429", TransientFailure.MAX_ATTEMPTS - 1))
        assertFalse(
            "после потолка причина обязана дойти до человека как есть",
            TransientFailure.shouldRetry("429", TransientFailure.MAX_ATTEMPTS),
        )
    }
}
