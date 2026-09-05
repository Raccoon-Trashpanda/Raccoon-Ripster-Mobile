package net.ripster.mobile.service.soundcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Выбор потока SoundCloud — по качеству звука, а не по удобству контейнера.
 *
 * Было наоборот: mp3 получал +40 «за совместимость», aac +30, progressive ещё
 * +20 сверху. У трека, где сервис отдаёт и aac_160k, и mp3_0_0, телефон
 * сознательно брал MP3 128. Замер 05.09.2026 по треку nWu: доступны aac_160k,
 * aac_96k, mp3_0_0 — и уезжал mp3.
 *
 * Здесь закрепляется и обратное: aac_96k хуже mp3_128, и «aac» само по себе
 * не должно давать преимущества — значение имеет число.
 */
class SoundCloudPresetTest {

    private val client = SoundCloudClient(
        oauthToken = null,
        cacheDir = File(System.getProperty("java.io.tmpdir"), "sc-preset-test").apply { mkdirs() },
    )

    @Test
    fun bitrateIsReadFromTheName() {
        assertEquals(160, client.presetKbps("aac_160k"))
        assertEquals(96, client.presetKbps("aac_96k"))
        assertEquals(256, client.presetKbps("aac_256k"))
    }

    @Test
    fun presetsWithoutANumberUseTheirKnownFixedRate() {
        assertEquals(128, client.presetKbps("mp3_0_0"))
        assertEquals(128, client.presetKbps("mp3_1_0"))
        assertEquals(72, client.presetKbps("opus_0_0"))
    }

    @Test
    fun anUnknownPresetIsNotTreatedAsBad() {
        // «Не знаю» не должно означать «плохой»: новый тир иначе молча уедет
        // в конец списка и никогда не будет выбран.
        assertTrue(client.presetKbps("совершенно_новый_тир") >= 100)
    }

    @Test
    fun aac160BeatsMp3128() {
        assertTrue(client.presetKbps("aac_160k") > client.presetKbps("mp3_0_0"))
    }

    @Test
    fun mp3_128BeatsAac96() {
        // Именно то, что ломает наивное «aac всегда лучше mp3».
        assertTrue(client.presetKbps("mp3_0_0") > client.presetKbps("aac_96k"))
    }

    @Test
    fun opusIsLastButNotExcluded() {
        assertTrue(client.presetKbps("opus_0_0") < client.presetKbps("mp3_0_0"))
        assertTrue(client.presetKbps("opus_0_0") > 0)
    }
}
