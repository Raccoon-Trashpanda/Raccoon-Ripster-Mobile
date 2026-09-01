package net.ripster.mobile.service.soundcloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request
import java.io.IOException

/**
 * SoundCloud не выдаёт публичный ключ — его добывают со страницы сайта.
 * Схема (та же, что у всех SC-клиентов, включая Lucida):
 *   1. GET https://soundcloud.com/ — в HTML есть `<script … src="…/assets/…js">`.
 *   2. В одном из этих бандлов лежит `client_id:"<32 hex-ish>"`.
 *   3. Кэшируем; на 401 сбрасываем и добываем заново (ключ ротируется).
 *
 * Держим последний рабочий ключ в памяти. Персист на диск добавит слой
 * настроек в Этапе 1b — пока добывается заново при холодном старте, это
 * один лишний GET.
 */
object SoundCloudClientId {

    private val mutex = Mutex()
    @Volatile private var cached: String? = null

    private val SCRIPT_SRC = Regex("""<script[^>]+src="(https://[^"]+/assets/[^"]+\.js)"""")
    private val CLIENT_ID = Regex("""[,{]client_id:"([a-zA-Z0-9]{32})"""")

    suspend fun get(forceRefresh: Boolean = false): String = mutex.withLock {
        val hit = cached
        if (hit != null && !forceRefresh) return hit
        val fresh = scrape()
        cached = fresh
        fresh
    }

    /** Вызвать при 401 от API — текущий ключ протух. */
    fun invalidate() {
        cached = null
    }

    private suspend fun scrape(): String = withContext(Dispatchers.IO) {
        val html = body("https://soundcloud.com/")
        val scripts = SCRIPT_SRC.findAll(html).map { it.groupValues[1] }.toList()
        if (scripts.isEmpty()) throw IOException("SoundCloud: no asset bundles found on the homepage")

        // Ключ почти всегда в последних бандлах — идём с конца, чтобы обычно
        // хватало одного запроса.
        for (src in scripts.asReversed()) {
            val js = runCatching { body(src) }.getOrNull() ?: continue
            CLIENT_ID.find(js)?.let { return@withContext it.groupValues[1] }
        }
        throw IOException("SoundCloud: client_id not found in any bundle (${scripts.size} scanned)")
    }

    private fun body(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GET $url -> HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("GET $url -> empty body")
        }
    }

    // Десктопный UA намеренно: под мобильным UA soundcloud.com отдаёт
    // урезанную страницу без asset-бандлов, и client_id негде взять.
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
}
