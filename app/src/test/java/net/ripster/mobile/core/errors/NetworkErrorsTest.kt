package net.ripster.mobile.core.errors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * «Сеть или авторизация» — единственный вопрос, от которого зависит, что
 * человеку предложат починить.
 *
 * `isNetworkFailure` утверждает две вещи: конкретный сетевой класс — это сеть;
 * голая `IOException` — НЕ сеть (им оборачивают HTTP-коды и битый JSON); и
 * сообщение с явным сетевым почерком — сеть, кроме строк, которые движок уже
 * сам подписал маркером `__e.`. Цепочку причин обещают проходить целиком.
 */
class NetworkErrorsTest {

    private fun net(t: Throwable?) = isNetworkFailure(t)

    // ── классы-очевидцы ─────────────────────────────────────────────────────

    @Test
    fun dnsFailureIsNetwork() = assertTrue(net(UnknownHostException("host not found")))

    @Test
    fun connectRefusedIsNetwork() = assertTrue(net(ConnectException("Connection refused")))

    @Test
    fun socketResetIsNetwork() = assertTrue(net(SocketException("Connection reset")))

    @Test
    fun socketTimeoutIsNetwork() = assertTrue(net(SocketTimeoutException("timeout")))

    @Test
    fun interruptedIoIsNetwork() = assertTrue(net(InterruptedIOException()))

    @Test
    fun tlsFailureIsNetwork() = assertTrue(net(SSLException("handshake failed")))

    @Test
    fun noRouteIsNetwork() = assertTrue(net(NoRouteToHostException("No route to host")))

    // ── голая IOException сетью не считается ────────────────────────────────

    @Test
    fun httpCodeIsNotNetwork() {
        assertFalse(net(IOException("GET https://api.deezer.com -> HTTP 500")))
        assertFalse(net(IOException("tracklist 404")))
        assertFalse(net(IOException("Qobuz: no url in reply")))
    }

    @Test
    fun nullIsNotNetwork() = assertFalse(net(null))

    @Test
    fun arbitraryRuntimeIsNotNetwork() = assertFalse(net(IllegalStateException("cache miss")))

    // ── сетевой почерк в сообщении ──────────────────────────────────────────

    @Test
    fun networkPhrasesInMessageAreNetwork() {
        val phrases = listOf(
            "Unable to resolve host api.tidal.com",
            "no route to host",
            "Network is unreachable",
            "Failed to connect to qobuz.com/93.184.216.34 (port 443)",
            "connect failed",
            "Connection abort",
            "Broken pipe",
            "socket error",
            "read timed out",
            "ENOTCONN",
            "EHOSTUNREACH",
        )
        for (p in phrases) assertTrue("«$p» обязана считаться сетью", net(IOException(p)))
    }

    @Test
    fun phraseMatchIsCaseInsensitive() {
        assertTrue(net(IOException("UNKNOWNHOST")))
        assertTrue(net(IOException("CONNECTION REFUSED")))
    }

    @Test
    fun timeoutsWrappedInPlainIoExceptionAreNetwork() {
        assertTrue(net(IOException("OkHttp timeout after 30000ms")))
    }

    // ── цепочка причин ──────────────────────────────────────────────────────

    @Test
    fun networkCauseBehindWrapperIsStillNetwork() {
        val t = IOException("SoundCloud: stream failed", UnknownHostException("sc-hw.info"))
        assertTrue(net(t))
    }

    @Test
    fun networkCauseThreeLevelsDeep() {
        val deep = RuntimeException("layer 3", IOException("layer 2", SocketException("Connection reset")))
        assertTrue(net(deep))
    }

    @Test
    fun authCauseBehindWrapperIsNotNetwork() {
        assertFalse(net(RuntimeException("resolve", IOException(EngineErrors.TOKEN_INVALID))))
    }

    /**
     * `takeIf { it !== cur }` обязан ловить самопричину: JDK её не даёт построить
     * (`initCause(this)` кидает), поэтому проверяем двухшаговый цикл — обход
     * обязан остановиться, а не крутиться вечно.
     */
    @Test
    fun twoCycleOfCausesStopsAtTheCap() {
        val a = IOException("a")
        val b = IOException("b")
        a.initCause(b)
        b.initCause(a)
        assertFalse(net(a))
    }

    @Test
    fun chainDeeperThanTenIsNotExaminedToTheEnd() {
        // Обещание «проходим цепочку целиком» на практике упирается в потолок 10:
        // дальше не ищем, и это осознанно (иначе циклы в cause вешают процесс).
        var t: Throwable = UnknownHostException("far down")
        repeat(11) { t = IOException("wrap", t) }
        assertFalse(net(t))
    }

    // ── маркер движка ───────────────────────────────────────────────────────

    @Test
    fun engineMarkerStopsMessageFromBeingReadAsNetwork() {
        // Движок сам назвал причину — верить хвосту нельзя: он чужой текст.
        assertFalse(net(IOException(EngineErrors.code(EngineErrors.AUTH_FAILED, "connection refused"))))
    }

    @Test
    fun engineMarkerDoesNotHideANetworkCause() {
        val t = IOException(EngineErrors.HTTP, UnknownHostException("no dns"))
        assertTrue(net(t))
    }

    @Test
    fun networkReasonBehindAnEngineMarkerIsStillNetwork() {
        val t = IOException(
            EngineErrors.code(EngineErrors.PC_OFFLINE, "java.net.UnknownHostException: ripster-pc"),
        )
        assertTrue(net(t))
    }
}
