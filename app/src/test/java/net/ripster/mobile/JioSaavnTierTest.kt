package net.ripster.mobile

import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.service.jiosaavn.JioSaavnClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «24» в `flac_24` — это БИТЫ, а не килобиты.
 *
 * Живой случай 22.09.2026. Владелец включил JioSaavn в эмуляторе и сказал:
 * «какое качество тянется — 96 кбпс там». Лестница 320→160→96 при этом была
 * написана верно, и подстановка ступени в имя файла тоже.
 *
 * Ломалось раньше. `desiredKbps()` идёт по общему списку предпочтений проекта
 * `QualityTier.DEFAULT_PREFERENCE`, а он начинается с `flac_24`. Разбор
 * `forcedKbps()` выдёргивал ЛЮБОЕ число после подчёркивания и читал его как
 * битрейт — получалось «хочу 24 кбит/с». Ни одна ступень под `<= 24` не
 * подходила, цикл не находил ничего, срабатывал запасной путь `ladder.last()`,
 * и телефон всегда играл 96.
 *
 * Симптом коварен тем, что выглядит как «сервис не отдаёт больше», хотя 320
 * лежит и доступен: у трека стоит каталожный флаг `320kbps=true`.
 *
 * Поэтому проверяем не «лестницу вообще», а именно разбор предпочтений: что
 * чужие идентификаторы не читаются как наш битрейт и что просьба о lossless,
 * которого у сервиса нет, честно приводит к потолку 320, а не к полу 96.
 */
class JioSaavnTierTest {

    private val client = JioSaavnClient(File(System.getProperty("java.io.tmpdir")!!))

    @Test
    fun `flac_24 is a bit depth and must not be read as a bitrate`() {
        assertNull("flac_24 — 24 бита, не 24 кбит/с", client.forcedKbps("flac_24"))
        assertNull("flac_16 — 16 бит", client.forcedKbps("flac_16"))
        assertNull("mp3_320 — чужой id, не наш тир", client.forcedKbps("mp3_320"))
    }

    @Test
    fun `our own tier ids still parse`() {
        assertEquals(320, client.forcedKbps("aac_320"))
        assertEquals(160, client.forcedKbps("aac_160"))
        assertEquals(96, client.forcedKbps("aac_96"))
        assertEquals(320, client.forcedKbps("AAC_320"))
    }

    @Test
    fun `the project default preference asks for our ceiling, not our floor`() {
        // Ровно тот список, что приходит из плеера и очереди по умолчанию.
        assertEquals(
            "по умолчанию должен выбираться потолок 320",
            320,
            client.desiredKbps(QualityTier.DEFAULT_PREFERENCE),
        )
    }

    @Test
    fun `asking for lossless gives the ceiling, never the floor`() {
        // Lossless у JioSaavn нет вовсе. Честный ответ — лучшее, что есть,
        // а не худшее: 96 здесь был бы наказанием за высокие запросы.
        for (id in listOf("flac", "flac_24", "lossless", "hires", "hifi")) {
            assertEquals("«$id» → 320", 320, client.desiredKbps(listOf(id)))
        }
    }

    @Test
    fun `an explicit lower tier is still honoured`() {
        // Обратная сторона: если человек ВЫБРАЛ 160, потолок навязывать нельзя.
        assertEquals(160, client.desiredKbps(listOf("aac_160")))
        assertEquals(96, client.desiredKbps(listOf("aac_96")))
    }

    @Test
    fun `an empty or unknown preference falls back to the ceiling`() {
        assertEquals(320, client.desiredKbps(emptyList()))
        assertEquals(320, client.desiredKbps(listOf("что-то незнакомое")))
    }

    // ── сама лестница: спуск есть, подъёма нет ──────────────────────────────

    private val base = "https://aac.saavncdn.com/405/deadbeefdeadbeef_96.mp4"

    @Test
    fun `a track without 320 steps down to 160 instead of failing`() {
        // единственный способ увидеть «спустились»: каталог вечно рапортует
        // 320kbps=true, поэтому ветку проверяем на искусственном served().
        val served = { url: String -> !url.endsWith("_320.mp4") }
        val (tier, url) = client.pickTier(base, 320, served)
        assertEquals(160, tier.bitrateKbps)
        assertTrue(url.endsWith("_160.mp4"))
    }

    @Test
    fun `a track with only 96 steps down twice, never up`() {
        val served = { url: String -> url.endsWith("_96.mp4") }
        val (tier, url) = client.pickTier(base, 320, served)
        assertEquals(96, tier.bitrateKbps)
        assertTrue(url.endsWith("_96.mp4"))
    }

    @Test
    fun `asking for less than the ceiling does not quietly buy more`() {
        // Все ступени живы, просят 160 — берут 160. Иначе «показанное качество»
        // расходилось бы с файлом, и подпись в MP4 врала бы человеку.
        for ((want, got) in listOf(320 to 320, 160 to 160, 96 to 96)) {
            val (tier, url) = client.pickTier(base, want) { true }
            assertEquals("просили $want", got, tier.bitrateKbps)
            assertTrue(url.endsWith("_$got.mp4"))
        }
    }

    @Test
    fun `catalog flag decides which tiers the track advertises`() {
        assertEquals(listOf(320, 160, 96), client.tiersFor("true").map { it.bitrateKbps })
        assertEquals(listOf(160, 96), client.tiersFor("false").map { it.bitrateKbps })
    }
}
