package net.ripster.mobile.core.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.ripster.mobile.core.errors.isJobCancellation
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

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

    /**
     * Чем кончился запрос. Раньше у `fetch` был единственный способ сказать
     * «ничего» — вернуть null, и отказ сервиса (нет сети, 429, 5xx) и честное
     * «слов на эту песню у нас нет» сливались в один ответ. На экране это
     * читалось как «у трека нет текста»: человек мирил отсутствие слов там,
     * где надо было просто retry. В UI для этих случаев РАЗНЫЕ строки
     * (`ref.lyrics_failed` и `ref.no_lyrics`), и ветка отказа была мертва —
     * `runCatching` на вызывающей стороне не ловил НИЧЕГО, потому что
     * исключения глотались здесь, внутри.
     */
    sealed interface Reply {
        val lyrics: Lyrics

        data class Found(override val lyrics: Lyrics) : Reply

        /** Сервис ответил: текста нет. Это не ошибка. */
        object Absent : Reply { override val lyrics = Lyrics(null, emptyList()) }

        /** Достучаться не смогли. [reason] — короткий технический факт для строки. */
        data class Failed(val reason: String) : Reply { override val lyrics = Lyrics(null, emptyList()) }
    }

    /**
     * Итог перебора вариантов названия: что-то нашли / ничего нет / бросали
     * ошибки. Чистая функция — потому что ровно здесь и решается, врать ли
     * человеку про «текста нет».
     */
    fun replyFor(hit: Lyrics?, failure: String?): Reply = when {
        hit != null && !hit.isEmpty -> Reply.Found(hit)
        failure != null -> Reply.Failed(failure)
        else -> Reply.Absent
    }

    suspend fun fetch(artist: String, title: String, durationSec: Int): Reply =
        withContext(Dispatchers.IO) {
            var failure: String? = null
            for (t in titleVariants(title)) {
                // Пустой ответ = «нет такого»; исключение = «не сумели». Не
                // путаем их: `get`/`search` бросают на.transport-сбоях.
                val exact = runCatching { get(artist, t, durationSec) }
                if (exact.isSuccess) {
                    val found = exact.getOrNull()
                    if (found != null && !found.isEmpty) return@withContext Reply.Found(found)
                } else {
                    failure = whyFailed(exact.exceptionOrNull())
                }
                val search = runCatching { search(artist, t) }
                if (search.isSuccess) {
                    val found = search.getOrNull()
                    if (found != null && !found.isEmpty) return@withContext Reply.Found(found)
                } else {
                    failure = whyFailed(search.exceptionOrNull())
                }
            }
            replyFor(null, failure)
        }

    /** Отменённую работу не превращаем в «не удалось достучаться». */
    private fun whyFailed(t: Throwable?): String {
        if (t == null) return "?"
        if (isJobCancellation(t)) throw t
        return t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    /**
     * Варианты названия для запроса, от полного к обрезанному. При импорте
     * релиз налипает на имя трека («Bare - Redux» у трека «Bare»), и суффикс
     * убивает совпадение в LRCLIB: и /api/get, и /api/search дают пустоту,
     * хотя по «Bare» лежит синхронный текст. Срезаем хвост после тире, затем
     * хвост в скобках; дубликаты и пустые не отдаём. Обрезанные варианты идут
     * ПОСЛЕ полного — легитимные названия вида «X - Y» не теряются.
     */
    fun titleVariants(title: String): List<String> {
        val out = LinkedHashSet<String>()
        out += title.trim()
        var cut = title.replace(Regex("""\s+[-–—]\s+.*$"""), "").trim()
        if (cut.isNotEmpty()) out += cut
        cut = cut.replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim()
        if (cut.isNotEmpty()) out += cut
        return out.toList()
    }

    /** Ответ LRCLIB «трека нет в базе». Отдельной функцией — чтобы тест держал контракт. */
    fun isMiss(code: Int): Boolean = code == 404

    private fun get(artist: String, title: String, durationSec: Int): Lyrics? {
        val url = "$BASE/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .apply { if (durationSec > 0) addQueryParameter("duration", durationSec.toString()) }
            .build()
        RipsterHttp.client.newCall(req(url.toString())).execute().use { r ->
            // 404 у LRCLIB — это «такого трека в базе нет» ({"name":"TrackNotFound"}),
            // то есть честный промах, а не сбой. 23.09.2026 он показывался как
            // «сервис не ответил, попробуй позже» — проверено живым запросом.
            if (isMiss(r.code)) return null
            // Прочие не-2xx — СБОЙ, а не «текста нет»: 429 у LRCLIB рядовое дело.
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val el = json.parseToJsonElement(r.body?.string().orEmpty())
            // На промах /api/get отдаёт JSON-`null`. Прежний `null.jsonObject`
            // бросался, и честный промах записывался как сбой.
            if (el !is JsonObject) return null
            return parse(el)
        }
    }

    private fun search(artist: String, title: String): Lyrics? {
        val url = "$BASE/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$artist $title")
            .build()
        RipsterHttp.client.newCall(req(url.toString())).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val el = json.parseToJsonElement(r.body?.string().orEmpty())
            if (el !is JsonArray) return null
            for (item in el) {
                val o = item as? JsonObject ?: continue
                val lyr = parse(o)
                if (lyr.isEmpty) continue
                // Поиск размытый: `q` — просто набор слов, и первым часто
                // прилетает ЧУЖАЯ песня с похожим названием. Раньше брали
                // первую непустую — и под своим треком человек читал слова
                // другого артиста. Лучше пустая панель, чем враньё.
                if (!accepts(
                        o["title"]?.jsonPrimitive?.contentOrNull,
                        o["artistName"]?.jsonPrimitive?.contentOrNull,
                        artist, title,
                    )
                ) continue
                return lyr
            }
            return null
        }
    }

    /**
     * Та ли это песня.
     *
     * Артист — строгая проверка (именно чужие тексты и подмешивал размытый
     * поиск). Название — пересечение: у LRCLIB «Bare» лежит и как «Bare», и как
     * «Bare (Redux)», и наоборот, а строгое равенство выбросило бы половину
     * легитимных находок. Пустое поле у находки — «сервис не сказал»: спорить
     * нечем, пропускаем.
     */
    fun accepts(itemTitle: String?, itemArtist: String?, wantArtist: String, wantTitle: String): Boolean {
        val a = name(itemArtist)
        if (a.isNotEmpty() && a != name(wantArtist)) return false
        val t = name(itemTitle)
        if (t.isEmpty()) return true
        val want = name(wantTitle)
        return t == want || t.contains(want) || want.contains(t)
    }

    private fun name(s: String?): String =
        s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

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
