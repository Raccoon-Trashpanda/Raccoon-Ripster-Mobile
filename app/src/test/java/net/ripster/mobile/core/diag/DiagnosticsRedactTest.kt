package net.ripster.mobile.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож над вычисткой отчёта о неполадке.
 *
 * Отчёт человек отправляет со своего телефона в чужой чат. Промах здесь —
 * не «некрасиво», а «утёк доступ», и заметить его постфактум нельзя: письмо
 * уже ушло. Поэтому у вычистки есть тест, и он проверяет обе стороны —
 * что секрет исчезает И что нормальный текст остаётся читаемым.
 */
class DiagnosticsRedactTest {

    private val token = "K7anI1mJugItVWtN7VKtldPrtsL9uxWtsEAqfjZTXCzZuKq0isLrotmPqEryXd96"
    private val pairs = listOf(token to "<qobuz.token>")

    @Test
    fun knownSecretIsRemovedEverywhereItAppears() {
        val text = """
            register: token=$token
            url: https://example.com/x?auth_token=$token&fmt=27
            note: $token again
        """.trimIndent()
        val out = Diagnostics.redact(text, pairs)
        assertFalse("настоящий токен остался в отчёте", out.contains(token))
        // Все три строки обязаны нести пометку. Какую именно — не важно:
        // в ссылке дословную замену сверху ещё раз накрывает шаблон для
        // параметров, и там остаётся «<redacted>». Требовать конкретный
        // ярлык значило бы проверять форму, а важно отсутствие секрета.
        out.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue("строка без пометки: $line", "redact" in line || "qobuz.token" in line)
        }
    }

    @Test
    fun unknownLongTokenIsMaskedToo() {
        // Чужой токен из ответа сервиса: в сторе его нет, дословно вырезать
        // нечего — должен сработать шаблон.
        val alien = "eyJhbGciOiJIUzI1NiJ9abcdef0123456789XYZ"
        val out = Diagnostics.redact("Tidal responded with $alien end", emptyList())
        assertFalse(out.contains(alien))
        assertTrue(out.contains("<redacted"))
    }

    @Test
    fun statusLinesSurvive() {
        // Тот самый баг: «tidal.oauth: set» попадало под шаблон заголовка и
        // превращалось в «<redacted>», из-за чего «задана» становилось
        // неотличимо от «не задана».
        val text = """
            tidal.oauth: set
            soundcloud.oauth: not set
            yandex.oauth: set, but the service REJECTED it
        """.trimIndent()
        val out = Diagnostics.redact(text, emptyList())
        assertTrue("статус учётки затёрт", out.contains("tidal.oauth: set"))
        assertTrue(out.contains("soundcloud.oauth: not set"))
        assertTrue(out.contains("REJECTED"))
    }

    @Test
    fun realHeaderIsStillRedacted() {
        val out = Diagnostics.redact("Authorization: Bearer abcdefghijklmnop", emptyList())
        assertFalse(out.contains("abcdefghijklmnop"))
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        // Имена классов, пути и обычные слова длиннее 24 знаков встречаются в
        // каждом стеке. Если бы шаблон резал их, отчёт стал бы нечитаемым —
        // а нечитаемый отчёт бесполезен ровно так же, как отсутствующий.
        val text = "net.ripster.mobile.service.qobuz.QobuzApi.fileUrl(QobuzApi.kt:210)"
        assertEquals(text, Diagnostics.redact(text, emptyList()))
    }

    @Test
    fun threadNamesAndPathsStayReadable() {
        // Замер на A31 06.09.2026: при пороге в 24 знака сеть съедала
        // «DefaultDispatcher-worker-1» в КАЖДОЙ строке журнала. Секретом это
        // не является, а журнал становился нечитаемым.
        val text = "Long monitor contention with owner DefaultDispatcher-worker-1 (6115)"
        assertEquals(text, Diagnostics.redact(text, emptyList()))
    }

    @Test
    fun emailKeepsItsDomain() {
        // Домен говорит, какой это сервис, — он полезен. Имя ящика не нужно.
        val out = Diagnostics.redact("qobuz.email vjz.hzh@gmail.com", emptyList())
        assertFalse(out.contains("vjz.hzh"))
        assertTrue(out.contains("gmail.com"))
    }

    @Test
    fun shortSecretsAreNotUsedAsPatterns() {
        // Короткие значения в пары не попадают (см. redactionPairs), но если
        // бы попали — замена подстроки «12» изуродовала бы весь отчёт.
        val out = Diagnostics.redact("track 12 of 13", emptyList())
        assertEquals("track 12 of 13", out)
    }
}
