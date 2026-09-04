package net.ripster.mobile.service.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вставленный руками токен Tidal обязан пережить сохранение.
 *
 * 04.09.2026: экран настроек клал в хранилище голый JWT, а читатель разбирал
 * значение только как JSON-блоб. Разбор молча падал, `isConfigured()` возвращал
 * false — учётка числилась «Не подключён», а Tidal вообще исчезал из списка
 * сервисов в поиске. Ни одной ошибки при этом не показывалось: человек вставлял
 * заведомо живой токен и не понимал, почему ничего не изменилось.
 *
 * Токены здесь синтетические: подпись не проверяется, разбирается только payload.
 */
class TidalStoredTest {

    /** claims → JWT с валидным base64url-payload (подпись не важна). */
    private fun jwt(payload: String): String {
        val b64 = { s: String ->
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        }
        return b64("""{"alg":"ES512"}""") + "." + b64(payload) + "." + "c".repeat(120)
    }

    private val refresh = jwt("""{"type":"o2_refresh","uid":209208385,"cc":"NZ","cid":13319}""")
    private val access = jwt("""{"type":"o2_access","uid":209208385,"cc":"NZ","cid":13319}""")

    @Test
    fun bareRefreshTokenIsUnderstood() {
        val s = TidalAuth.decodeStored(refresh)
        assertNotNull("голый refresh-JWT должен разбираться", s)
        assertEquals(refresh, s!!.refreshToken)
        assertTrue("refresh не должен попасть в accessToken", s.accessToken.isBlank())
        assertEquals("NZ", s.countryCode)
    }

    @Test
    fun bareAccessTokenIsUnderstood() {
        val s = TidalAuth.decodeStored(access)
        assertNotNull(s)
        assertEquals(access, s!!.accessToken)
        assertTrue(s.refreshToken.isBlank())
    }

    @Test
    fun jsonBlobStillWins() {
        // Синк с ПК кладёт готовый блоб — его формат трогать нельзя.
        val blob = TidalAuth.encodeAccessToken(refresh)
        assertTrue(blob.trimStart().startsWith("{"))
        val s = TidalAuth.decodeStored(blob)
        assertNotNull(s)
        assertEquals(refresh, s!!.refreshToken)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(TidalAuth.decodeStored(""))
        assertNull(TidalAuth.decodeStored("   "))
        // Не JSON и не разбираемый JWT — учётки нет, и это честнее выдумки.
        assertNull(TidalAuth.decodeStored("просто строка"))
    }
}
