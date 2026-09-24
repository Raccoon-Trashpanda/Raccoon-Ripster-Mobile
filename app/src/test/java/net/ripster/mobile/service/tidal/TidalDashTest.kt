package net.ripster.mobile.service.tidal

import net.ripster.mobile.core.errors.EngineErrors
import net.ripster.mobile.core.model.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HI_RES у Tidal приходит MPEG-DASH-манифестом (`application/dash+xml`) с
 * `SegmentTemplate`. Готового парсера под рукой нет — `media3-exoplayer-dash`
 * в этой среде не устанавливается (`dl.google.com` отвечает 404 на любой путь),
 * — поэтому разбор свой и поэтому он под тестами: ошибиться в нём значит молча
 * склеить не тот файл или потерять куски, а оба случая со стороны выглядят как
 * «приложение сломалось», когда виновата одна цифра в шаблоне.
 *
 * Хосты во всех манифестах — `.invalid` (такой домен не резолвится никогда);
 * токенов, подписей и живых ссылок здесь нет ни в одном тексте.
 */
private fun d(xml: String): String = xml.replace("@", "\u0024")

class TidalDashTest {

    private fun outcome(mpd: String): DashOutcome =
        TidalDash.parse(mpd, "https://api.example.invalid/v1/tracks/42/playbackinfopostpaywall")

    private fun segments(mpd: String): DashSegments {
        val o = outcome(mpd)
        if (o !is DashOutcome.Segments) {
            throw AssertionError(
                "манифест не разобран: " +
                    (o as? DashOutcome.Rejected)?.let { "${it.reason} ${it.detail}" },
            )
        }
        return o.plan
    }

    /**
     * Таймлайн Tidal-вида: `r="2"` (три куска), одиночный `S`, и последний
     * `r="-1"` — «до конца периода». timescale = частота дискретизации, как у
     * FLAC-в-mp4. Знак `@` здесь — доллар шаблона DASH: в сырой строке Kotlin
     * `\$` не экранируется (компилятор всё равно видит шаблон), а `${'$'}`
     * запрещён стражем EscapedTemplateTest.
     */
    private val timelineMpd = d("""
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"
             mediaPresentationDuration="PT1M33.600S" minBufferTime="PT1.5S"
             profiles="urn:mpeg:dash:profile:isoff-live:2011">
          <Period id="0" duration="PT1M33.600S" start="PT0.0S">
            <AdaptationSet segmentAlignment="true" mimeType="audio/mp4">
              <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
              <BaseURL>https://audio.example.invalid/medianext/1/</BaseURL>
              <SegmentTemplate timescale="192000" startNumber="1"
                               initialization="@RepresentationID@/init.flac"
                               media="@RepresentationID@/@Number%05d@.m4s">
                <SegmentTimeline>
                  <S t="0" d="1843200" r="2"/>
                  <S t="5529600" d="1843200"/>
                  <S t="7372800" d="1843200" r="-1"/>
                </SegmentTimeline>
              </SegmentTemplate>
              <Representation id="flac-24-192" bandwidth="9216000" codecs="fLaC" audioSamplingRate="192000"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent())

    // --- SegmentTimeline: S@t, S@d, S@r ---

    @Test
    fun `timeline разворачивается в полный список кусков`() {
        val plan = segments(timelineMpd)
        // 93.6 c периода, по 9.6 c на кусок: 3 (r="2") + 1 + 5 (r="-1" до конца
        // периода; девятый ещё влезает, десятый — уже за границу).
        assertEquals(9, plan.mediaUrls.size)
        assertTrue("init-сегмент потерян: ${plan.initUrl}", plan.initUrl.endsWith("flac-24-192/init.flac"))
        // $Number%05d$ — ведущие нули обязательны, без них CDN отдаёт 404 на
        // каждом куске, и выглядит это как «протухшая ссылка».
        assertEquals("https://audio.example.invalid/medianext/1/flac-24-192/00001.m4s", plan.mediaUrls.first())
        assertEquals("https://audio.example.invalid/medianext/1/flac-24-192/00009.m4s", plan.mediaUrls.last())
        assertEquals("flac-24-192", plan.representationId)
        assertEquals(9_216_000L, plan.bandwidth)
        assertTrue("выбранная дорожка обязана быть FLAC", plan.flac)
    }

    @Test
    fun `r=-1 без ориентира — честный отказ, а не догадка`() {
        val mpd = timelineMpd
            .replace("mediaPresentationDuration=\"PT1M33.600S\"", "")
            .replace("duration=\"PT1M33.600S\"", "")
        // Последний S с r="-1" не имеет ни следующего S@t, ни длительности
        // периода: границу повтора восстановить нечем. Взять «сколько вышло» —
        // значит обрезать трек и выдать обрезок за целый.
        val o = outcome(mpd)
        assertTrue("ожидался отказ, получен ${o::class.simpleName}", o is DashOutcome.Rejected)
        o as DashOutcome.Rejected
        assertEquals(EngineErrors.DASH_UNSUPPORTED, o.reason)
        assertTrue("деталь не названа: ${o.detail}", o.detail.contains("r=-1"))
    }

    // --- startNumber, $Number$, $Bandwidth$ ---

    @Test
    fun `startNumber и подстановка номера без форматирования`() {
        val mpd = d("""
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT30S" type="static">
              <Period>
                <AdaptationSet>
                  <SegmentTemplate timescale="1000" duration="10000" startNumber="7"
                                   initialization="i-@Bandwidth@.mp4" media="seg-@Number@.m4s"/>
                  <Representation id="a" bandwidth="256000" codecs="mp4a.40.2"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent())
        val o = TidalDash.parse(mpd, "https://api.example.invalid/mpd")
        assertTrue("нарезка по duration не разобрана: $o", o is DashOutcome.Segments)
        val plan = (o as DashOutcome.Segments).plan
        // 30 c / 10 c = 3 куска, нумерация от startNumber=7.
        assertEquals(3, plan.mediaUrls.size)
        assertEquals(
            listOf("seg-7.m4s", "seg-8.m4s", "seg-9.m4s"),
            plan.mediaUrls.map { it.substringAfterLast('/') },
        )
        assertEquals("i-256000.mp4", plan.initUrl.substringAfterLast('/'))
        assertFalse("AAC-дорожка не должна считаться FLAC", plan.flac)
    }

    @Test
    fun `Time и печатный доллар в шаблоне`() {
        val sub = TidalDash.sub(
            d("t/@Time@/@Number@@/x.m4s"),
            repId = "r", bandwidth = 1000L, number = 12, time = 5529600L,
        )
        // `$$` — намеренный знак доллара в имени (literal-dollar-ok), не шаблон.
        assertEquals("t/5529600/12\u0024/x.m4s", sub)
        assertNull(
            "неизвестную подстановку нельзя раскрывать",
            TidalDash.sub(d("a/@Bitrate@.m4s"), "r", 1L, 1, 1L),
        )
    }

    @Test
    fun `неизвестная подстановка в манифесте — отказ`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT10S" type="static">
              <Period><AdaptationSet>
                <SegmentTemplate timescale="1000" duration="5000" media="@Bitrate@/@Number@.m4s"
                                 initialization="init.mp4"/>
                <Representation id="a" bandwidth="100" codecs="fLaC"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent())
        val o = outcome(mpd)
        assertTrue("неизвестная подстановка проглочена молча: $o", o is DashOutcome.Rejected)
    }

    // --- BaseURL ---

    @Test
    fun `цепочка BaseURL склеивается от MPD до AdaptationSet`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT20S" type="static">
              <BaseURL>https://cdn.example.invalid/a/</BaseURL>
              <Period>
                <BaseURL>b/</BaseURL>
                <AdaptationSet>
                  <BaseURL>c/</BaseURL>
                  <SegmentTemplate timescale="1000" duration="5000" media="m-@Number@.m4s" initialization="init.mp4"/>
                  <Representation id="r" bandwidth="1" codecs="fLaC"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent())
        val plan = segments(mpd)
        assertEquals(4, plan.mediaUrls.size)
        assertEquals("https://cdn.example.invalid/a/b/c/m-1.m4s", plan.mediaUrls.first())
        assertEquals("https://cdn.example.invalid/a/b/c/init.mp4", plan.initUrl)
    }

    @Test
    fun `абсолютный BaseURL у Representation перебивает родителя`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT10S" type="static">
              <BaseURL>https://old.example.invalid/</BaseURL>
              <Period><AdaptationSet>
                <SegmentTemplate timescale="1000" duration="5000" media="m-@Number@.m4s" initialization="init.mp4"/>
                <Representation id="r" bandwidth="1" codecs="fLaC">
                  <BaseURL>https://new.example.invalid/x/</BaseURL>
                </Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent())
        assertEquals("https://new.example.invalid/x/m-1.m4s", segments(mpd).mediaUrls.first())
    }

    @Test
    fun `путь от корня авторитета не наследует каталог базы`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT10S" type="static">
              <BaseURL>https://cdn.example.invalid/deep/dir/</BaseURL>
              <Period><AdaptationSet>
                <SegmentTemplate timescale="1000" duration="5000" media="/root/m-@Number@.m4s" initialization="init.mp4"/>
                <Representation id="r" bandwidth="1" codecs="fLaC"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent())
        assertEquals("https://cdn.example.invalid/root/m-1.m4s", segments(mpd).mediaUrls.first())
    }

    @Test
    fun `без базы и без адреса манифеста относительная ссылка не выдумывается`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT10S" type="static">
              <Period><AdaptationSet>
                <SegmentTemplate timescale="1000" duration="5000" media="m-@Number@.m4s" initialization="init.mp4"/>
                <Representation id="r" bandwidth="1" codecs="fLaC"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent())
        val o = TidalDash.parse(mpd, manifestUrl = null)
        // «m-1.m4s» без базы OkHttp не возьмёт; отдать её плееру — значит
        // получить пустую загрузку без имени причины.
        assertTrue("относительную ссылку приняли за готовую: $o", o is DashOutcome.Rejected)
    }

    // --- несколько Representation ---

    @Test
    fun `из нескольких дорожек выбирается FLAC, а не самый жирный битрейт`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT10S" type="static">
              <BaseURL>https://cdn.example.invalid/</BaseURL>
              <Period><AdaptationSet mimeType="audio/mp4">
                <SegmentTemplate timescale="1000" duration="5000" media="@RepresentationID@/@Number@.m4s"
                                 initialization="@RepresentationID@/init.mp4"/>
                <Representation id="aac-hi" bandwidth="320000" codecs="mp4a.40.2"/>
                <Representation id="flac-thin" bandwidth="700000" codecs="fLaC"/>
                <Representation id="flac-fat" bandwidth="9216000" codecs="fLaC"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent())
        val plan = segments(mpd)
        // Порядок важности: lossless > битрейт. Среди FLAC — жирнейший.
        assertEquals("flac-fat", plan.representationId)
        assertEquals("https://cdn.example.invalid/flac-fat/1.m4s", plan.mediaUrls.first())
        assertTrue(plan.flac)
    }

    @Test
    fun `адапсет субтитров не участвует в выборе`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT10S" type="static">
              <BaseURL>https://cdn.example.invalid/</BaseURL>
              <Period>
                <AdaptationSet type="SUBTITLE" codecs="stpp">
                  <SegmentTemplate timescale="1000" duration="5000" media="sub/@Number@.m4s" initialization="sub/i.mp4"/>
                  <Representation id="s" bandwidth="99000000" codecs="stpp"/>
                </AdaptationSet>
                <AdaptationSet type="AUDIO" codecs="fLaC">
                  <SegmentTemplate timescale="1000" duration="5000" media="a/@Number@.m4s" initialization="a/i.mp4"/>
                  <Representation id="a" bandwidth="1000" codecs="fLaC"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent())
        assertEquals("a", segments(mpd).representationId)
    }

    // --- то, на чём старый разбор ломался ---

    @Test
    fun `BaseURL вместе с SegmentTemplate не считается цельным файлом`() {
        // Единственный <BaseURL> без нарезки — это и есть файл (так Tidal отдаёт
        // обычный lossless-в-DASH). Старый parseDash верил этому признаку ВСЕГДА
        // и возвращал пустой список media-сегментов, даже когда нарезка в
        // манифесте была: плеер получал один заголовок и жаловался на «malformed
        // content», а HI_RES уезжал со стрима на вынужденный откат.
        val plan = segments(timelineMpd)
        assertTrue("media-сегменты потеряны", plan.mediaUrls.isNotEmpty())
        assertTrue(plan.mediaUrls.all { it.startsWith("https://audio.example.invalid/") })
    }

    @Test
    fun `цельный файл без нарезки остаётся цельным файлом`() {
        val mpd = """
            <MPD type="static">
              <Period><AdaptationSet mimeType="audio/mp4">
                <BaseURL>https://audio.example.invalid/whole/track.flac</BaseURL>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val o = outcome(mpd)
        assertTrue("ожидался WholeFile, получен ${o::class.simpleName}", o is DashOutcome.WholeFile)
        assertEquals("https://audio.example.invalid/whole/track.flac", (o as DashOutcome.WholeFile).url)
    }

    @Test
    fun `префиксы пространства имён не ломают разбор`() {
        val mpd = d("""
            <mpd:MPD xmlns:mpd="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT10S">
              <mpd:Period><mpd:AdaptationSet>
                <mpd:BaseURL>https://cdn.example.invalid/</mpd:BaseURL>
                <mpd:SegmentTemplate timescale="1000" duration="5000" media="m-@Number@.m4s" initialization="i.mp4"/>
                <mpd:Representation id="r" bandwidth="1" codecs="fLaC"/>
              </mpd:AdaptationSet></mpd:Period>
            </mpd:MPD>
        """.trimIndent())
        assertEquals("https://cdn.example.invalid/m-1.m4s", segments(mpd).mediaUrls.first())
    }

    // --- честные отказы ---

    @Test
    fun `drm-манифест называется drm-ошибкой, а не пустым ответом`() {
        val mpd = d("""
            <MPD mediaPresentationDuration="PT10S" type="static">
              <Period><AdaptationSet>
                <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                <SegmentTemplate timescale="1000" duration="5000" media="m-@Number@.m4s" initialization="i.mp4"/>
                <Representation id="r" bandwidth="1" codecs="fLaC"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent())
        val o = outcome(mpd)
        assertTrue(o is DashOutcome.Rejected)
        assertEquals(EngineErrors.DRM_UNSUPPORTED, (o as DashOutcome.Rejected).reason)
    }

    @Test
    fun `SegmentList вместо SegmentTemplate — именованная нехватка`() {
        val mpd = """
            <MPD mediaPresentationDuration="PT10S" type="static">
              <Period><AdaptationSet>
                <BaseURL>https://cdn.example.invalid/</BaseURL>
                <Representation id="r" bandwidth="1" codecs="fLaC">
                  <SegmentList duration="10">
                    <Initialization sourceURL="i.mp4"/>
                    <SegmentURL media="m1.m4s"/>
                  </SegmentList>
                </Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val o = outcome(mpd)
        assertTrue("SegmentList прошёл как разобранное: $o", o is DashOutcome.Rejected)
        o as DashOutcome.Rejected
        assertEquals(EngineErrors.DASH_UNSUPPORTED, o.reason)
        assertTrue("деталь не названа: ${o.detail}", o.detail.contains("SegmentList"))
    }

    @Test
    fun `мусор вместо манифеста не превращается в успех`() {
        assertTrue(outcome("<html>403 Forbidden</html>") is DashOutcome.Rejected)
        assertTrue(outcome("") is DashOutcome.Rejected)
    }

    // --- маршрут манифеста: старый путь BTS не тронут ---

    private val btsJson = """
        {"mimeType":"application/vnd.tidal.bts",
         "urls":["https://audio.example.invalid/bts/track.flac"]}
    """.trimIndent()

    @Test
    fun `BTS-манифест идёт прежним прямым путём`() {
        val m = TidalClient.decodeManifest("application/vnd.tidal.bts", btsJson)
        // Разбор DASH не должен к BTS даже прикоснуться: у прямого URL своя
        // жизнь (он играется и качается целиком), и любая «помощь» здесь ломает
        // то, что у тестеров работает годами.
        assertTrue("BTS уехал не в Direct: $m", m is TidalManifest.Direct)
        assertEquals("https://audio.example.invalid/bts/track.flac", (m as TidalManifest.Direct).url)
    }

    @Test
    fun `BTS без ссылок — отказ, а не пустой список`() {
        val m = TidalClient.decodeManifest(
            "application/vnd.tidal.bts",
            """{"mimeType":"application/vnd.tidal.bts","urls":[]}""",
        )
        assertTrue(m is TidalManifest.Rejected)
        assertEquals(EngineErrors.EMPTY_STREAM, (m as TidalManifest.Rejected).reason)
    }

    @Test
    fun `dash+xml с таймлайном уезжает в список сегментов`() {
        val m = TidalClient.decodeManifest("application/dash+xml", timelineMpd, "https://api.example.invalid/mpd")
        assertTrue("DASH не доехал до сегментов: $m", m is TidalManifest.Segments)
        assertEquals(9, (m as TidalManifest.Segments).plan.mediaUrls.size)
    }

    @Test
    fun `неизвестный mime не выдаётся за DASH`() {
        assertNull(TidalClient.decodeManifest("application/octet-stream", "{\"urls\":[]}"))
    }

    // --- подпись качества по факту, а не по запросу ---

    private val hires = QualityTier("flac_24", "FLAC Hi-Res", lossless = true, container = "flac", bitDepth = 24)

    @Test
    fun `hi-res-подпись остаётся hi-res только когда дорожка правда FLAC`() {
        val flacPlan = DashSegments("https://a.invalid/i", listOf("https://a.invalid/1"), "r", 9216000L, "fLaC")
        assertEquals(hires, TidalClient.tierForDash(hires, flacPlan))

        val aacPlan = DashSegments("https://a.invalid/i", listOf("https://a.invalid/1"), "r", 256000L, "mp4a.40.2")
        val real = TidalClient.tierForDash(hires, aacPlan)
        // Tidal отвечает audioQuality=HI_RES_LOSSLESS и на манифестах без
        // FLAC-дорожки. Оставить подпись «FLAC Hi-Res» значило бы соврать о
        // файле, который человек слушает и качает.
        assertEquals("flac", hires.container)
        assertFalse("lossless там, где AAC", real.lossless)
        assertEquals("m4a", real.container)
        assertEquals(256, real.bitrateKbps)
        assertTrue("реальный битрейт не показан: ${real.label}", real.label.contains("256"))
    }

    // --- реестр планов для плеера ---

    @Test
    fun `план возвращается по ключу из фрагмента url`() {
        val plan = segments(timelineMpd)
        val key = TidalDashRegistry.put(plan)
        val url = TidalDashRegistry.tag(plan.initUrl, key)
        assertEquals(key, TidalDashRegistry.keyOf(url.substringAfter('#')))
        assertEquals(plan, TidalDashRegistry.peek(key))
        assertNull("чужую метку приняли за DASH", TidalDashRegistry.keyOf("dzbf=12345"))
    }

    @Test
    fun `длительность из mediaPresentationDuration читается целиком`() {
        assertEquals(3723.45, TidalDash.iso8601Seconds("PT1H2M3.45S")!!, 0.001)
        assertEquals(93.6, TidalDash.iso8601Seconds("PT1M33.600S")!!, 0.001)
        assertNull(TidalDash.iso8601Seconds("PT"))
        assertNull(TidalDash.iso8601Seconds("3723"))
    }
}
