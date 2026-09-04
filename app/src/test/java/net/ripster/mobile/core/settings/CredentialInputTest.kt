package net.ripster.mobile.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Вставка секретов из мессенджера. Проверяем ровно то, из-за чего 03.09.2026
 * потерялись рабочие Tidal-токен и Deezer-ARL: обёртка вокруг значения и
 * обрезка при вставке проходили в стор молча, после чего сервис падал 401, а
 * причину искали в самом токене.
 */
class CredentialInputTest {

    private val arl = "8".repeat(192)
    private val jwt = "eyJhbGciOiJFUzUxMiJ9.eyJ0eXBlIjogIm8yX3JlZnJlc2giLCAidWlkIjogMjA5MjA4Mzg1LCAic2NvcGUiOiAicl91c3Igd191c3IiLCAiY2lkIjogMTMzMTksICJzVmVyIjogMCwgImdWZXIiOiAwLCAiaXNzIjogImh0dHBzOi8vYXV0aC50aWRhbC5jb20vdjEifQ." + "b".repeat(200)

    @Test
    fun stripsMessengerWrapping() {
        assertEquals(arl, CredentialInput.clean("Token ➠  $arl"))
        assertEquals(arl, CredentialInput.clean("ARL: $arl"))
        assertEquals(arl, CredentialInput.clean("arl=$arl"))
        assertEquals(arl, CredentialInput.clean("\"$arl\""))
    }

    @Test
    fun dropsWhitespaceAndNewlines() {
        // Перенос строки в середине — самый частый способ испортить вставку.
        assertEquals(arl, CredentialInput.clean(arl.take(100) + "\n" + arl.drop(100)))
        assertEquals(arl, CredentialInput.clean("  $arl \r\n"))
    }

    @Test
    fun goodValuesPass() {
        assertNull(CredentialInput.problem(CredentialStore.Key.DEEZER_ARL, arl))
        assertNull(CredentialInput.problem(CredentialStore.Key.TIDAL_OAUTH, jwt))
        assertNull(CredentialInput.problem(CredentialStore.Key.QOBUZ_APP_ID, "312369995"))
        assertNull(CredentialInput.problem(CredentialStore.Key.QOBUZ_SECRET, "e79f8b9be485692b0e5f9dd895826368"))
        // JSON-блоб Tidal с ПК — тоже законная форма.
        assertNull(CredentialInput.problem(CredentialStore.Key.TIDAL_OAUTH, """{"refreshToken":"x"}"""))
    }

    /**
     * 04.09.2026, найдено на телефоне владельца: в поле лежала склейка тестовой
     * заглушки `deadbeef123` с настоящим ARL — 203 символа, все шестнадцатеричные.
     * Порог «не короче 128» пропускал её, в учётках горело «Подключён», Deezer
     * сессию не открывал, и человека отправляли переклеивать токен, с которым
     * всё было в порядке. Слишком длинное — такая же порча, как обрезка.
     */
    @Test
    fun arlWithJunkGluedInFrontIsRejected() {
        assertNotNull(CredentialInput.problem(CredentialStore.Key.DEEZER_ARL, "deadbeef123$arl"))
        // И хвостом тоже — вставили дважды подряд.
        assertNotNull(CredentialInput.problem(CredentialStore.Key.DEEZER_ARL, arl + arl))
        // Ровно 192, но не hex — не ARL.
        assertNotNull(CredentialInput.problem(CredentialStore.Key.DEEZER_ARL, "z".repeat(192)))
    }

    @Test
    fun truncatedValuesAreRejected() {
        // Ровно тот случай: половина ARL / обрезанный JWT.
        assertNotNull(CredentialInput.problem(CredentialStore.Key.DEEZER_ARL, arl.take(96)))
        // Обрезка ВНУТРЬ payload — именно так теряется вставка; подпись
        // при этом тоже пропадает, и base64 середины перестаёт разбираться.
        assertNotNull(CredentialInput.problem(CredentialStore.Key.TIDAL_OAUTH, jwt.take(60)))
        assertNotNull(CredentialInput.problem(CredentialStore.Key.QOBUZ_APP_ID, "31236999"))
        assertNotNull(CredentialInput.problem(CredentialStore.Key.QOBUZ_SECRET, "e79f8b9b"))
    }

    @Test
    fun emptyMeansClearNotError() {
        assertNull(CredentialInput.problem(CredentialStore.Key.DEEZER_ARL, ""))
        assertNull(CredentialInput.problem(CredentialStore.Key.TIDAL_OAUTH, "   "))
    }
}
