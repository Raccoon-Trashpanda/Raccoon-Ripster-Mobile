package net.ripster.mobile.service.qobuz

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Без аккаунта Qobuz проверяем то, что от него не зависит: скрейп
 * `app_id` + секрета со страницы веб-плеера и то, что пустые креды дают
 * `isConfigured() == false` без падения. Логин/поиск/скачивание — на
 * владельце с его учёткой.
 */
class QobuzTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "qb-test").apply { mkdirs() }

    @Test
    fun bundleScrapeYieldsAppIdAndSecret(): Unit = runBlocking {
        val creds = QobuzBundle.resolve(overrideId = null, overrideSecret = null)
        assertTrue("appId должен быть 9-10 цифр, получили '${creds.appId}'",
            creds.appId.matches(Regex("\\d{9,10}")))
        assertTrue("должен быть хотя бы один секрет", creds.secrets.isNotEmpty())
        assertTrue("секрет — 32 hex, получили '${creds.secrets.first()}'",
            creds.secrets.first().matches(Regex("[a-f0-9]{32}")))
    }

    @Test
    fun noCredentialsIsConfiguredFalse(): Unit = runBlocking {
        val client = QobuzClient(
            email = null, password = null, token = null,
            appId = null, secret = null, cacheDir = cacheDir,
        )
        assertFalse(client.isConfigured())
    }
}
