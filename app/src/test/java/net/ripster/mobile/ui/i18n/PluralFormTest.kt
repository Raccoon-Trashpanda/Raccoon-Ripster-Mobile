package net.ripster.mobile.ui.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * согласование числительного с существительным.
 *
 * Владелец перечислил это отдельным классом текстовых дефектов, и он прав:
 * «3 трек(ов)» человек читает как недоделку, а носитель языка — как безграмотность.
 * Русскому нужны три формы, и ловушка не в «1/2/5», а в 11–14: по остатку от 10
 * это «один-два-четыре», а по правилу — множественное («11 треков», а не
 * «11 трека»). Именно поэтому проверка на 11..14 идёт ПЕРВОЙ.
 */
class PluralFormTest {

    private val ruExpect = mapOf(
        0 to "треков", 1 to "трек", 2 to "трека", 3 to "трека", 4 to "трека",
        5 to "треков", 6 to "треков", 10 to "треков",
        11 to "треков", 12 to "треков", 13 to "треков", 14 to "треков",
        15 to "треков", 20 to "треков", 21 to "трек", 22 to "трека", 24 to "трека",
        25 to "треков", 100 to "треков", 101 to "трек", 104 to "трека",
        111 to "треков", 114 to "треков", 121 to "трек", 1000 to "треков", 1001 to "трек",
    )

    @Test
    fun russianFormFollowsTheNumberNotJustItsLastDigit() {
        for ((n, word) in ruExpect) {
            assertEquals("$n → ", word, trTracks(n.toLong(), AppLang.RU))
        }
    }

    @Test
    fun negativeAndHugeCountsDoNotCrashTheRule() {
        // Отрицательное число приходит не из UI, а из разности — форма обязана
        // считаться по модулю, а не падать в «else» ветку со знаком.
        assertEquals("трек", trTracks(-1, AppLang.RU))
        assertEquals("треков", trTracks(-11, AppLang.RU))
        assertEquals("трека", trTracks(1_000_000_002, AppLang.RU))
    }

    @Test
    fun englishHasExactlyTwoForms() {
        assertEquals("track", trTracks(1, AppLang.EN))
        assertEquals("tracks", trTracks(2, AppLang.EN))
        assertEquals("tracks", trTracks(11, AppLang.EN))
        assertEquals("tracks", trTracks(0, AppLang.EN))
    }

    /**
     * В hindi/japanese/chinese форма одна на всё — иначе trPlural начал бы
     * выбирать из русской схемы и подставил бы в «{0} {1}» русское слово.
     */
    @Test
    fun nonInflectingLanguagesGetOneStableForm() {
        for (lang in listOf(AppLang.HI, AppLang.JA, AppLang.ZH)) {
            val one = trTracks(1, lang)
            assertEquals("${lang.tag}: форма поехала между 1 и 5", one, trTracks(5, lang))
            assertTrue("${lang.tag}: форма уехала в русскую транслитерацию", one.isNotBlank())
        }
    }

    /**
     * Сторож против «трек(ов)» в словаре.
     *
     * Скобка — это способ не решать: она одинаковоlooksWrong в любом языке.
     * Проверка читает словарь как текст (тот же приём, что у стражей
     * `HardcodedStringsTest`), потому что `STRINGS` приватен и не обязан
     * становиться публичным ради теста.
     */
    @Test
    fun dictionaryCarriesNoLazyParentheticalPlurals() {
        val file = sourceOf("Strings.kt") ?: return
        val offences = mutableListOf<String>()
        file.readText().lineSequence().forEachIndexed { i, line ->
            val lazy = Regex("""\(\s*(ов|а|ев|ьё|ы)\s*\)""").containsMatchIn(line)
            val t = line.trimStart()
            val comment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            if (lazy && !comment) offences += "${i + 1}: ${line.trim()}"
        }
        assertTrue(
            "форма числа спрятана в скобки — заведи ключ _one/_few/_many и согласуй через trPlural:\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    /**
     * Каждый `_n`-ключ обязан иметь русскую тройку, иначе trPlural молча
     * откатится на EN и человек увидит «tracks» в русском интерфейсе.
     */
    @Test
    fun pluralTripletsAreCompleteInAllFiveLanguages() {
        val keys = listOf("lib.tw_one", "lib.tw_few", "lib.tw_many")
        for (key in keys) {
            for (lang in AppLang.entries) {
                val value = tr(key, lang)
                assertTrue("$key/${lang.tag} не переведён (получили сам ключ)", value != key && value.isNotBlank())
            }
        }
        assertEquals("трек", tr("lib.tw_one", AppLang.RU))
        assertEquals("трека", tr("lib.tw_few", AppLang.RU))
        assertEquals("треков", tr("lib.tw_many", AppLang.RU))
    }

    private fun sourceOf(name: String): File? {
        val candidates = listOf(
            File("src/main/java/net/ripster/mobile/ui/i18n/$name"),
            File("app/src/main/java/net/ripster/mobile/ui/i18n/$name"),
        )
        return candidates.firstOrNull { it.isFile }
    }
}
