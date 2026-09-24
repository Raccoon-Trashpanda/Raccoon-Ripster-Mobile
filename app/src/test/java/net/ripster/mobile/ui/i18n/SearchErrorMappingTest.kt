package net.ripster.mobile.ui.i18n

import net.ripster.mobile.core.errors.EngineErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-3 (этап 2 LIVE-кампании): в баннере поиска читалось
 * `Beatport: Element class kotlinx.serialization.json.JsonNull (Kotlin
 * reflection is not available) is not a JsonObject`.
 *
 * Экран обязан подписывать сбой сервиса СЛОВАМИ из словаря — на любом из пяти
 * языков, — а внутренности движка (классы, поля, стек) не показывать никогда.
 * При этом то, что словарем уже названо (маркер движка, таймаут, отсутствие
 * связи), терять нельзя: «503» и «429, подожди 30» человеку нужны.
 */
class SearchErrorMappingTest {

    /** Тот самый текст из кринка `s2_01_search_beatport_error.png`. */
    private val serializerLeak = IllegalStateException(
        "Element class kotlinx.serialization.json.JsonNull " +
            "(Kotlin reflection is not available) is not a JsonObject",
    )

    /** Сырое исключение не уходит на экран НИ НА ОДНОМ языке. */
    @Test
    fun rawExceptionTextNeverReachesTheScreen() {
        for (lang in AppLang.entries) {
            val text = safeErrorText(serializerLeak, lang)
            assertTrue("утек класс сериализатора (${lang.tag}): $text", "kotlinx" !in text.lowercase())
            assertTrue("утек тип из ответа (${lang.tag}): $text", "jsonobject" !in text.lowercase())
            assertTrue("утек текст исключения (${lang.tag}): $text", "is not a" !in text.lowercase())
            // Перевод обязан найтись: не найденный ключ tr() отдаёт как есть.
            assertNotEquals("нет перевода: $text", "search.svc_parse", text)
            assertTrue("пустая подпись: ${lang.tag}", text.isNotBlank())
        }
    }

    /** Языки различаются — иначе строка скопипастена в одну колонку. */
    @Test
    fun fallbackIsTranslatedIntoEveryLanguageNotCopied() {
        val byLang = AppLang.entries.map { safeErrorText(serializerLeak, it) }
        assertEquals("подписей пять", 5, byLang.distinct().size)
    }

    /**
     * `errorText` остаётся прежним: другие экраны (queue, health) продолжают
     * видеть причину так, как её назвал движок. Разница между двумя функциями
     * — ровно четвёртая ветка разбора.
     */
    @Test
    fun plainErrorTextStillShowsTheEnginesOwnWords() {
        assertTrue(
            "raw-ветка исчезла из errorText",
            errorText(serializerLeak, AppLang.EN).contains("JsonObject"),
        )
        assertNotEquals(errorText(serializerLeak, AppLang.EN), safeErrorText(serializerLeak, AppLang.EN))
    }

    /** Маркер движка — не «внутренности»: он переводим и detail нужен человеку. */
    @Test
    fun engineMarkersKeepTheirTranslationAndDetail() {
        val e = java.io.IOException(EngineErrors.code(EngineErrors.HTTP, "503"))
        val en = safeErrorText(e, AppLang.EN)
        assertTrue("деталь потеряна: $en", en.contains("503"))
        assertNotEquals(en, safeErrorText(serializerLeak, AppLang.EN))
        assertEquals("перевод должен совпасть с engineErrorText", engineErrorText(e.message, AppLang.EN), en)
    }

    /** Битый ответ сервиса — отдельная формулировка, а не «сервис молчит». */
    @Test
    fun parseFailureDiffersFromSilence() {
        val parse = safeErrorText(kotlinx.serialization.SerializationException("Expected STRING"), AppLang.RU)
        val silence = safeErrorText(java.io.IOException("HTTP 500 Internal Server Error"), AppLang.RU)
        assertNotEquals("разные причины подписаны одинаково", parse, silence)
    }

    /** Сеть и таймаут уже умели называться словами — не сломать по пути. */
    @Test
    fun networkAndTimeoutKeepTheirMessages() {
        assertEquals(tr("search.svc_neterr", AppLang.RU), safeErrorText(java.net.UnknownHostException("itunes.apple.com"), AppLang.RU))
        assertEquals(tr("search.svc_timeout", AppLang.RU), safeErrorText(java.net.SocketTimeoutException(), AppLang.RU))
        assertEquals(
            "экран сам бросает такой маркер при потолке на сервис",
            tr("search.svc_timeout", AppLang.RU),
            safeErrorText(java.io.IOException("__timeout__"), AppLang.RU),
        )
        // Голый IOException с сетевым почерком в сообщении — тоже сеть.
        assertTrue(
            "EHOSTUNREACH не опознан как сеть",
            safeErrorText(
                java.io.IOException("connect failed: EHOSTUNREACH (No route to host)"), AppLang.RU,
            ) == tr("search.svc_neterr", AppLang.RU),
        )
    }

    /** Язык важен: на английском экране не должно быть ни русской, ни иероглифной подписи. */
    @Test
    fun fallbackFollowsTheSelectedLanguage() {
        val ru = safeErrorText(serializerLeak, AppLang.RU)
        val en = safeErrorText(serializerLeak, AppLang.EN)
        assertFalse("русская и английская подписи совпали", ru == en)
        assertFalse("кириллица уехала в английский перевод", en.any { it.code in 0x0400..0x04FF })
        assertFalse("иероглифы уехали в английский перевод", en.any { it.code in 0x3040..0x9FFF })
    }
}
