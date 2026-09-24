package net.ripster.mobile.ui.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Компонент не вправе подсовывать интерфейсу слово, которого нет в словаре.
 *
 * 23.09.2026, снимок владельца из плеера «Studio»: бейдж качества показывал
 * «not measured» по-английски при русском интерфейсе. Виноват был не вызов —
 * он ничего не передавал — а ДЕФОЛТ в подписи параметра:
 * `notMeasuredLabel: String = "not measured"`. Такой дефолт безобиден глазами:
 * экран на русском выглядит правильно, пока кто-нибудь не забудёт передать
 * подпись. Забыть легко — и мы забыли: у `SeekStrip` все семь подписей имели
 * английский дефолт, и НИ ОДИН из трёх вызывающих не передавал ни одной.
 * Скринридер читал «playing, 0:52 of 5:41».
 *
 * Отсюда правило: у параметра-подписи нет права на строковый дефолт. Либо
 * вызывающий передаёт tr(), либо компонент сам берёт tr() внутри — третий
 * вариант запрещён этим тестом.
 */
class LabelDefaultAuditTest {

    /**
     * `имя: String = "что-то"` — дефолт, который станет текстом на экране.
     *
     * Имя параметра обязано походить на подпись: `kind = "artist"` в DTO или
     * `theme = "Neon"` в настройках — это ДАННЫЕ и ключи, их перевод сломал бы
     * сопоставление. Поэтому фильтр по имени, а не «все строковые дефолты в
     * проекте».
     */
    private val defaulted = Regex(
        """(\b(?:\w*(?:label|description|title|caption|message|hint|placeholder|text|word|reason)\w*))\s*:\s*String\s*\??\s*=\s*"([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun noComponentBakesItsOwnLabelIntoADefault() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            // Только слой ui/: вне его tr() на экране всё равно не появится.
            if (!rel.startsWith("ui/")) return@forEach
            // Словарь — единственное место, где слова имеют право быть литералами.
            if (rel.endsWith("ui/i18n/Strings.kt")) return@forEach
            stripComments(f.readText()).lines().forEachIndexed { i, line ->
                defaulted.findAll(line).forEach { m ->
                    val value = m.groupValues[2]
                    // Пусто, знак или код (глиф «↓», «♪», `"%d"`) — не слово.
                    if (!value.any { it.isLetter() }) return@forEach
                    offenders += "$rel:${i + 1}: ${m.groupValues[1]} = \"$value\""
                }
            }
        }
        assertTrue(
            "Строковый дефолт у параметра-подписи = язык, зашитый в код. Передай " +
                "tr() в вызове или возьми его внутри компонента (`name: String? = null` " +
                "+ `name ?: tr(\"key\", lang)`).\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Каждое tr() с ключом «a11y.» обязано возвращать НЕ ключ: если строку
     * завели, но переводом забыли заполнить, скринридер зачитает «a11y.seek_position».
     */
    @Test
    fun everyAccessibilityKeyResolvesToWords() {
        val root = sourceRoot()
        val key = Regex("""tr\(\s*"(a11y\.[a-z0-9_]+)"""")
        val used = LinkedHashSet<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            key.findAll(f.readText()).forEach { used += it.groupValues[1] }
        }
        assertTrue("ключей a11y не найдено — тест сломался", used.isNotEmpty())
        val missing = used.filter { k ->
            AppLang.entries.any { lang -> tr(k, lang) == k || tr(k, lang).isBlank() }
        }
        assertTrue(
            "у подписи для озвучки нет перевода хотя бы на один язык — скринридер " +
                "прочитает сам ключ: $missing",
            missing.isEmpty(),
        )
    }

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/net/ripster/mobile"),
            File("app/src/main/java/net/ripster/mobile"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("не нашёл исходники: ${candidates.map { it.absolutePath }}")
    }

    private fun stripComments(src: String): String = I18nAuditTest().stripComments(src)
}
