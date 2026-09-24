package net.ripster.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ripster.mobile.core.errors.attempt
import net.ripster.mobile.core.errors.isJobCancellation
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Широкий `catch` не должен глотать отмену.
 *
 * ПОВТОР БАГА (раунд 1, пункт 2.1; чинится в раунде 2). В `SearchScreen`
 * поиск ловил `Throwable` и превращал его в текст ошибки. Когда корутину
 * отменяли (человек ушёл с экрана, повернул телефон), прилетал
 * `JobCancellationException` — а это тоже `Exception`, и он превращался в
 * красную строку «Standalone coroutine was cancelled…». Отдельно в пяти
 * `health()` отменённая проверка учёток ставила учётке статус «мертва».
 *
 * Первое семейство тестов закрепляет сам факт: отмена ловится широким catch
 * (без этого теста починка висит на вере в иерархию классов). Второе —
 * решение (относить отмену к сбоям или нет). Третье — сторож по всем
 * исходникам: лечится сразу везде, иначе через неделю появится новый
 * `catch (t: Throwable)` с тем же поведением.
 */
class CancellationSwallowTest {

    // ── 1. баг реален: широкий catch ловит отмену ────────────────────────────

    /**
     * Отмена, долетающая до `catch` обычным путём.
     *
     * Нюанс, на котором первая версия теста и провалилась: `catch` вокруг
     * `delay` у ОТМЕНЁННОЙ корутины не выполняется вообще — kotlinx выбрасывает
     * исключение на последнем resume, и оно никуда не идёт (`DispatchedTask.run`
     * его отбрасывает). В живом коде отмена приходит НЕ оттуда: из `await()` отменённого
     * deferred, из `ensureActive()` в цикле, от закрытого канала — то есть из
     * точки, после которой корутина ещё собиралась работать. Тогда catch
     * отрабатывает и ЧТО-ТО решает. Проверяем решение, значит достаём отмену
     * тем же способом, каким она достаёт её в приложении.
     */
    private suspend fun cancellationInto(handler: (Throwable) -> Unit) {
        val inner = Job()
        val d = CoroutineScope(coroutineContext + inner).async {
            delay(5_000)
            1
        }
        inner.cancel()
        try {
            d.await()
        } catch (t: Throwable) {
            handler(t)
        }
    }

    @Test
    fun broadCatchDoesCatchCancellation() {
        var swallowed: Throwable? = null
        runBlocking { cancellationInto { swallowed = it } }
        assertTrue(
            "отмена должна была попасть в широкий catch (иначе весь тест бессмыслен)",
            swallowed is CancellationException,
        )
    }

    @Test
    fun cancellationInheritsFromException() {
        assertTrue(
            "CancellationException — потомок Exception: `catch (e: Exception)` её тоже ловит",
            CancellationException("x") is Exception,
        )
    }

    // ── 2. решение ───────────────────────────────────────────────────────────

    @Test
    fun plainJobCancellationIsNotAFailure() {
        var swallowed: Throwable? = null
        runBlocking { cancellationInto { swallowed = it } }
        val real = swallowed ?: error("не поймали отмену")
        assertTrue(
            "отменённую работу нельзя показывать как ошибку: ${real.javaClass.name}",
            real is CancellationException && isJobCancellation(real),
        )
    }

    @Test
    fun timeoutIsAFailure() {
        val real: Throwable = try {
            runBlocking { withTimeout(1) { delay(1_000) } }
            error("таймаута не было")
        } catch (t: Throwable) {
            t
        }
        assertTrue(real is TimeoutCancellationException)
        assertFalse(
            "таймаут — настоящая причина, человек обязан увидеть текст",
            isJobCancellation(real),
        )
    }

    @Test
    fun ordinaryErrorsStayErrors() {
        assertFalse(isJobCancellation(IOException("нет сети")))
        assertFalse(isJobCancellation(IllegalStateException("401")))
        assertFalse(isJobCancellation(RuntimeException("JNI")))
    }

    // ── 2b. `runCatching` — та же дыра, только без слова catch ───────────────

    @Test
    fun attemptPropagatesCancellationButKeepsFailures() {
        // Контрольная группа: обычный runCatching ОТДАЁТ отмену как Failure — именно так
        // «отменили» превращается в «не смогли спросить».
        val viaRunCatching = runBlocking {
            val inner = Job()
            val d = CoroutineScope(coroutineContext + inner).async { delay(5_000); 1 }
            inner.cancel()
            runCatching { d.await() }
        }
        assertTrue(
            "runCatching обязан проглотить отмену — иначе весь дальнейший смысл теста пуст",
            viaRunCatching.isFailure && viaRunCatching.exceptionOrNull() is CancellationException,
        )

        val boom: Throwable? = try {
            runBlocking {
                val inner = Job()
                val d = CoroutineScope(coroutineContext + inner).async { delay(5_000); 1 }
                inner.cancel()
                attempt { d.await() }
                null
            }
        } catch (t: Throwable) {
            t
        }
        assertTrue(
            "отмена обязана пройти сквозь attempt: ${boom?.javaClass?.name}",
            boom is CancellationException,
        )

        val failed = attempt { throw IOException("нет сети") }
        assertTrue("обычный сбой attempt обязан вернуть как Failure", failed.isFailure)
        assertTrue("значение тоже обязано доезжать", attempt { 7 }.getOrNull() == 7)
    }

    /**
     * Страж над «безопасными» обёртками.
     *
     * Раунд 1 пересчитал места по `catch (e: Exception)`, и я унаследовал этот
     * список — поэтому фикс отмены в пяти `health()` до панели учёток дошёл лишь
     * наполовину: сам вызов уже выбрасывал отмену наружу, а `AccountAutoHeal`
     * ловил её своим `runCatching { … }.getOrElse { unknown }` и писал «не смогли
     * спросить» — ровно тот текст, ради которого всё и чинилось. `runCatching`
     * ловит `Throwable`, а отмена — его потомок: синтаксически «catch» там нет,
     * и первый вариант стража был к этому классу слеп.
     *
     * Правило намеренно УЗКОЕ и совпадает с самим вредом: `runCatching { …}`
     * с suspend-вызовом, у которого ОБРАБОТЧИК результата (`.getOrElse {}`,
     * `.getOrDefault()`, `.?:` — цепочка сразу за скобками) СОБИРАЕТ ВИДИМЫЙ
     * ВЕРДИКТ (`AccountHealth`, `unknown(`, `EngineErrors`). Именно обработчик
     * и есть строка, где «перестали спрашивать» становится «учётка мертва».
     *
     * Почему не «в окне вокруг»: первый вариант смотрел на ±300 знаков и ловил
     * так три заведомоневинных места — `runCatching { df.delete() }` (имя
     * `delete` collision с чужой suspend-функцией), и два парсера
     * (`jwtExpired`, `parseDash`) в НЕsuspend-функциях, где отмене взяться
     * негде. Страж, который кричит на невозможное, через неделю глушат.
     *
     * Остальные `runCatching` с suspend-вызовом (бухгалтерия загрузок, откат на
     * ExoPlayer, запасной текст в отчёте) отменённую работу лечат «значением по
     * умолчанию». Раунд 4 закрыл и их: каждое место либо переведено на
     * `attempt` (отмена перестаёт быть значением по умолчанию), либо помечено
     * `catch-all-ok` с причиной — честный откат, где отмене взяться негде или
     * где доделывать MUST именно вопреки отмене (чистка файлов, отчёт).
     * Отсюда новое правило стража: НЕПОМЕЧЕННЫХ мест не остаётся вовсе; список
     * помеченных печатается в отчёт, чтобы их число было проверяемым.
     */
    @Test
    fun noRunCatchingTurnsCancellationIntoAVerdict() {
        val root = sourceRoot
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val suspendNames = HashSet<String>()
        files.forEach { f ->
            Regex("""suspend\s+fun\s+(?:<[^>]+>\s+)?(\w+)""")
                .findAll(f.readText()).forEach { suspendNames += it.groupValues[1] }
        }
        assertTrue("имена suspend-функций не собрались — страж слеп", suspendNames.size > 20)

        val verdict = Regex("""AccountHealth|unknown\s*\(|EngineErrors""")
        val unmarked = ArrayList<String>()   // молчаливые откаты — их должно быть 0
        val marked = ArrayList<String>()     // catch-all-ok — инвентарь для отчёта
        var seenRunCatching = 0
        val bad = ArrayList<String>()
        files.forEach { f ->
            val text = f.readText()
            Regex("""runCatching\s*\{""").findAll(text).forEach { m ->
                if (isComment(text, m.range.first)) return@forEach
                seenRunCatching++
                val close = closeBraceAt(text, m.range.last - 1)
                val body = text.substring(m.range.last, minOf(close, text.length))
                val callsSuspend = Regex("""\.(\w+)\s*\(""")
                    .findAll(body).any { it.groupValues[1] in suspendNames }
                if (!callsSuspend) return@forEach
                val site = "${f.relativeTo(root)}:${text.take(m.range.first).count { it == '\n' } + 1}"
                val handler = handlerChain(text, close)
                if ("catch-all-ok" in handler || catchAllOkAbove(text, m.range.first)) {
                    marked += site
                    return@forEach
                }
                unmarked += site
                if (!verdict.containsMatchIn(handler)) return@forEach
                if ("isJobCancellation" in handler) return@forEach
                bad += site
            }
        }
        assertTrue(
            "страж не видел ни одного runCatching — он сломан",
            seenRunCatching >= 100,
        )
        assertTrue(
            "проглоченная отмена станет вердиктом об учётке:\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
        assertTrue(
            "отмена лечится «значением по умолчанию» — раунд 4 требует решения по каждому месту " +
                "(перевести на attempt или пометить catch-all-ok с причиной):\n" +
                unmarked.joinToString("\n"),
            unmarked.isEmpty(),
        )
        println("runCatching-with-suspend: непомеченных ${unmarked.size}, помеченных catch-all-ok ${marked.size}")
    }

    /** Пометка стоит над местом: проверяем две предыдущие строки (комментарий-маркер). */
    private fun catchAllOkAbove(text: String, at: Int): Boolean {
        var lineEnd = at
        repeat(2) {
            val start = text.lastIndexOf('\n', (lineEnd - 2).coerceAtLeast(0)) + 1
            if (start > lineEnd) return@repeat
            val line = text.substring(start, lineEnd)
            if ("catch-all-ok" in line) return true
            lineEnd = start
        }
        return false
    }

    /**
     * Цепочка обработчиков, идущая сразу за `runCatching {…}`: `.getOrElse {…}`,
     * `.getOrDefault(…)`, `.onFailure {…}` и т. д. Собирается дословно, потому
     * что именно в ней решается, чем станет исключение — текстом или ничем.
     */
    private fun handlerChain(text: String, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (true) {
            val rest = text.substring(i, minOf(i + 60, text.length))
            val head = Regex("""^\s*\.\s*\w+\s*""").find(rest) ?: break
            val open = i + head.range.last + 1
            if (open >= text.length || (text[open] != '(' && text[open] != '{')) break
            val end = closerAt(text, open)
            sb.append(text, i, end)
            i = end
        }
        return sb.toString()
    }

    /** Позиция сразу за парной скобкой данного вида (`(`, `{` или `[`). */
    private fun closerAt(text: String, open: Int): Int {
        val o = text[open]
        val c = when (o) {
            '{' -> '}'; '(' -> ')'; '[' -> ']'; else -> return open + 1
        }
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == o) depth++
            else if (text[i] == c) { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return text.length
    }

    /** Позиция сразу за парной закрывающей скобкой, открывающейся в `open`. */
    private fun closeBraceAt(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return text.length
    }

    // ── 3. сторож по исходникам ──────────────────────────────────────────────

    private val sourceRoot = run {
        listOf(File("app/src/main/java"), File("src/main/java"), File("../app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("нет app/src/main/java относительно ${File("").absolutePath}")
    }

    private val broadCatch = Regex("""catch\s*\(\s*\w+\s*:\s*(Throwable|Exception)\s*\)""")

    @Test
    fun noBroadCatchSwallowsCancellation() {
        val bad = ArrayList<String>()
        var seen = 0
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            broadCatch.findAll(text).forEach { m ->
                val at = m.range.first
                // «Здесь стояло catch (_: Exception)» в KDoc — не код, а
                // рассказ про то, почему кода такого быть не должно.
                if (isComment(text, at)) return@forEach
                seen++
                val stmt = enclosingTryStatement(text, at)
                if (cancellationAware(stmt)) return@forEach
                bad += "${f.relativeTo(sourceRoot)}:${text.take(at).count { it == '\n' } + 1}"
            }
        }
        assertTrue("страж никого не увидел — он сломан", seen >= 10)
        assertTrue(
            "отмена превратится в текст ошибки / в «учётка мертва»:\n${bad.joinToString("\n")}",
            bad.isEmpty(),
        )
    }

    /** Начало строки, где стоит позиция, закомментировано (`//` или `*` в KDoc). */
    private fun isComment(text: String, at: Int): Boolean {
        val start = text.lastIndexOf('\n', at - 1) + 1
        val head = text.substring(start, at).trimStart()
        return head.startsWith("//") || head.startsWith("*")
    }

    /** Отмена обработана, если в try-операторе есть явный разбор CancellationException. */
    private fun cancellationAware(tryStatement: String): Boolean =
        "CancellationException" in tryStatement || "isJobCancellation" in tryStatement ||
            "catch-all-ok" in tryStatement

    /**
     * Текст ВСЕГО try-оператора: от `try {` до последнего `catch`/`finally`.
     * Смотрим на весь, а не на один блок: законный приём — сначала
     * `catch (ce: CancellationException) { throw ce }`, потом широкий catch.
     */
    private fun enclosingTryStatement(text: String, catchAt: Int): String {
        val tryAt = findTryKeyword(text, catchAt)
        if (tryAt < 0) return text.substring(maxOf(0, catchAt - 400), minOf(text.length, catchAt + 400))
        // идём по цепочке блоков: try {…} catch {…} catch {…} finally {…}.
        // 23.09.2026: раньше после `catch (…) {` цикл искал СЛЕДУЮЩУЮ `{` где
        // угодно дальше по файлу, а `catch` — тоже где угодно (find без якоря).
        // У последней функции файла следующей `{` нет — тело catch выпадало из
        // проверки, и честный `if (isJobCancellation(t)) throw t` считался
        // проглатыванием (AppleProxyClient.appleReplyOrEmpty). Теперь каждый
        // блок закрывается своей парной скобкой, а catch/finally обязан идти
        // вплотную за предыдущим блоком.
        val firstOpen = text.indexOf('{', tryAt)
        if (firstOpen < 0) return text.substring(tryAt)
        var i = closeBrace(text, firstOpen)
        val link = Regex("""\s*(catch|finally)\s*(\([^)]*\))?\s*\{""")
        while (i < text.length) {
            val next = link.find(text, i) ?: break
            if (next.range.first != i) break
            i = closeBrace(text, next.range.last)
        }
        return text.substring(tryAt, minOf(i, text.length))
    }

    /** Позиция сразу за парной закрывающей скобкой. */
    private fun closeBrace(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return text.length
    }

    /**
     * `try` перед данным catch — СЛОВОМ, а не подстрокой: `retry {`, `entry`
     * и «поtry» в комментарии иначе сдвигают границу, и страж начинает
     * смотреть не в тот оператор.
     */
    private fun findTryKeyword(text: String, before: Int): Int {
        var at = before
        while (true) {
            at = text.lastIndexOf("try", at - 1)
            if (at < 0) return -1
            val beforeCh = if (at == 0) ' ' else text[at - 1]
            val after = text.substring(at + 3, minOf(at + 20, text.length)).trimStart()
            if (!beforeCh.isLetterOrDigit() && beforeCh != '_' && after.startsWith("{")) return at
        }
    }
}
