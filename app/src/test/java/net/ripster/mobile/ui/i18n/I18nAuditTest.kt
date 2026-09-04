package net.ripster.mobile.ui.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож против возврата хардкода языка.
 *
 * 04.09.2026 владелец: «ошибки должны быть на языке, выбранном в программе;
 * практика показала, что там русский, даже если выбран английский». Разбор
 * показал, что виноват не словарь: движки бросали готовую русскую строку, а три
 * экрана печатали `exception.message` как есть. Починили — но починка такого
 * рода живёт ровно до следующей быстрой правки, если её никто не стережёт.
 *
 * Тест обходит боевые исходники и падает на кириллице внутри строковых
 * литералов. Комментарии по-русски — норма проекта и вырезаются перед разбором:
 * на экран они не попадают.
 *
 * Список исключений намеренно короткий и каждое объяснено. Если понадобилось
 * добавить файл — сперва спроси себя, точно ли строка не видна человеку.
 */
class I18nAuditTest {

    /** Файлы, где кириллица законна. Каждый — с причиной, а не «так вышло». */
    private val allowed = mapOf(
        // Сам словарь переводов: русский текст здесь и должен быть.
        "ui/i18n/Strings.kt" to "таблица переводов",
        // Витрина компонентов для отладки дизайн-системы: из UI не открывается
        // (см. комментарий у RipsterRoot), человеку не показывается.
        "MainActivity.kt" to "витрина компонентов, из UI не открывается",
        "ui/screens/SettingsScreen.kt" to "демо-экран витрины, вызывается только из MainActivity",
        "ui/screens/PairingScreen.kt" to "демо сопряжения без сетевого рукопожатия, только из MainActivity",
        // Не подписи, а данные для сопоставления: слова, отличающие ремикс от
        // оригинала. Их «перевод» сломал бы подбор той же записи.
        "core/service/TrackMatch.kt" to "ключевые слова сопоставления, не UI",
        // Внутренние check()/require(): их текст уходит в лог и в ветку
        // фолбэка на ExoPlayer, на экран не попадает никогда.
        "player/NativeAudioEngine.kt" to "сообщения внутренних проверок, не UI",
    )

    private val cyrillic = Regex("[А-Яа-яЁё]")

    @Test
    fun noHardcodedRussianInProductionUi() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()

        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            if (allowed.keys.any { rel.endsWith(it) }) return@forEach
            val clean = stripComments(f.readText())
            stringLiterals(clean).forEach { lit ->
                if (cyrillic.containsMatchIn(lit)) {
                    offenders += "$rel: ${lit.take(80)}"
                }
            }
        }

        assertTrue(
            "Кириллица в строковых литералах боевого кода — язык интерфейса на неё " +
                "не влияет. Заведи ключ в Strings.kt (все пять языков) и покажи через " +
                "tr(), а для ошибок движка — маркер EngineErrors.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun everyEngineErrorMarkerHasATranslation() {
        val root = sourceRoot()
        val markers = Regex("\"__e\\.([a-z_]+)__\"")
            .findAll(File(root, "core/errors/EngineErrors.kt").readText())
            .map { it.groupValues[1] }.toList()
        assertTrue("маркеры ошибок не найдены — проверь EngineErrors.kt", markers.isNotEmpty())

        val strings = File(root, "ui/i18n/Strings.kt").readText()
        val missing = markers.filterNot { strings.contains("\"err.$it\"") }
        assertTrue(
            "у маркера нет перевода — на экране будет виден сам ключ: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun everyTranslationRowCoversAllFiveLanguages() {
        // row(...) принимает ровно пять аргументов; недобор ловится компилятором,
        // а вот пустая строка вместо перевода — нет. Она означает пустое место на
        // экране, что хуже ключа: пользователь не поймёт даже, что текст был.
        val strings = File(sourceRoot(), "ui/i18n/Strings.kt").readText()
        val empties = Regex("\"([a-z0-9_.]+)\" to row\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(strings)
            .filter { m -> Regex("(^|,)\\s*\"\"\\s*(,|$)").containsMatchIn(m.groupValues[2]) }
            .map { it.groupValues[1] }.toList()
        assertTrue("пустой перевод — пустое место на экране: $empties", empties.isEmpty())
    }

    // ── вспомогательное ──────────────────────────────────────────────────────

    private fun sourceRoot(): File {
        // Тест запускают и из корня модуля, и из корня проекта — ищем оба.
        val candidates = listOf(
            File("src/main/java/net/ripster/mobile"),
            File("app/src/main/java/net/ripster/mobile"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("не нашёл исходники: ${candidates.map { it.absolutePath }}")
    }

    /** Убрать //-, /* */- и KDoc-комментарии, не задев строковые литералы.
     *
     * Символьные литералы приходится отслеживать наравне со строковыми: в коде
     * встречается `'"'` (например, список кавычек в CredentialInput), и без
     * этого разбор принимал бы такую кавычку за начало строки, съезжал на
     * полфайла и «находил» кириллицу в комментариях. Первый прогон теста именно
     * это и показал. */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inStr = false
        var inChar = false
        var inRaw = false
        while (i < src.length) {
            val c = src[i]
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            val three = if (i + 2 < src.length) src.substring(i, i + 3) else ""
            when {
                inRaw -> {
                    if (c == '"') {
                        // Raw-строку закрывают ПОСЛЕДНИЕ три кавычки серии, а не
                        // первые. В коде есть `Regex("""…\.js)"""")` — четыре
                        // подряд: содержимое кончается кавычкой, потом ограничитель.
                        // Наивная проверка первых трёх обрывала строку на символ
                        // раньше, оставшаяся кавычка открывала обычную строку, и
                        // разбор ехал на полфайла — первый прогон теста «нашёл»
                        // из-за этого кириллицу в комментариях.
                        var n = 0
                        while (i + n < src.length && src[i + n] == '"') n++
                        out.append(src, i, i + n)
                        i += n
                        if (n >= 3) inRaw = false
                    } else { out.append(c); i++ }
                }
                inStr -> {
                    if (c == '\\' && i + 1 < src.length) { out.append(src, i, i + 2); i += 2 }
                    else { if (c == '"') inStr = false; out.append(c); i++ }
                }
                inChar -> {
                    if (c == '\\' && i + 1 < src.length) { out.append(src, i, i + 2); i += 2 }
                    else { if (c == '\'') inChar = false; out.append(c); i++ }
                }
                three == "\"\"\"" -> { inRaw = true; out.append(three); i += 3 }
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '\'' -> { inChar = true; out.append(c); i++ }
                two == "//" -> { while (i < src.length && src[i] != '\n') i++ }
                two == "/*" -> { i += 2; while (i + 1 < src.length && src.substring(i, i + 2) != "*/") i++; i += 2 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun stringLiterals(src: String): List<String> =
        Regex("\"\"\"(?:.|\\n)*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"")
            .findAll(src).map { it.value }.toList()
}
