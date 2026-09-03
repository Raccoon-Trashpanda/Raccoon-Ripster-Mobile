package net.ripster.mobile.ui.i18n

import net.ripster.mobile.core.errors.EngineErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineErrorTextTest {

    /**
     * Главный тест: каждый маркер должен быть переведён на ВСЕ пять языков.
     *
     * Маркер без перевода — это регресс ровно того класса, который мы чиним:
     * человек снова видит техническую строку. Константы берём рефлексией, чтобы
     * новый маркер попадал под проверку сам, без правки теста.
     */
    @Test
    fun everyMarkerIsTranslatedInEveryLanguage() {
        val markers = EngineErrors::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.isAccessible = true; it.get(EngineErrors) as String }
            .filter { it.startsWith("__e.") }
        assertTrue("маркеров не найдено — сломалась рефлексия", markers.size >= 16)
        for (marker in markers) {
            for (lang in AppLang.entries) {
                val text = engineErrorText(marker, lang)
                assertNotNull("нет перевода: $marker / ${lang.tag}", text)
                assertTrue("перевод пустой: $marker / ${lang.tag}", !text.isNullOrBlank())
            }
        }
    }

    /** Хвост-деталь не переводится, но и не теряется — уходит в скобки. */
    @Test
    fun detailIsKeptInParentheses() {
        val text = engineErrorText(EngineErrors.code(EngineErrors.HTTP, "503 getFileUrl"), AppLang.EN)
        assertNotNull(text)
        assertTrue("деталь потеряна: $text", text!!.endsWith("(503 getFileUrl)"))
    }

    /** Пустая деталь не должна давать висящие скобки. */
    @Test
    fun blankDetailAddsNothing() {
        assertEquals(EngineErrors.HTTP, EngineErrors.code(EngineErrors.HTTP, null))
        assertEquals(EngineErrors.HTTP, EngineErrors.code(EngineErrors.HTTP, "  "))
        val text = engineErrorText(EngineErrors.HTTP, AppLang.EN)
        assertTrue("висячие скобки: $text", text!!.none { it == '(' })
    }

    /** Не наш текст проходит мимо: вызывающий покажет его как есть. */
    @Test
    fun foreignMessagesArePassedThrough() {
        assertNull(engineErrorText("Connection reset by peer", AppLang.RU))
        assertNull(engineErrorText(null, AppLang.RU))
        assertNull(engineErrorText("", AppLang.RU))
        // Похоже на маркер, но такого ключа нет — лучше отдать исходное сообщение,
        // чем показать человеку «err.nope».
        assertNull(engineErrorText("__e.nope__", AppLang.RU))
        // Незакрытый маркер — не маркер.
        assertNull(engineErrorText("__e.http", AppLang.RU))
    }

    /** Языки различаются — иначе таблица заполнена копипастой одного текста. */
    @Test
    fun translationsActuallyDiffer() {
        val ru = engineErrorText(EngineErrors.GEO_UK, AppLang.RU)
        val en = engineErrorText(EngineErrors.GEO_UK, AppLang.EN)
        val ja = engineErrorText(EngineErrors.GEO_UK, AppLang.JA)
        assertTrue("RU и EN совпали", ru != en)
        assertTrue("EN и JA совпали", en != ja)
    }
}
