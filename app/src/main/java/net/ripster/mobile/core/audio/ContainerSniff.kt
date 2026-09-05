package net.ripster.mobile.core.audio

import java.io.File

/**
 * Что за файл на самом деле — по его первым байтам, а не по имени.
 *
 * Поймано живьём 05.09.2026 на телефоне владельца: в библиотеке лежал
 * `03 - Teardrop.flac` на 40 МБ, у которого внутри `00 00 00 1c ftyp ... dash`,
 * а в `stsd` — `fLaC`. То есть звук честно lossless, но упакован во
 * ФРАГМЕНТИРОВАННЫЙ MP4 (так отдают DASH), а вовсе не в нативный FLAC.
 *
 * Имя при этом говорило `.flac`, потому что расширение бралось из ЗАПРОШЕННОГО
 * тира качества, а не из того, что пришло. Отсюда две беды: нативный движок,
 * доверявший расширению, скармливал MP4 декодеру FLAC и молча падал на
 * ExoPlayer; а на компьютере такой файл не откроет почти ни один плеер — по
 * расширению он выберет неверный демуксер.
 *
 * Здесь только распознавание — чистая функция над байтами, чтобы правило
 * проверялось тестом, а не наблюдением за телефоном. Про качество звука она
 * НИЧЕГО не утверждает: контейнер и кодек — разные вещи, и «m4a» здесь значит
 * ровно «упаковка MP4», внутри которой может быть и AAC, и ALAC, и FLAC.
 */
object ContainerSniff {

    /** Сколько байт достаточно: самая дальняя проверяемая сигнатура — `ftyp` на 4..8. */
    const val HEAD_BYTES = 16

    /**
     * Контейнер по сигнатуре: `flac`, `wav`, `m4a`, `ogg`, `mp3`, `dsf`, `aiff`.
     * `null` — не опознан; тогда врать не надо, оставляем что было.
     */
    fun of(head: ByteArray): String? {
        if (head.size < 4) return null
        fun ascii(from: Int, s: String): Boolean {
            if (head.size < from + s.length) return false
            for (i in s.indices) if (head[from + i].toInt() and 0xFF != s[i].code) return false
            return true
        }

        return when {
            ascii(0, "fLaC") -> "flac"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(0, "FORM") && (ascii(8, "AIFF") || ascii(8, "AIFC")) -> "aiff"
            // Размер бокса впереди — сама сигнатура стоит на 4-м байте.
            ascii(4, "ftyp") -> "m4a"
            ascii(0, "OggS") -> "ogg"
            ascii(0, "DSD ") -> "dsf"
            ascii(0, "ID3") -> "mp3"
            // Кадр MPEG без ID3-тега: 11 бит синхронизации.
            (head[0].toInt() and 0xFF) == 0xFF &&
                (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> null
        }
    }

    /** То же для файла на диске. Нечитаемый файл — `null`, а не догадка. */
    fun of(file: File): String? = runCatching {
        file.inputStream().use { s ->
            val buf = ByteArray(HEAD_BYTES)
            val n = s.read(buf)
            if (n <= 0) null else of(buf.copyOf(n))
        }
    }.getOrNull()

    /**
     * Расширение, под которым файл надо сохранить.
     *
     * [requested] — контейнер запрошенного тира. Если байты опознаны и говорят
     * другое — верим байтам: имя должно описывать то, что лежит внутри.
     */
    fun extensionFor(file: File, requested: String): String {
        val actual = of(file) ?: return requested
        return actual
    }
}
