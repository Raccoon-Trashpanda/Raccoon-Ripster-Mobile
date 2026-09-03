package net.ripster.mobile.service.qobuz

import net.ripster.mobile.core.errors.EngineErrors
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Добыча `app_id` и секрета Qobuz со страницы веб-плеера — приём из
 * streamrip. Пользовательские значения из настроек (если заданы) имеют
 * приоритет и скрейп не запускается.
 *
 * Схема: `play.qobuz.com/login` → в HTML ссылка на `bundle.js` → в бандле
 *   1. `production:{api:{appId:"<9 цифр>",appSecret:"<32 hex>"` — если есть,
 *      берём и id, и секрет разом (частый случай);
 *   2. иначе — восстановление секретов из seed+timezone (`initialSeed` +
 *      `name/<tz>,info,extras`), base64 без последних 44 символов.
 */
object QobuzBundle {

    data class Creds(val appId: String, val secrets: List<String>)

    /** Встроенная пара app_id ↔ app_secret. Это НЕ запасной вариант, а основной:
     *  скрейп bundle.js больше не нужен ни для поиска, ни для скачивания.
     *
     *  Почему не скрейп: app_id веб-плеера (`798273057`) отвечает 401 даже на
     *  публичный поиск, а секреты, восстановленные из seed+timezone, не
     *  подписывают `getFileUrl` (у streamrip его спуфер сейчас вообще падает на
     *  `vals.remove("")`). Эта пара приходит вместе с самими токенами подписки.
     *
     *  Проверено 03.09.2026 на трёх независимых токенах (NZ/US/PT):
     *   · `track/search` → total=1000;
     *   · `track/getFileUrl` format_id 27/6/5 → живые ссылки, FLAC 24bit/96kHz.
     *
     *  Своя пара из Настроек → Учётные записи (`qobuz.app_id`+`qobuz.secret`)
     *  ПЕРЕКРЫВАЕТ встроенную — но только целиком, парой (чужой секрет к
     *  чужому id не подойдёт). */
    const val SEARCH_APP_ID = "312369995"
    const val DEFAULT_SECRET = "e79f8b9be485692b0e5f9dd895826368"

    val FALLBACK = Creds(SEARCH_APP_ID, listOf(DEFAULT_SECRET))

    // Qobuz периодически меняет путь бандла — держим набор шаблонов от узкого к
    // широкому, чтобы скрейп не «рот" на смене формата HTML веб-плеера.
    private val BUNDLE_SRCS = listOf(
        Regex("""<script src="(/resources/\d+\.\d+\.\d+-[a-z]\d+/bundle\.js)""""),
        Regex("""<script src="(/resources/[^"]+/bundle\.js)""""),
        Regex("""<script[^>]+src="(/[^"]*bundle(?:\.[a-z0-9]+)?\.js)""""),
        Regex("""src="(/[^"]*\.bundle\.js)""""),
    )
    // app_id живёт в `production:{api:{appId:"…",appSecret:"…"` — якорь обязателен,
    // иначе голый `appId:"…"` цепляет ЧУЖОЙ id из другого места бандла → 400 на
    // getFileUrl. (1:1 со streamrip client/qobuz.py `app_id_regex`.)
    private val APPID = Regex("""production:\{api:\{appId:"(\d{9})",appSecret:"(\w{32})""")
    private val SEED_TZ = Regex("""[a-z]\.initialSeed\("([\w=]+)",window\.utimezone\.([a-z]+)\)""")

    /** Скрейп bundle.js многомегабайтный — держим результат на диске [TTL_MS],
     *  иначе КАЖДЫЙ холодный старт платит за него в таймауте первого поиска
     *  (жалоба тестера 03.09.2026: «Qobuz didn't respond in time»). */
    private const val TTL_MS = 7L * 24 * 3600 * 1000

    suspend fun resolve(
        overrideId: String?,
        overrideSecret: String?,
        cacheFile: File? = null,
        forceFresh: Boolean = false,
    ): Creds = withContext(Dispatchers.IO) {
        if (!overrideId.isNullOrBlank() && !overrideSecret.isNullOrBlank()) {
            return@withContext Creds(overrideId.trim(), listOf(overrideSecret.trim()))
        }

        if (!forceFresh && cacheFile != null && cacheFile.isFile &&
            (System.currentTimeMillis() - cacheFile.lastModified()) < TTL_MS
        ) {
            runCatching {
                val lines = cacheFile.readText().split('\n').filter { it.isNotBlank() }
                val id = lines.firstOrNull()?.trim().orEmpty()
                val secs = lines.drop(1).map { it.trim() }.filter { it.length == 32 }
                if (id.length == 9 && secs.isNotEmpty()) return@withContext Creds(id, secs)
            }
        }

        val loginHtml = body("https://play.qobuz.com/login")
        val bundlePath = BUNDLE_SRCS.firstNotNullOfOrNull { it.find(loginHtml)?.groupValues?.get(1) }
            ?: throw IOException(EngineErrors.QOBUZ_KEYS)
        val bundle = body("https://play.qobuz.com$bundlePath")

        val appIdM = APPID.find(bundle)
        val appId = overrideId?.trim()?.ifBlank { null }
            ?: appIdM?.groupValues?.get(1)
            ?: throw IOException(EngineErrors.QOBUZ_KEYS)

        // Восстановление секретов по методу веб-плеера (порт streamrip Spoofer):
        //  seed+timezone из initialSeed(); ВТОРОЙ timezone двигаем в начало
        //  (Qobuz-код с двумя ложными тернарниками использует именно вторую пару);
        //  к каждому timezone добавляем info+extras; base64(seed+info+extras без
        //  хвостовых 44 символов).
        val seeds = LinkedHashMap<String, MutableList<String>>()
        SEED_TZ.findAll(bundle).forEach { m ->
            seeds.getOrPut(m.groupValues[2]) { mutableListOf() }.add(m.groupValues[1])
        }
        val ordered = LinkedHashMap<String, MutableList<String>>()
        val keys = seeds.keys.toList()
        if (keys.size >= 2) {
            ordered[keys[1]] = seeds[keys[1]]!!
            keys.forEachIndexed { i, k -> if (i != 1) ordered[k] = seeds[k]!! }
        } else {
            ordered.putAll(seeds)
        }
        val tzAlt = ordered.keys.joinToString("|") { it.replaceFirstChar { c -> c.uppercase() } }
        val infoExtras = Regex("""name:"\w+/($tzAlt)",info:"([\w=]+)",extras:"([\w=]+)"""")
        infoExtras.findAll(bundle).forEach { m ->
            ordered[m.groupValues[1].lowercase()]?.apply { add(m.groupValues[2]); add(m.groupValues[3]) }
        }
        val secrets = ordered.values.mapNotNull { parts ->
            runCatching {
                String(Base64.decode(parts.joinToString("").dropLast(44), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.length == 32 }
        }
        val merged = (secrets + listOfNotNull(overrideSecret?.trim()?.ifBlank { null })
            + listOfNotNull(appIdM?.groupValues?.get(2))).distinct()
        if (merged.isEmpty()) throw IOException(EngineErrors.QOBUZ_KEYS)
        if (cacheFile != null) runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText((listOf(appId) + merged.filter { it.length == 32 }).joinToString("\n"))
        }
        Creds(appId, merged)
    }

    private fun body(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GET $url -> HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("GET $url -> empty body")
        }
    }
}
