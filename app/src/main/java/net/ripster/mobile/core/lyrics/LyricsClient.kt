package net.ripster.mobile.core.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Тексты песен через lrclib.net — свободный público API без ключа. Отдаёт и
 * простой текст, и синхронный (LRC с таймкодами). Сначала точный `/api/get`
 * (артист + название + длительность), при промахе — `/api/search`.
 *
 * Ничего не кэшируется на диск (пока): один трек = один запрос при открытии
 * панели, дальше держится в памяти экрана.
 */
object LyricsClient {

    private const val BASE = "https://lrclib.net"
    private val json = Json { ignoreUnknownKeys = true }

    data class Lyrics(
        val plain: String?,
        /** LRC-строки, отсортированы по времени. Пусто → синхронного нет. */
        val synced: List<Line>,
    ) {
        val isEmpty get() = plain.isNullOrBlank() && synced.isEmpty()
    }

    data class Line(val atMs: Long, val text: String)

    suspend fun fetch(artist: String, title: String, durationSec: Int): Lyrics? =
        withContext(Dispatchers.IO) {
            val exact = runCatching { get(artist, title, durationSec) }.getOrNull()
            if (exact != null && !exact.isEmpty) return@withContext exact
            runCatching { search(artist, title) }.getOrNull()
        }

    private fun get(artist: String, title: String, durationSec: Int): Lyrics? {
        val url = "$BASE/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        RipsterHttp.client.newCall(req(url.toString())).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            return parse(o)
        }
    }

    private fun search(artist: String, title: String): Lyrics? {
        val url = "$BASE/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$artist $title")
            .build()
        RipsterHttp.client.newCall(req(url.toString())).execute().use { r ->
            if (!r.isSuccessful) return null
            val arr = json.parseToJsonElement(r.body?.string().orEmpty()).jsonArray
            for (el in arr) {
                val lyr = parse(el.jsonObject)
                if (!lyr.isEmpty) return lyr
            }
            return null
        }
    }

    private fun parse(o: kotlinx.serialization.json.JsonObject): Lyrics {
        val plain = o["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val syncedRaw = o["syncedLyrics"]?.jsonPrimitive?.contentOrNull
        return Lyrics(plain = plain, synced = syncedRaw?.let(::parseLrc) ?: emptyList())
    }

    private val TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    private fun parseLrc(raw: String): List<Line> {
        val out = ArrayList<Line>()
        for (rawLine in raw.split('\n')) {
            val matches = TAG.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val (mm, ss, frac) = m.destructured
                val ms = mm.toLong() * 60_000 + ss.toLong() * 1_000 +
                    when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out.add(Line(ms, text))
            }
        }
        return out.sortedBy { it.atMs }
    }

    private fun req(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "RipsterMobile/0.1 (https://github.com/)")
        .build()
}
