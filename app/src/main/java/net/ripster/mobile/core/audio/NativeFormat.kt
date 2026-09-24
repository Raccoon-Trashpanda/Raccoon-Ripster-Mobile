package net.ripster.mobile.core.audio

/**
 * Какой из НАШИХ нативных декодеров брать для файла — те же коды, что у
 * `NativeAudioEngine` (`playQueue`, `nDecodeMono`): 0 flac, 1 wav, 2 alac(m4a),
 * 3 wavpack, 4 dsd.
 *
 * Порядок решения — СНАЧАЛА байты, имя файла только когда байты молчат. Имя —
 * это обещание, и с содержимым оно расходится в обе стороны, обе пойманы живьём:
 *
 *  · lossless из DASH-выдачи лежит под `.flac`, а внутри — `ftyp`-упаковка MP4
 *    (описанный в `ContainerSniff` случай `03 - Teardrop.flac`); по имени он
 *    уходил к dr_flac, и `open` возвращал null;
 *  · временный файл играющего потока `SpectrumSource` именует по заголовку
 *    Content-Type, а при молчании сервиса ставит `.m4a` поверх честного FLAC;
 *    по имени его отдавали ALAC-декодеру.
 *
 * Проигрывание байтам верит (`NativeAudioEngine.detectFormat`), поэтому трек
 * играл; спектр до 23.09.2026 выбирал декодер по расширению и отказывался
 * строить картинку на том же файле — «системный декодер не читает формат»,
 * которого он не пытался (BUG-5).
 *
 * Чистая функция над (байты, имя) — чтобы правило проверялось тестом, а не
 * наблюдением за телефоном.
 */
object NativeFormat {

    const val FLAC = 0
    const val WAV = 1
    const val ALAC = 2
    const val WAVPACK = 3
    const val DSD = 4

    /**
     * Код декодера для файла: сначала по байтам, при их молчании — по имени.
     * `null` — ни содержимое, ни имя не обещают формат, который тянет наш
     * тракт (mp3/ogg/aiff и вообще неопознанное).
     *
     * [head] — первые байты источника (`null`, если прочесть не удалось);
     * [name] — путь или URI, резервный источник формата.
     */
    fun decoderFor(head: ByteArray?, name: String?): Int? = byContent(head) ?: byName(name)

    /** Что сказали байты. `null` — «байты молчат»: не опознан либо заведомо
     *  чужой контейнер, тогда спрошаем имя. */
    private fun byContent(head: ByteArray?): Int? = when (head?.let { ContainerSniff.of(it) }) {
        "flac" -> FLAC
        "wav" -> WAV
        "wv" -> WAVPACK
        "dsf", "dff" -> DSD
        // MP4 — обещание упаковки, а не кодека: внутри и ALAC, и AAC, и FLAC.
        // Пробуем ALAC: `openAlac` сам откажется, если дорожки audio/alac нет.
        "m4a" -> ALAC
        else -> null
    }

    /** Последняя подсказка — расширение имени. Цена промаха один отказ `open`,
     *  а не ложный приговор формату. */
    private fun byName(name: String?): Int? = when (extensionOf(name)) {
        "flac" -> FLAC
        "wav", "wave" -> WAV
        "m4a", "m4b", "mp4", "alac", "aac" -> ALAC
        "wv" -> WAVPACK
        "dsf", "dff", "dsd" -> DSD
        else -> null
    }

    /**
     * Расширение имени без `?query` и `#fragment`, в нижнем регистре; `""` —
     * если расширения нет.
     *
     * Точка считается только в последнем сегменте пути: у
     * `content://media/external/audio/media/123` и у
     * `/data/net.ripster.mobile/files/Music/track` «расширения» нет, а
     * наивный `substringAfterLast('.')` выдавал бы `ripster.mobile/files/music`.
     */
    fun extensionOf(name: String?): String {
        if (name.isNullOrEmpty()) return ""
        val path = name.substringBefore('#').substringBefore('?')
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot < path.lastIndexOf('/')) return ""
        val ext = path.substring(dot + 1).lowercase()
        return if (ext.length in 1..5) ext else ""
    }
}
