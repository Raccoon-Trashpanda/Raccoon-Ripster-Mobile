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
        // Тот же случай: кириллица здесь — ВХОДНЫЕ данные, а не подписи.
        // Сервисы отдают жанр на языке своей витрины («Електро» от Deezer на
        // английский интерфейс, 06.09.2026), и словарь синонимов обязан знать
        // эти написания, чтобы свести их к одному ключу. Показываемое имя
        // берётся уже из Strings.kt по ключу «genre.<key>» — то есть перевод
        // здесь не нужен, а «перевод» синонимов сломал бы само распознавание.
        "core/service/GenreKey.kt" to "написания жанров для распознавания, не UI",
        // Распознаёт ИМЕНА ПАПОК на диске пользователя («Диск 2» рядом с «CD2»),
        // а не подписывает что-то на экране. Перевод сломал бы импорт коллекции.
        "core/library/FolderImport.kt" to "распознавание имён папок, не UI",
        // Внутренние check()/require(): их текст уходит в лог и в ветку
        // фолбэка на ExoPlayer, на экран не попадает никогда.
        //
        // ЭТО ИСКЛЮЧЕНИЕ ОДНАЖДЫ УЖЕ СОЛГАЛО. Файл был освобождён целиком —
        // и под прикрытием «это же внутренние проверки» в нём жил
        // formatLine(), который возвращал готовую русскую строку прямо в
        // плеер: при английском интерфейсе человек читал «(ресемпл → …)»
        // (поймано 05.09.2026). Поэтому файл разрешён не целиком —
        // построчно, и только там, где литерал стоит внутри check/require:
        // см. theNativeEngineWritesNoWordsForTheScreen ниже.
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

    /**
     * Страж обязан умирать ГРОМКО, а не молча.
     *
     * Проверяем сканер на том же входе, на котором регэксп ушёл в
     * `StackOverflowError`: незакрытая raw-строка плюс сотни килобайт после
     * неё. Правильный ответ — досканать до конца файла и не упасть.
     */
    @Test
    fun scannerSurvivesAnUnclosedRawString() {
        val q = '"'
        val src = buildString {
            append("val a = ").append(q).append("обычная").append(q).append('\n')
            append("val tail = ")
            repeat(3) { append(q) }
            append("незакрытая ")
            repeat(400_000) { append('x') }
        }
        val lits = stringLiterals(src)
        val plain = String(charArrayOf(q)) + "обычная" + String(charArrayOf(q))
        val opener = String(charArrayOf(q, q, q))
        val tail = lits.last()
        assertTrue("обычный литерал потерян: ${lits.map { it.take(24) }}", lits.contains(plain))
        assertTrue("хвост не начался с raw-ограничителя", tail.startsWith(opener))
        assertTrue("хвост не дошёл до конца файла", tail.endsWith("x"))
    }

    @Test
    fun theNativeEngineWritesNoWordsForTheScreen() {
        // Движок сообщает ФАКТ (RateNote + частота), слова подбирает ui/i18n.
        // Разрешены только сообщения check()/require() — они уходят в лог.
        val src = stripComments(File(sourceRoot(), "player/NativeAudioEngine.kt").readText())
        val offenders = src.lines().withIndex().filter { (_, line) ->
            cyrillic.containsMatchIn(line) &&
                !line.contains("check(") && !line.contains("require(")
        }.map { (i, line) -> "NativeAudioEngine.kt:${i + 1}: ${line.trim().take(80)}" }

        assertTrue(
            "Русский текст в аудио-движке вне check()/require() — он уедет на экран " +
                "мимо выбранного языка, как это было с formatLine(). Верни факт " +
                "(enum + число), а слова заведи в Strings.kt.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun noHardcodedEnglishInLabelsThePhoneReadsAloud() {
        // Кириллицу ловит проверка выше, но подпись для озвучки можно вшить и
        // ПО-АНГЛИЙСКИ — словарь этого не заметит, а незрячий человек с русским
        // интерфейсом услышит «Previous track» вместо «Предыдущий».
        // Найдено 05.09.2026 в плеере (prev/next/shuffle/repeat) и у шестерёнки
        // библиотеки — уже после того, как «подписи кнопок плеера» считались
        // сделанными.
        val root = sourceRoot()
        val brands = setOf("Ripster", "Tidal", "Qobuz", "Deezer", "Apple Music", "SoundCloud")
        val offenders = mutableListOf<String>()

        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            // Витрина компонентов из UI не открывается — см. allowed выше.
            if (allowed.keys.any { rel.endsWith(it) }) return@forEach
            englishLabelsIn(stripComments(f.readText())).forEach { label ->
                // Имя приложения и сервисов не переводится.
                if (label in brands) return@forEach
                offenders += "$rel: contentDescription = \"$label\""
            }
        }

        assertTrue(
            "Подпись для озвучки задана строкой, а не через tr(): с русским " +
                "интерфейсом её прочитают по-английски.\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Сторож обязан смотреть ПРАВУЮ ЧАСТЬ присваивания, а не только случай
     * `= "литерал"`.
     *
     * 23.09.2026, BUG-6: прежний регэксп читал только прямое присваивание —
     * `contentDescription = "…"`. Главная кнопка плеера подписывалась тернарником
     * `= if (loading) "Loading" else if (isPlaying) "Pause" else "Play"`, и
     * сторож, заведённый ровно против этого, три недели проходил мимо: на
     * русской сборке TalkBack читал «Pause».
     */
    @Test
    fun theGuardReadsPastTheEqualsIntoTernaryAndWhenBlocks() {
        val sample = stripComments(
            """
            Box(Modifier.semantics { contentDescription = if (playing) "Pause" else "Play" })
            Box(Modifier.semantics {
                contentDescription = when {
                    loading -> tr("a11y.loading", lang)
                    playing -> "Pause"
                    else -> tr("a11y.play", lang)
                }
            })
            Box(Modifier.semantics { contentDescription = tr("a11y.prev", lang) })
            """.trimIndent(),
        )
        val found = englishLabelsIn(sample)

        assertTrue("тернарник не просканирован: $found", found.containsAll(listOf("Pause", "Play")))
        assertTrue("ветка when не просканирована: $found", found.count { it == "Pause" } == 2)
        assertTrue(
            "ключ tr() не подписью считается — его вслух не читают: $found",
            found.none { it.contains("a11y") },
        )
    }

    /** Литералы, которыми подписывают озвучку: из правой части `contentDescription = …`. */
    private fun englishLabelsIn(src: String): List<String> {
        val assign = Regex("""contentDescription\s*=\s*""")
        val labels = mutableListOf<String>()
        assign.findAll(src).forEach { m ->
            val from = m.range.last + 1
            val tail = src.substring(from, statementEnd(src, from))
            stringLiterals(tail).forEach { lit ->
                val v = lit.trim('"')
                if (labelWord.matches(v)) labels += v
            }
        }
        return labels
    }

    /**
     * Подпись — то, что скринридер прочитает как слово: с заглавной буквы, без
     * цифр и без знаков, которыми пишут ключи словаря. Так «Pause» ловится, а
     * «a11y.pause», «UTF-8» и «%s» — нет.
     */
    private val labelWord = Regex("[A-Z][A-Za-z]*( [A-Z][A-Za-z]*)*")

    /**
     * До куда простёрта правая часть присваивания: до первой строки, на которой
     * баланс скобок вернулся к нулю. Иначе `when {` на трёх строках останется
     * недоглядённым — ровно та форма, что прятала баг.
     */
    private fun statementEnd(src: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < src.length) {
            when (src[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
            }
            if (src[i] == '\n' && depth <= 0) return i
            i++
        }
        return src.length
    }

    @Test
    fun noHandlerIsWiredToNothing() {
        // Кнопка, которая ничего не делает, — такой же обман, как кнопка,
        // которая делает не то. Найдено 05.09.2026: в плеере «Studio» главная
        // кнопка во всю ширину «Скачать альбом» была подключена как
        // `onDownloadAlbum = {}`, и нажатие не делало РОВНО ничего.
        //
        // Пустой обработчик уместен там, где экран сознательно не даёт
        // действия (витрина, предпросмотр) — такие места перечислены явно.
        val allowedEmpty = setOf(
            // Полоса перемотки в «Studio» не показывает предпросмотр кадра —
            // это осознанный отказ от фичи, а не забытая проводка.
            "onScrubPreview",
        )
        val root = sourceRoot()
        val call = Regex("""(on[A-Z]\w*)\s*=\s*\{\s*\}""")
        val decl = Regex("""(on[A-Z]\w*)\s*:\s*\(""")
        val offenders = mutableListOf<String>()

        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            if (allowed.keys.any { rel.endsWith(it) }) return@forEach
            stripComments(f.readText()).lines().forEachIndexed { i, line ->
                if (decl.containsMatchIn(line)) return@forEachIndexed
                call.findAll(line).forEach { m ->
                    if (m.groupValues[1] !in allowedEmpty) {
                        offenders += "$rel:${i + 1}: ${m.groupValues[1]} = {}"
                    }
                }
            }
        }

        assertTrue(
            "Обработчик подключён пустым: контрол на экране есть, а нажатие " +
                "не делает ничего.\n" +
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

    /**
     * Подпись, которую приняли и не применили, выглядит как сделанная работа.
     *
     * 04.09.2026: `SideGlyph` в плеере принимал `cd: String` и не использовал его
     * нигде. Четыре кнопки транспорта — перемотка, шаффл, повтор — были для
     * озвучки безымянными, хотя переводы к ним заведены и вызовы честно их
     * передавали. Увидеть это можно было только в дампе экрана.
     */
    @Test
    fun everyAccessibilityLabelParameterIsActuallyApplied() {
        val offenders = mutableListOf<String>()
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            if (!Regex("""\bcd:\s*String""").containsMatchIn(src)) return@forEach
            val used = src.contains("contentDescription = cd") ||
                src.contains("onClickLabel = cd") ||
                src.contains("contentDescription(cd)")
            if (!used) offenders += f.relativeTo(sourceRoot()).invariantPath()
        }
        assertTrue(
            "параметр подписи принят, но не применён — кнопка останется безымянной " +
                "для скринридера: " + offenders.joinToString(", "),
            offenders.isEmpty(),
        )
    }

    // ── вспомогательное ──────────────────────────────────────────────────────

    /** Путь через `/` независимо от разделителя ОС — чтобы сообщение теста
     *  читалось одинаково на любой машине. */
    private fun File.invariantPath(): String = path.split(File.separatorChar).joinToString("/")

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
     * internal: разбор нужен не только этому сторожу (см. LabelDefaultAuditTest),
     * а копировать его второй копией — значит иметь два разборчика, которые
     * разъедутся ровно тогда, когда станут важны.
     *
     * Символьные литералы приходится отслеживать наравне со строковыми: в коде
     * встречается `'"'` (например, список кавычек в CredentialInput), и без
     * этого разбор принимал бы такую кавычку за начало строки, съезжал на
     * полфайла и «находил» кириллицу в комментариях. Первый прогон теста именно
     * это и показал. */
    internal fun stripComments(src: String): String {
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

    /**
     * Литералы без регэкспа.
     *
     * Здесь стояло `Regex("\"\"\"(?:.|\\n)*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"")`, и
     * на `Strings.kt` (161 КБ) оно ловило `StackOverflowError`: java.util.regex
     * для ленивого цикла `(.|\n)` рекурсируется на каждый символ, а один
     * не закрытый как надо `"""` заставляет его пройти до конца файла. Страж
     * при этом не «не нашёл», а УМЕР — то есть перестал проверять молча, ровно
     * тот класс отказа, который мы ищем этим раундом (раунд 2, 21.09.2026).
     *
     * Скан линейный и в состоянии учитывает то же, что и `stripComments`:
     * экранирование, символьные литералы и raw-строки с серией кавычек.
     */
    internal fun stringLiterals(src: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\'' -> {
                    // Символьный литерал: `'"'` — не начало строки.
                    var j = i + 1
                    while (j < src.length && src[j] != '\'') { if (src[j] == '\\') j++; j++ }
                    i = minOf(j + 1, src.length)
                }
                c == '"' && src.startsWith("\"\"\"", i) -> {
                    var j = i + 3
                    while (j < src.length) {
                        if (src[j] == '"') {
                            var n = 0
                            while (j + n < src.length && src[j + n] == '"') n++
                            // Закрывают ПОСЛЕДНИЕ три кавычки серии (см. stripComments).
                            if (n >= 3) { j += n; break }
                            j += n
                        } else j++
                    }
                    out += src.substring(i, minOf(j, src.length))
                    i = j
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < src.length && src[j] != '"' && src[j] != '\n') {
                        if (src[j] == '\\') j++
                        j++
                    }
                    out += src.substring(i, minOf(j + 1, src.length))
                    i = j + 1
                }
                else -> i++
            }
        }
        return out
    }
}
