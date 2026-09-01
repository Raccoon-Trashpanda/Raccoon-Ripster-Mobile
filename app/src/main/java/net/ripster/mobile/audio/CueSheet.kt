package net.ripster.mobile.audio

/**
 * Разбор CUE — ПОРТ `parse_cue()` из `ripster/mixcue.py`, а не новая реализация.
 *
 * Зачем порт, а не «напишем как принято». Ripster сам пишет CUE к сводимым
 * миксам (`mixcue.build_mix`), и телефон обязан читать их ровно так же, как
 * читает десктоп. Две независимые реализации одного формата — это два разных
 * ответа на вопрос «где начинается третий трек», и разойдутся они не сразу, а
 * на чьём-нибудь конкретном миксе через полгода.
 *
 * Поведение сверено с оригиналом на общих тестовых векторах (23.08.2026),
 * включая частности, которые легко потерять при «чистом» переписывании:
 *
 *  * кадры CUE — 1/75 секунды, не 1/100 и не 1/1000;
 *  * TITLE и PERFORMER ДО первого TRACK относятся к альбому, после — к треку;
 *  * у трека без своего PERFORMER артистом становится альбомный;
 *  * учитывается только INDEX 01 (INDEX 00 — это пре-гэп, а не начало трека);
 *  * кавычки снимаются только парные, одиночная кавычка внутри названия жива.
 */
data class CueTrack(
    val num: Int,
    val title: String,
    val artist: String,
    /** Начало трека в секундах от начала файла-образа. */
    val start: Double,
)

data class CueSheet(
    val audio: String?,
    val album: String,
    val albumArtist: String,
    val tracks: List<CueTrack>,
) {
    /** Индекс трека, который звучит в момент [sec]; -1 если треков нет. */
    fun indexAt(sec: Double): Int {
        if (tracks.isEmpty()) return -1
        var i = 0
        for ((k, t) in tracks.withIndex()) if (sec >= t.start) i = k else break
        return i
    }
}

private val FILE_RE = Regex("""FILE\s+"?(.+?)"?\s+\w+\s*$""")
private val TRACK_RE = Regex("""TRACK\s+(\d+)""", RegexOption.IGNORE_CASE)
private val INDEX_RE = Regex("""INDEX\s+01\s+(\d+:\d+:\d+)""", RegexOption.IGNORE_CASE)

/** `MM:SS:FF` (75 кадров в секунде) → секунды. Кривое значение даёт 0.0, а не бросок. */
internal fun parseCueTime(t: String): Double {
    val p = t.trim().split(":")
    if (p.size != 3) return 0.0
    val mm = p[0].toIntOrNull() ?: return 0.0
    val ss = p[1].toIntOrNull() ?: return 0.0
    val ff = p[2].toIntOrNull() ?: return 0.0
    return mm * 60.0 + ss + ff / 75.0
}

internal fun cueUnquote(s: String): String {
    val v = s.trim()
    return if (v.length >= 2 && v.first() == '"' && v.last() == '"') v.substring(1, v.length - 1) else v
}

fun parseCue(text: String): CueSheet {
    var audio: String? = null
    var album = ""
    var albumArtist = ""
    val tracks = ArrayList<CueTrack>()

    var num = 0; var title = ""; var artist = ""; var start = 0.0
    var inTrack = false

    fun flush() { if (inTrack) tracks.add(CueTrack(num, title, artist, start)) }

    for (raw in text.lines()) {
        val line = raw.trim()
        val up = line.uppercase()
        when {
            up.startsWith("FILE ") ->
                FILE_RE.find(line)?.let { audio = it.groupValues[1] }

            up.startsWith("TRACK ") -> {
                flush()
                num = TRACK_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: (tracks.size + 1)
                title = ""; artist = ""; start = 0.0; inTrack = true
            }

            up.startsWith("TITLE ") -> {
                val v = cueUnquote(line.substring(6))
                if (!inTrack) album = v else title = v
            }

            up.startsWith("PERFORMER ") -> {
                val v = cueUnquote(line.substring(10))
                if (!inTrack) albumArtist = v else artist = v
            }

            up.startsWith("INDEX 01") ->
                INDEX_RE.find(line)?.let { if (inTrack) start = parseCueTime(it.groupValues[1]) }
        }
    }
    flush()

    val filled = tracks.map { if (it.artist.isEmpty()) it.copy(artist = albumArtist) else it }
    return CueSheet(audio, album, albumArtist, filled)
}
