package net.ripster.mobile.core.storage

import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Track

/**
 * Раскрытие шаблона пути/имени файла. По духу как десктопный `queue_paths`
 * (`{albumartist}/{album}/{track} - {title}`), но проще: плоский набор
 * плейсхолдеров, без условных секций и без регэкспов (их компиляция в
 * static-init однажды уронила весь воркер).
 *
 * Результат — относительный путь со слешами; каждый сегмент очищен от
 * символов, недопустимых в именах файлов Android/FAT. Расширение
 * добавляется отдельно из [QualityTier.container].
 */
object NameTemplate {

    const val DEFAULT = "{albumartist}/{album}/{track} - {title}"

    private val KEYS = listOf(
        "artist", "albumartist", "album", "title", "track", "disc", "year", "service", "quality",
    )
    // Недопустимо в имени файла Android/FAT. Пробел и дефис оставляем.
    private const val ILLEGAL = "\\/:*?\"<>|"

    /**
     * [actualContainer] — контейнер, опознанный по байтам уже скачанного файла
     * (см. `ContainerSniff`). Если он известен, имя строится по нему: расширение
     * должно описывать содержимое, а не пожелание.
     */
    fun render(
        template: String,
        track: Track,
        quality: QualityTier,
        actualContainer: String? = null,
    ): String {
        val trackNo = track.trackNumber?.let { "%02d".format(it) } ?: ""
        val values = mapOf(
            "artist" to track.artist,
            "albumartist" to (track.albumArtist ?: track.artist),
            "album" to (track.albumTitle ?: track.title),
            "title" to track.title,
            "track" to trackNo,
            "disc" to (track.discNumber?.toString() ?: ""),
            "year" to (track.year?.toString() ?: ""),
            "service" to track.service.label,
            "quality" to quality.label,
        )

        var out = template.ifBlank { DEFAULT }
        // Значения чистятся ДО подстановки: иначе «AC/DC» добавляет в путь
        // ступень, а не остаётся именем внутри сегмента.
        for (k in KEYS) out = out.replace("{$k}", sanitizeSegment(values[k] ?: ""))
        out = stripLeftoverPlaceholders(out)

        val rel = out.split('/')
            .map { sanitizeSegment(it) }
            .filter { it.isNotBlank() }
            .joinToString("/")
        // Расширение — по тому, что РЕАЛЬНО легло на диск, если это
        // известно. Тир качества здесь лишь запрос: Apple отдаёт ALAC в
        // MP4 там, где просили lossless, и файл уезжал в библиотеку с
        // именем `.flac` при содержимом MP4 (поймано 05.09.2026).
        val ext = (actualContainer ?: quality.container).ifBlank { "bin" }
        // Пустые метаданные дали бы файл «.flac»: в листере SAF его не видно,
        // библиотека его молча теряет. Имя — не текст интерфейса, поэтому
        // запасное остаётся ASCII.
        val base = rel.ifBlank { "untitled" }
        return "$base.$ext"
    }

    /** Убрать нераскрытые `{...}` без регэкспа. */
    private fun stripLeftoverPlaceholders(s: String): String {
        if ('{' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '{') {
                val close = s.indexOf('}', i)
                if (close != -1 && s.substring(i + 1, close).all { it in 'a'..'z' }) {
                    i = close + 1
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** Один сегмент пути: без разделителей, управляющих, хвостовых точек/пробелов. */
    private fun sanitizeSegment(s: String): String {
        val kept = buildString {
            var lastWasSpace = false
            for (ch in s) {
                when {
                    ch in ILLEGAL || ch.code < 0x20 -> Unit
                    ch.isWhitespace() -> {
                        if (!lastWasSpace) append(' ')
                        lastWasSpace = true
                    }
                    else -> {
                        append(ch)
                        lastWasSpace = false
                    }
                }
            }
        }
        // Пустой {track}/{disc} оставляет висячий разделитель («- Title») —
        // подчищаем края от разделителей, не трогая середину.
        return kept.trim().trim('-', ' ', '.', '_').trim().take(120)
    }
}
