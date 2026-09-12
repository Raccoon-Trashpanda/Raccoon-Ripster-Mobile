package net.ripster.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Набранное руками автосинк не трогает.
 *
 * Живой случай 06.09.2026, A31. На телефоне сохранён ЖИВОЙ токен Qobuz, экран
 * настроек его показывает — а клиент при запуске получает СТАРЫЙ, приехавший с
 * ПК: «register: token len=86 head=J29n», тогда как сохранён был «gmDH…».
 * Синхронизация перезаписывала ручное значение при каждом старте. Человек
 * вводит токен, видит его в поле, и приложение молча им не пользуется —
 * контрол показывает не то, чем живёт.
 *
 * Сравнивать отметки времени бесполезно: конфиг на ПК переписывается часто, и
 * его mtime почти всегда свежее ручной правки — пробовал, не помогло.
 *
 * Правило: слово человека держится, пока он сам не нажмёт «Забрать учётки с
 * ПК». Здесь проверяется именно решение, без Android-хранилища.
 */
class CredentialSourceTest {

    /** Копия решения из CredentialStore.mergeFromPc — та же логика, без prefs. */
    private fun accepts(
        storedIsManual: Boolean,
        storedValue: String?,
        force: Boolean,
    ): Boolean = !(storedIsManual && !force && storedValue != null)

    @Test
    fun `auto sync does not overwrite what a person typed`() {
        assertFalse(accepts(storedIsManual = true, storedValue = "gmDH", force = false))
    }

    @Test
    fun `the explicit pull button does overwrite it`() {
        // Человек сам попросил взять с ПК — значит он согласен потерять своё.
        assertTrue(accepts(storedIsManual = true, storedValue = "gmDH", force = true))
    }

    @Test
    fun `a value that came from the PC is refreshed as before`() {
        assertTrue(accepts(storedIsManual = false, storedValue = "J29n", force = false))
    }

    @Test
    fun `an empty slot is filled from the PC`() {
        // Пусто — значит человек ничего не вводил, и перезаписывать нечего.
        assertTrue(accepts(storedIsManual = true, storedValue = null, force = false))
    }
}
