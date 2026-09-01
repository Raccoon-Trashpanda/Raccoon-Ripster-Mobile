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

    fun render(template: String, track: Track, quality: QualityTier): String {
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
        for (k in KEYS) out = out.replace("{$k}", values[k] ?: "")
        out = stripLeftoverPlaceholders(out)

        val rel = out.split('/')
            .map { sanitizeSegment(it) }
            .filter { it.isNotBlank() }
            .joinToString("/")
        val ext = quality.container.ifBlank { "bin" }
        return "$rel.$ext"
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
