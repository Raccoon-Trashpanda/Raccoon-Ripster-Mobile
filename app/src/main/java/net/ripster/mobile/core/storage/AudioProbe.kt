package net.ripster.mobile.core.storage

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Настоящие параметры скачанного файла — из его заголовка, а не из того,
 * что обещал сервис. Нужно, чтобы бейдж качества и строка в библиотеке не
 * врали, и чтобы ловить «MP3 в контейнере FLAC» (контейнер говорит lossless,
 * заголовок — нет).
 */
object AudioProbe {

    data class Meta(
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val bitrateKbps: Int?,
        val lossless: Boolean,
        val codec: String?,
        /** Контейнер по расширению обещает lossless, но заголовок — lossy. */
        val fakeLossless: Boolean,
        /** Длительность из заголовка, сек. 0 — не прочиталась. */
        val durationSec: Int,
    )

    private val LOSSLESS_EXT = setOf("flac", "wav", "alac")

    data class Tags(val title: String?, val artist: String?, val album: String?)

    /** Название/артист/альбом из тегов файла. Нужно для Apple: имя приходит с
     *  ПК уже в файле, а в задаче — заглушка из слага ссылки. */
    fun tags(file: File): Tags? = runCatching {
        val tag = AudioFileIO.read(file).tag ?: return null
        fun g(k: FieldKey) = tag.getFirst(k)?.trim()?.ifBlank { null }
        Tags(g(FieldKey.TITLE), g(FieldKey.ARTIST), g(FieldKey.ALBUM))
    }.getOrNull()

    fun probe(file: File): Meta? {
        if (!file.exists() || file.length() < 1024) return null
        return runCatching {
            val h = AudioFileIO.read(file).audioHeader
            val sr = h.sampleRateAsNumber.takeIf { it > 0 }
            val bd = runCatching { h.bitsPerSample }.getOrNull()?.takeIf { it > 0 }
            val br = runCatching { h.bitRateAsNumber.toInt() }.getOrNull()?.takeIf { it > 0 }
            val fmt = runCatching { h.format }.getOrNull()?.lowercase().orEmpty()
            // jaudiotagger не всегда ставит isLossless для ALAC в .m4a —
            // распознаём по названию кодека и по эффективному битрейту.
            val effKbps = runCatching {
                (file.length() * 8.0 / 1000.0 / h.trackLength).toInt()
            }.getOrNull() ?: 0
            val lossless = h.isLossless ||
                "lossless" in fmt || "alac" in fmt || "flac" in fmt ||
                (file.extension.lowercase() == "m4a" && effKbps > 700)
            val extLossless = file.extension.lowercase() in LOSSLESS_EXT
            Meta(
                sampleRateHz = sr,
                bitDepth = bd,
                bitrateKbps = br,
                lossless = lossless,
                codec = runCatching { h.format }.getOrNull(),
                fakeLossless = extLossless && !lossless,
                durationSec = runCatching { h.trackLength }.getOrNull()?.takeIf { it > 0 } ?: 0,
            )
        }.getOrNull()
    }
}
