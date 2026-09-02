package net.ripster.mobile.service.qobuz

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request
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

    suspend fun resolve(overrideId: String?, overrideSecret: String?): Creds = withContext(Dispatchers.IO) {
        if (!overrideId.isNullOrBlank() && !overrideSecret.isNullOrBlank()) {
            return@withContext Creds(overrideId.trim(), listOf(overrideSecret.trim()))
        }

        val loginHtml = body("https://play.qobuz.com/login")
        val bundlePath = BUNDLE_SRCS.firstNotNullOfOrNull { it.find(loginHtml)?.groupValues?.get(1) }
            ?: throw IOException("Qobuz: не нашёл bundle.js на странице входа — формат сайта изменился; введите app_id и app_secret вручную в Настройках → Учётные записи")
        val bundle = body("https://play.qobuz.com$bundlePath")

        val appIdM = APPID.find(bundle)
        val appId = overrideId?.trim()?.ifBlank { null }
            ?: appIdM?.groupValues?.get(1)
            ?: throw IOException("Qobuz: app_id не найден в bundle — введите app_id и app_secret вручную в Настройках")

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
        if (merged.isEmpty()) throw IOException("Qobuz: could not reconstruct any app secret")
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
