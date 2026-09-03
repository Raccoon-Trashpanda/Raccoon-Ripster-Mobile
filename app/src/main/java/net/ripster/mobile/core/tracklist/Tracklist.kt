package net.ripster.mobile.core.tracklist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request

/**
 * Треклист микса с таймкодами — самодостаточно, без ПК.
 *
 * Два источника, ровно как в десктопной версии:
 *  • BBC отдаёт разметку официально — `programmes/{pid}/segments.json`, где у
 *    каждого события есть смещение от начала эфира, название и исполнитель;
 *  • у SoundCloud такой разметки нет, поэтому таймкоды достаём из описания
 *    трека — там их пишут руками («12:34 Artist — Title»), и это давно
 *    сложившийся формат.
 *
 * Ничего не выдумываем: если источник молчит, возвращаем пустой список, и
 * экран просто не показывает секцию. Придуманный треклист был бы хуже, чем его
 * отсутствие.
 */
object Tracklist {

    private val json = Json { ignoreUnknownKeys = true }

    data class Entry(val offsetSec: Int, val artist: String, val title: String) {
        /** «1:02:03» для длинных миксов, иначе «12:34». */
        val stamp: String
            get() {
                val h = offsetSec / 3600
                val m = (offsetSec % 3600) / 60
                val s = offsetSec % 60
                return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
            }
    }

    /** Треклист по ссылке: сам решает, какой источник применим. */
    suspend fun forUrl(url: String, description: String? = null): List<Entry> {
        bbcPid(url)?.let { pid ->
            val bbc = withTimeoutOrNull(12_000) { runCatching { fromBbc(pid) }.getOrNull() }.orEmpty()
            if (bbc.isNotEmpty()) return bbc
        }
        return description?.let { fromDescription(it) }.orEmpty()
    }

    /** `bbc.co.uk/programmes/<pid>` или `/sounds/play/<pid>`. */
    private fun bbcPid(url: String): String? =
        Regex("""bbc\.co\.uk/(?:programmes|sounds/play)/([a-z0-9]{6,})""")
            .find(url)?.groupValues?.get(1)

    /** Официальная разметка BBC: смещение + исполнитель + название. */
    suspend fun fromBbc(pid: String): List<Entry> {
        val body = get("https://www.bbc.co.uk/programmes/$pid/segments.json")
        val events = json.parseToJsonElement(body).jsonObject["segment_events"]?.jsonArray
            ?: return emptyList()
        return events.mapNotNull { el ->
            runCatching {
                val e = el.jsonObject
                val seg = e["segment"]?.jsonObject ?: return@runCatching null
                val title = seg["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (title.isBlank()) return@runCatching null
                Entry(
                    offsetSec = e["version_offset"]?.jsonPrimitive?.intOrNull
                        ?: e["offset"]?.jsonPrimitive?.intOrNull ?: 0,
                    artist = seg["primary_contributor"]?.jsonObject
                        ?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    title = title,
                )
            }.getOrNull()
        }
    }

    // «12:34 Artist — Title», «1:02:03 - Artist – Title», с любым разделителем.
    private val TC = Regex(
        """(?m)^\s*\[?(\d{1,2}:\d{2}(?::\d{2})?)\]?\s*[-–—.)|]?\s*(.+?)\s*$""",
    )

    /** Таймкоды из описания (SoundCloud, YouTube — формат один и тот же). */
    fun fromDescription(text: String): List<Entry> {
        if (text.isBlank()) return emptyList()
        return TC.findAll(text).mapNotNull { m ->
            val stamp = m.groupValues[1]
            val rest = m.groupValues[2].trim().trimEnd(' ', '|', '·', '-', '–', '—')
            if (rest.isBlank() || rest.length > 300) return@mapNotNull null
            val parts = stamp.split(':').map { it.toIntOrNull() ?: return@mapNotNull null }
            val sec = when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> return@mapNotNull null
            }
            // «Artist — Title» разделяем, если разделитель есть; иначе всё в title.
            val split = Regex("""\s+[-–—]\s+""").split(rest, limit = 2)
            if (split.size == 2) Entry(sec, split[0].trim(), split[1].trim())
            else Entry(sec, "", rest)
        }.toList()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("tracklist ${r.code}")
            r.body?.string().orEmpty()
        }
    }
}
