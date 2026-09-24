package net.ripster.mobile.service.apple

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * BUG-9 (этап 9 LIVE-кампании): без сети поиск Apple Music отвечал «ничего не
 * вернул — проверь токен в настройках». `itunes()` ловил ВСЁ под
 * `runCatching{…}.getOrDefault(emptyList())`, поэтому `UnknownHostException`
 * становился пустым списком, а экран по единственному сервису трактовал пустоту
 * как протухшую учётку.
 *
 * Классификатор ниже — единственное, что решает «пусто» это или «бросай». Сеть
 * и отмена обязаны выходить наружу (тогда `SearchScreen` скажет «нет связи»),
 * а прочий мусор остаётся молчаливой пустотой.
 */
class AppleOfflineClassificationTest {

    private fun rethrown(block: () -> List<JsonElement>): Throwable? =
        try {
            block(); null
        } catch (t: Throwable) {
            if (t is AssertionError) throw t
            t
        }

    /** Адрес не разложился — это сеть, а не пустая выдача. */
    @Test
    fun unknownHostIsRethrownNotSilenced() {
        val caught = rethrown { appleReplyOrEmpty { throw UnknownHostException("itunes.apple.com") } }
        assertTrue("UnknownHostException проглочен пустотой", caught is UnknownHostException)
    }

    /** OkHttp кладёт настоящий сбой в cause, наружу отдаёт враппер-IOException. */
    @Test
    fun networkFailureWrappedInCauseIsRethrown() {
        val wrapper = IOException("Request failed").also { it.initCause(UnknownHostException("no dns")) }
        val caught = rethrown { appleReplyOrEmpty { throw wrapper } }
        assertTrue("сеть в cause не распознана", caught is IOException)
    }

    /** «No route to host» / «Network is unreachable» приходят голым сообщением. */
    @Test
    fun networkMessageOnBareIoExceptionIsRethrown() {
        rethrown { appleReplyOrEmpty { throw IOException("connect failed: EHOSTUNREACH (No route to host)") } }
            .also { assertTrue("EHOSTUNREACH не опознан как сеть", it != null) }
        rethrown { appleReplyOrEmpty { throw ConnectException("Failed to connect to itunes.apple.com") } }
            .also { assertTrue("ConnectException проглочен", it is ConnectException) }
    }

    /** Таймаут — тоже сеть: экран различает «не ответил» и «нет связи», но молчать не вправе. */
    @Test
    fun timeoutIsRethrown() {
        val caught = rethrown { appleReplyOrEmpty { throw SocketTimeoutException() } }
        assertTrue("таймаут стал пустотой", caught is SocketTimeoutException)
    }

    /** Отмена — не ошибка и не пустота: сигнал обязан пройти насквозь. */
    @Test
    fun cancellationIsRethrown() {
        val caught = rethrown { appleReplyOrEmpty { throw CancellationException("job cancelled") } }
        assertTrue("отмена проглочена", caught is CancellationException)
    }

    /** Битый ответ — честная пустота: частичный пропуск Apple-поиск переживает. */
    @Test
    fun malformedReplyStaysEmpty() {
        assertEquals(emptyList<JsonElement>(), appleReplyOrEmpty { throw SerializationException("Expected OBJECT") })
        assertEquals(
            "HTTP-код не сеть, но и не выдача — должно молчать",
            emptyList<JsonElement>(),
            appleReplyOrEmpty { throw IOException("__e.http__ 503") },
        )
    }

    /** Успешный ответ возвращается как есть, нетронутым. */
    @Test
    fun successfulReplyPassesThrough() {
        val blockRan = object : List<JsonElement> by emptyList() {}
        assertEquals(blockRan, appleReplyOrEmpty { blockRan })
    }

    /** Пустая выдача сервиса (не ошибка) остаётся пустой, а не превращается в исключение. */
    @Test
    fun genuineEmptyResultIsNotTurnedIntoError() {
        assertEquals(emptyList<JsonElement>(), appleReplyOrEmpty { emptyList() })
    }
}
