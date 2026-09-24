package net.ripster.mobile.service.tidal

import net.ripster.mobile.core.errors.EngineErrors
import kotlin.math.ceil

/**
 * Развёрнутый по шаблону план DASH-потока: init-сегмент и ВСЕ media-сегменты
 * по порядку, уже абсолютными ссылками.
 *
 * [representationId]/[bandwidth]/[codec] — что именно из манифеста выбрано.
 * Нужно для честной подписи: человек должен видеть реальную дорожку, а не ту,
 * которую мы попросили у Tidal.
 */
internal data class DashSegments(
    val initUrl: String,
    val mediaUrls: List<String>,
    val representationId: String,
    val bandwidth: Long,
    val codec: String,
) {
    val flac: Boolean get() = TidalDash.isFlacCodec(codec)
}

/** Результат разбора MPD. Всегда один из трёх — «разобрали как-нибудь» не бывает. */
internal sealed interface DashOutcome {
    data class Segments(val plan: DashSegments) : DashOutcome
    /** Манифест указывает на ЦЕЛЫЙ файл (BaseURL без нарезки). */
    data class WholeFile(val url: String) : DashOutcome
    /**
     * [reason] — маркер [EngineErrors] (что показать человеку), [detail] —
     * техническая причина для разбора (что именно в MPD нас остановило).
     */
    data class Rejected(val reason: String, val detail: String = "") : DashOutcome
}

/**
 * Минимальный парсер MPEG-DASH (MPD) с `SegmentTemplate`.
 *
 * Почему свой, а не `media3-exoplayer-dash`: в этой среде `dl.google.com`
 * отвечает 404 на любой путь, и media3-dash не приезжает в офлайн-кэш (в кэше
 * лежат только media3-exoplayer/-hls/-common/-datasource-okhttp), то есть
 * готовый DASH-парсер недоступен без новой зависимости — а запрет на новые
 * AndroidX-артефакты стоит в правилах проекта. Поэтому манифест разворачивается
 * в УПОРЯДОЧЕННЫЙ список ссылок, а склейку их в один поток делает
 * `player/RipsterDataSource` (список он получает из [TidalDashRegistry]) — ровно
 * так же, как скачивание склеивает сегменты в файл.
 *
 * Охват — то, что Tidal реально отдаёт для HI_RES/HI_RES_LOSSLESS:
 * `SegmentTemplate` с `SegmentTimeline` (`S@t`/`S@d`/`S@r`, включая `r="-1"`)
 * либо с `duration`/`timescale`; `$Number$`, `$Number%05d$`, `$Time$`,
 * `$RepresentationID$`, `$Bandwidth$`; цепочка `BaseURL`
 * (MPD → Period → AdaptationSet → Representation); несколько `Representation` —
 * берём FLAC с наивысшим bandwidth.
 *
 * Чего парсер НЕ делает «молча»: DRM, `SegmentList`/`SegmentBase`, неизвестные
 * подстановки, `r="-1"` без ориентира, относительные ссылки без базы. Всё это —
 * `Rejected` с именованной причиной: тихий откат на качество ниже хуже честного
 * отказа, а тихая догадка про нарезку даёт битый файл, который выглядит целым.
 */
internal object TidalDash {

    /** Страховка от сломанного `r="-1"`/`duration`: не заводить список на гигабайты. */
    private const val MAX_SEGMENTS = 20_000

    /**
     * @param manifestUrl адрес, по которому получен MPD — нужен для разрешения
     *   относительных `BaseURL` (Tidal почти всегда кладёт абсолютный, но
     *   молча уезжать в относительную ссылку OkHttp нечем).
     */
    fun parse(mpd: String, manifestUrl: String? = null): DashOutcome {
        if (mpd.isBlank()) return DashOutcome.Rejected(EngineErrors.DASH_UNSUPPORTED, "empty MPD")
        if (!hasTag(mpd, "MPD")) return DashOutcome.Rejected(EngineErrors.DASH_UNSUPPORTED, "no MPD root")
        // DRM мы не вскрываем. Отказываемся сразу и по имени: спуск на LOSSLESS
        // здесь был бы подменой — человек заказывал hi-res и получил бы другой
        // файл, не узнав об этом.
        if (drmDeclared(mpd)) return DashOutcome.Rejected(EngineErrors.DRM_UNSUPPORTED, "ContentProtection urn:uuid")

        val mpdTag = attrsOf(mpd, "MPD")
        val mpdTotal = iso8601Seconds(attrOf(mpdTag, "mediaPresentationDuration") ?: attrOf(mpdTag, "duration"))
        val mpdTemplate = element(mpd, "SegmentTemplate")

        val periods = elements(mpd, "Period")
        val candidates = ArrayList<DashSegments>()
        var templateSeen = mpdTemplate != null
        var reject: PlanNo? = null

        for (p in if (periods.isEmpty()) listOf(Tagged(mpdTag, mpd)) else periods) {
            val periodTotal = iso8601Seconds(attrOf(p.attrs, "duration")) ?: mpdTotal
            val sets = elements(p.body.orEmpty(), "AdaptationSet")
            for (set in if (sets.isEmpty()) listOf(Tagged("", p.body.orEmpty())) else sets) {
                if (!isAudio(set.attrs)) continue
                val asTemplate = element(set.body.orEmpty(), "SegmentTemplate")
                templateSeen = templateSeen || asTemplate != null
                val reps = elements(set.body.orEmpty(), "Representation")
                for (r in if (reps.isEmpty()) listOf(Tagged(set.attrs, set.body)) else reps) {
                    val repTemplate = element(r.body.orEmpty(), "SegmentTemplate") ?: asTemplate ?: mpdTemplate
                    templateSeen = templateSeen || repTemplate != null
                    val outcome = plan(
                        template = repTemplate,
                        bases = listOfNotNull(
                            baseUrlOf(mpd), baseUrlOf(p.body), baseUrlOf(set.body), baseUrlOf(r.body),
                        ),
                        manifestUrl = manifestUrl,
                        repId = attrOf(r.attrs, "id").orEmpty(),
                        bandwidth =
                            longOf(attrOf(r.attrs, "bandwidth") ?: attrOf(set.attrs, "bandwidth")) ?: 0L,
                        codec = attrOf(r.attrs, "codecs") ?: attrOf(set.attrs, "codecs").orEmpty(),
                        totalSeconds = periodTotal,
                    )
                    when (outcome) {
                        is PlanOk -> candidates += outcome.segments
                        is PlanNo -> if (outcome.fatal) {
                            return DashOutcome.Rejected(outcome.reason, outcome.detail)
                        } else if (reject == null && outcome.detail.isNotEmpty()) reject = outcome
                    }
                }
            }
        }
        if (candidates.isNotEmpty()) {
            // FLAC важнее bandwidth: lossless-дорожка — то, ради чего HI_RES и
            // просят; среди равных по кодексу — жирнейшая.
            return DashOutcome.Segments(candidates.maxWith(compareBy({ it.flac }, { it.bandwidth })))
        }
        // Единственный BaseURL без всякой нарезки — это и есть цельный файл (так
        // Tidal отдаёт lossless-в-DASH). Раньше эта ветка срабатывала ПОСЛУШНО,
        // даже когда нарезка в манифесте была: media-сегменты терялись, плеер
        // видел только заголовок и жаловался на «malformed content».
        val segmenting = templateSeen || hasTag(mpd, "SegmentList") || hasTag(mpd, "SegmentBase")
        val whole = absoluteBaseUrl(mpd, manifestUrl)
        if (!segmenting && whole != null) return DashOutcome.WholeFile(whole)
        // SegmentList/SegmentBase описывают куски иначе (явным списком ссылок
        // либо байтовыми диапазонами) — разворачивать их нечем.
        val other = listOf("SegmentList", "SegmentBase").firstOrNull { hasTag(mpd, it) }
        return DashOutcome.Rejected(
            EngineErrors.DASH_UNSUPPORTED,
            // Сначала — НАЗВАННЫЙ способ нарезки, если он встретился: «тут
            // SegmentList» полезнее, чем «у дорожки нет SegmentTemplate».
            other?.let { "$it not supported" }
                ?: reject?.detail?.takeIf { it.isNotBlank() }
                ?: if (segmenting) "no usable SegmentTemplate" else "no SegmentTemplate",
        )
    }

    // --- развёртка одного Representation ---

    private sealed interface Plan
    private class PlanOk(val segments: DashSegments) : Plan
    private class PlanNo(val reason: String, val detail: String, val fatal: Boolean) : Plan

    private fun plan(
        template: Tagged?,
        bases: List<String>,
        manifestUrl: String?,
        repId: String,
        bandwidth: Long,
        codec: String,
        totalSeconds: Double?,
    ): Plan {
        if (template == null) return PlanNo(EngineErrors.DASH_UNSUPPORTED, "no SegmentTemplate for '$repId'", false)
        val ta = template.attrs
        val mediaT = attrOf(ta, "media")
            ?: return PlanNo(EngineErrors.DASH_UNSUPPORTED, "SegmentTemplate@media missing", true)
        val initT = attrOf(ta, "initialization")
            ?: return PlanNo(EngineErrors.DASH_UNSUPPORTED, "SegmentTemplate@initialization missing", true)
        val timescale = (longOf(attrOf(ta, "timescale")) ?: 1L).coerceAtLeast(1L)
        val startNumber = (longOf(attrOf(ta, "startNumber")) ?: 1L).toInt()
        val pto = longOf(attrOf(ta, "presentationTimeOffset")) ?: 0L
        val no = { d: String -> PlanNo(EngineErrors.DASH_UNSUPPORTED, d, true) }

        // (номер сегмента, его время в timescale-единицах уже со сдвигом)
        val timeline = element(template.body.orEmpty(), "SegmentTimeline")
        val spans = ArrayList<Pair<Int, Long>>()
        if (timeline != null) {
            val s = elements(timeline.body.orEmpty(), "S")
            if (s.isEmpty()) return no("empty SegmentTimeline")
            var time = longOf(attrOf(s.first().attrs, "t")) ?: 0L
            for ((i, e) in s.withIndex()) {
                val d = longOf(attrOf(e.attrs, "d")) ?: return no("S@d missing")
                if (d <= 0) return no("S@d <= 0")
                longOf(attrOf(e.attrs, "t"))?.let { time = it }
                val r = (longOf(attrOf(e.attrs, "r")) ?: 0L).toInt()
                // r="-1" — «повторять до следующего S@t, а на последнем — до
                // конца периода». Без обоих ориентиров границу не восстановить,
                // и додумывать её значит потерять или удвоить куски.
                val until: Long? = if (r >= 0) null else
                    longOf(attrOf(s.getOrNull(i + 1)?.attrs ?: "", "t"))
                        ?: totalSeconds?.let { (it * timescale).toLong() }
                        ?: return no("S@r=-1 without reference")
                val repeats = if (r >= 0) r + 1 else Int.MAX_VALUE
                var k = 0
                while (k < repeats && (until == null || time + d <= until) && spans.size < MAX_SEGMENTS) {
                    spans += (startNumber + spans.size) to (pto + time)
                    time += d; k++
                }
                if (r < 0 && until != null && spans.size >= MAX_SEGMENTS) return no("segment cap reached")
            }
            if (spans.isEmpty()) return no("timeline gave no segments")
        } else {
            val dur = longOf(attrOf(ta, "duration"))
                ?: return no("no duration and no SegmentTimeline")
            if (dur <= 0) return no("duration <= 0")
            val total = totalSeconds ?: return no("unknown total duration")
            val count = ceil(total * timescale / dur).toInt().coerceAtLeast(1)
            if (count > MAX_SEGMENTS) return no("segment cap reached")
            for (i in 0 until count) spans += (startNumber + i) to (pto + i * dur)
        }

        val media = ArrayList<String>(spans.size)
        for ((num, t) in spans) {
            media += absolute(sub(mediaT, repId, bandwidth, num, t), bases, manifestUrl)
                ?: return no("unusable media url for #$num")
        }
        val initUrl = absolute(sub(initT, repId, bandwidth, startNumber, spans.first().second), bases, manifestUrl)
            ?: return no("unusable initialization url")
        return PlanOk(DashSegments(initUrl, media, repId, bandwidth, codec))
    }

    // --- подстановка шаблона ---

    /**
     * `$Number$`, `$Number%05d$`, `$Time$`, `$RepresentationID$`, `$Bandwidth$`;
     * `$$` — печатный доллар (literal-dollar-ok: знак нужен намеренно).
     *
     * Неизвестная подстановка → null: оставить `$Foo$` в URL значит получить
     * 404 на каждом куске и склеить битый файл, который выглядит целым.
     */
    fun sub(tpl: String, repId: String, bandwidth: Long, number: Int, time: Long): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < tpl.length) {
            val c = tpl[i]
            if (c != '$') { sb.append(c); i++; continue }
            if (i + 1 < tpl.length && tpl[i + 1] == '$') { sb.append('$'); i += 2; continue }
            val close = tpl.indexOf('$', i + 1)
            if (close < 0) { sb.append(c); i++; continue }
            val spec = tpl.substring(i + 1, close)
            val width = spec.substringAfter('%', "").removeSuffix("d").toIntOrNull()
            val value = when (spec.substringBefore('%')) {
                "Number" -> number.toString()
                "Time" -> time.toString()
                "RepresentationID" -> repId
                "Bandwidth" -> bandwidth.toString()
                else -> return null
            }
            sb.append(if (width != null && width > 0) value.padStart(width, '0') else value)
            i = close + 1
        }
        return sb.toString()
    }

    private fun absolute(expanded: String?, bases: List<String>, manifestUrl: String?): String? =
        expanded?.let { url -> resolveUrl(bases, url, manifestUrl)?.takeIf { it.startsWith("http", true) } }

    // --- разбор XML-подобного MPD ---

    /** attrs — содержимое открывающего тега, body — внутренность (null для `/>`). */
    internal class Tagged(val attrs: String, val body: String?)

    /** Элементы с необязательным префиксом пространства имён (`<mpd:S …>`). */
    private fun elements(xml: String?, name: String): List<Tagged> {
        if (xml.isNullOrEmpty()) return emptyList()
        val re = Regex(
            "<(?:[A-Za-z_][\\w.-]*:)?$name\\b([^>]*?)(/>|>(.*?)</(?:[A-Za-z_][\\w.-]*:)?$name\\s*)",
            RegexOption.DOT_MATCHES_ALL,
        )
        return re.findAll(xml).map { m ->
            Tagged(m.groupValues[1], if (m.groupValues[2] == "/>") null else m.groupValues[3])
        }.toList()
    }

    private fun element(xml: String?, name: String): Tagged? = elements(xml, name).firstOrNull()

    private fun hasTag(xml: String, name: String): Boolean =
        Regex("<(?:[A-Za-z_][\\w.-]*:)?$name\\b").containsMatchIn(xml)

    private fun attrsOf(xml: String, name: String): String = element(xml, name)?.attrs ?: ""

    private fun attrOf(attrs: String?, name: String): String? =
        attrs?.let { Regex("""\b$name\s*=\s*"([^"]*)"""").find(it)?.groupValues?.get(1) }

    private fun baseUrlOf(scope: String?): String? =
        element(scope, "BaseURL")?.body?.trim()?.takeIf { it.isNotEmpty() }

    /** Без `type` считаем дорожку аудио — так делает Tidal; субтитры/видео отсекаются по типу. */
    private fun isAudio(attrs: String): Boolean {
        val type = attrOf(attrs, "type") ?: return true
        return type.equals("AUDIO", true)
    }

    /** Tidal объявляет DRM как `urn:uuid:<systemId>`; `mp4protection` — безобидная сигнатура контейнера. */
    private fun drmDeclared(mpd: String): Boolean =
        elements(mpd, "ContentProtection").any { e ->
            val scheme = attrOf(e.attrs, "schemeIdUri").orEmpty()
            scheme.startsWith("urn:uuid", true) || scheme.contains("cenc", true) ||
                scheme.contains("widevine", true) || scheme.contains("playready", true)
        }

    private fun longOf(v: String?): Long? = v?.trim()?.toDoubleOrNull()?.toLong()

    /** `PT1H2M3.456S` → секунды; `null` — не читается, и тогда не гадаем. */
    fun iso8601Seconds(v: String?): Double? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!s.startsWith("PT", true)) return null
        var total = 0.0
        var matched = false
        for (m in Regex("(\\d+(?:\\.\\d+)?)([HMS])").findAll(s)) {
            matched = true
            total += m.groupValues[1].toDouble() * when (m.groupValues[2]) {
                "H" -> 3600.0; "M" -> 60.0; else -> 1.0
            }
        }
        return if (matched) total else null
    }

    private fun absoluteBaseUrl(mpd: String, manifestUrl: String?): String? =
        resolveUrl(listOfNotNull(baseUrlOf(mpd)), "", manifestUrl)?.takeIf { it.startsWith("http", true) }

    /**
     * Разрешение ссылки относительно ЦЕПОЧКИ баз (MPD → Period → AdaptationSet →
     * Representation) и, в крайнем случае, адреса манифеста. Порядок как для URI:
     * абсолютная ссылка не трогается, `//` — авторитет, `/x` — от корня
     * авторитета, `x` — от каталога базы, база без слэша в конце — «файл», его
     * последний сегмент отбрасывается.
     */
    fun resolveUrl(bases: List<String>, ref: String, manifestUrl: String? = null): String? {
        var cur = manifestUrl?.takeIf { it.isNotBlank() }
        for (b in bases) if (b.isNotBlank()) cur = join(cur, b)
        return join(cur, ref)
    }

    private fun join(base: String?, ref: String): String? = when {
        ref.isBlank() -> base
        ref.startsWith("http://", true) || ref.startsWith("https://", true) -> ref
        ref.startsWith("//") -> "https:$ref"
        base == null -> null
        ref.startsWith("/") -> authorityOf(base)?.let { it + ref }
        base.endsWith("/") -> base + ref
        else -> base.substringBeforeLast('/', authorityOf(base).orEmpty()) + "/" + ref
    }

    private fun authorityOf(url: String): String? =
        Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(url)?.value

    internal fun isFlacCodec(codec: String): Boolean =
        codec.contains("fLaC", true) || codec.contains("flac", true) || codec.contains("alac", true)
}
