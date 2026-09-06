package net.ripster.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * В живом интерфейсе не должно быть зашитого русского.
 *
 * Владелец 04.09.2026 получил русскую ошибку при английском интерфейсе, и
 * виноват был не словарь, а место, которое печатало текст мимо него. Такое
 * ловится только сторожем: глазами это не находят, потому что на русском
 * интерфейсе всё выглядит правильно.
 *
 * Сторож смотрит ТОЛЬКО на достижимые экраны. Файлы, помеченные шапкой
 * «ВЕРСТАК, А НЕ ЖИВОЙ ЭКРАН», человеку не показываются: переводить то, чего
 * никто не увидит, — работа ради работы, а внесённый в список исключений
 * файл сразу видно в проверке.
 */
class HardcodedStringsTest {

    private val visibleText = Regex("""(?:BasicText|Text)\(\s*"([^"]{2,})"|\btext\s*=\s*"([^"]{2,})"""")
    private val cyrillic = Regex("""[А-Яа-яЁё]{3,}""")

    @Test
    fun `no russian is hardcoded in screens people can actually reach`() {
        val root = File("src/main/java/net/ripster/mobile/ui")
        if (!root.exists()) return                       // запуск из другого корня — не наше дело
        val offences = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" && !it.path.contains("i18n") }.forEach { f ->
            val text = f.readText()
            if (text.contains("ВЕРСТАК, А НЕ ЖИВОЙ ЭКРАН")) return@forEach
            text.lineSequence().forEachIndexed { i, line ->
                if (line.contains("tr(")) return@forEachIndexed
                visibleText.findAll(line).forEach { m ->
                    val s = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    if (cyrillic.containsMatchIn(s)) offences += "${f.name}:${i + 1}  $s"
                }
            }
        }
        assertTrue(
            "русский зашит в живой экран — заведи строку через tr():\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }
}
