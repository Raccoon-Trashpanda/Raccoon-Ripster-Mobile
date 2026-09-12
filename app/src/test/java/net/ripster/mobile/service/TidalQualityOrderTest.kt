package net.ripster.mobile.service

import net.ripster.mobile.service.tidal.TidalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тупик, из-за которого тестеры писали «трансляция с Tidal работает, а загрузка
 * даёт 403».
 *
 * Отказ CDN в сегментах означает не «Tidal сломан», а «этому аккаунту не дают
 * ЭТОТ тир» — что и подтверждает работающая кнопка ▶: плеер берёт качество
 * попроще и играет. Загрузка обязана уметь то же самое.
 *
 * Замер 12.09.2026 на Premium-учётке владельца (NZ, hi-res заявлен): Tidal
 * ответил `audioQuality: LOW` и на `HI_RES_LOSSLESS`, и на `LOSSLESS`, причём
 * одинаково для двух разных client_id. То есть «запрошенный тир не выдаётся» —
 * штатное состояние сервиса, а не край.
 */
class TidalQualityOrderTest {

    @Test
    fun `зафиксированный hi-res оставляет куда спуститься`() {
        // Человек выбрал в настройках именно FLAC 24-bit, CDN отказал в сегментах.
        val keys = TidalClient.qualityKeys(listOf("flac_24"), exclude = setOf("HI_RES_LOSSLESS"))
        assertTrue("после отказа hi-res список не должен быть пустым", keys.isNotEmpty())
        assertEquals("LOSSLESS", keys.first())
    }

    @Test
    fun `обычный путь начинается с того, что просили`() {
        val keys = TidalClient.qualityKeys(listOf("flac_24"))
        assertEquals("HI_RES_LOSSLESS", keys.first())
    }

    @Test
    fun `хвост не поднимает качество выше просьбы`() {
        // Выбран AAC — значит человек экономит место. Отдать ему 24-битный
        // файл было бы не услышать просьбу.
        val keys = TidalClient.qualityKeys(listOf("aac_256"))
        assertEquals("HIGH", keys.first())
        assertTrue("hi-res не должен появляться сам", "HI_RES_LOSSLESS" !in keys)
    }

    @Test
    fun `отказ во всех тирах оставляет пустой список`() {
        // Это единственный случай, когда честно сказать «не дают ничего».
        val keys = TidalClient.qualityKeys(
            listOf("flac_24"),
            exclude = setOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW"),
        )
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `режим прямого стрима не уходит в DASH`() {
        val keys = TidalClient.qualityKeys(listOf("lossless_direct"))
        assertTrue("HI_RES_LOSSLESS" !in keys)
        assertEquals("LOSSLESS", keys.first())
    }

    @Test
    fun `порядок без повторов`() {
        val keys = TidalClient.qualityKeys(listOf("flac_16", "flac_24", "mp3_320"))
        assertEquals(keys.distinct(), keys)
    }
}
