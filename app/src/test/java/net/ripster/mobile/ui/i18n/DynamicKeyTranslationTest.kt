package net.ripster.mobile.ui.i18n

import net.ripster.mobile.core.audio.Spectrogram
import net.ripster.mobile.core.service.GenreKey
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключи, которые собираются на лету, не должны собираться вникуда.
 *
 * Половина подписей на экране ищется в словаре не литералом, а склейкой:
 * `tr("genre.$key")`, `tr("spec.${s.id}")`, `tr("spec.v_${verdict}")`.
 * Такого вызова нет ни в одном статическом проверщике, и если домен
 * расширили, а строку не добавили, `tr` молча отдаёт **сам ключ** — человек
 * читает «genre.dnb» вместо «Drum & Bass».
 *
 * Поэтому проверяем не вызовы, а замыкание: у КАЖДОГО значения, которое
 * домен может выдать, есть перевод на всех пяти языках. Домен берём из
 * кода (константы GenreKey, enum'ы Spectrogram), а не переписываем сюда
 * руками — иначе тест разъедется с реальностью ровно в тот момент, когда
 * становится нужен.
 */
class DynamicKeyTranslationTest {

    private fun assertTranslated(prefix: String, suffixes: Collection<String>) {
        assertTrue("домен пуст — рефлексия сломалась", suffixes.isNotEmpty())
        val misses = ArrayList<String>()
        for (s in suffixes) {
            val key = "$prefix$s"
            for (lang in AppLang.entries) {
                val text = tr(key, lang)
                if (text == key || text.isBlank()) misses += "$key/${lang.tag}"
            }
        }
        assertTrue("нет перевода (экран покажет технический ключ):\n${misses.joinToString("\n")}",
            misses.isEmpty())
    }

    /** Канонические ключи жанра — публичные строковые константы GenreKey. */
    private fun genreKeys(): List<String> =
        GenreKey::class.java.declaredFields
            .filter { it.type == String::class.java && !it.isSynthetic }
            .map { it.isAccessible = true; it.get(GenreKey) as String }
            .distinct()

    @Test
    fun everyGenreKeyIsTranslated() = assertTranslated("genre.", genreKeys())

    @Test
    fun everySpectrumStyleIsTranslated() =
        assertTranslated("spec.", Spectrogram.Style.entries.map { it.id })

    @Test
    fun everyVerdictIsTranslated() =
        assertTranslated("spec.v_", Spectrogram.Verdict.entries.map { it.name.lowercase() })

    /**
     * Отказ разбора спектра называется СВОЕЙ строкой. Экран печатает
     * `tr(failure.key)` — ключ живёт в перечислении, литералом в исходниках его
     * не найти, и без этого теста дырка молча показала бы «ref.spectrum_short».
     * Прошлое — одна строка на три отказа — враньём про декодер (BUG-5, 23.09.2026).
     */
    @Test
    fun everySpectrumFailureIsTranslated() {
        val misses = ArrayList<String>()
        for (f in Spectrogram.Failure.entries) {
            for (lang in AppLang.entries) {
                val text = tr(f.key, lang)
                if (text == f.key || text.isBlank()) misses += "${f.key}/${lang.tag}"
            }
        }
        assertTrue("нет перевода отказа спектра:\n${misses.joinToString("\n")}", misses.isEmpty())
    }
}
