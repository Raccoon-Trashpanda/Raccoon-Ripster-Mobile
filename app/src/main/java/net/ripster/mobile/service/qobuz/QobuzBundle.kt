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
    // appId + appSecret рядом (порядок ключей и пробелы могут гулять).
    private val APPID_SECRET = Regex("""appId:"(\d{9,10})"[^}]{0,80}?appSecret:"([a-f0-9]{32})"""")
    private val APPID_SECRET_REV = Regex("""appSecret:"([a-f0-9]{32})"[^}]{0,80}?appId:"(\d{9,10})"""")
    private val APPID_ONLY = Regex("""appId:"(\d{9,10})"""")
    private val SEED_TZ = Regex("""[a-z]\.initialSeed\("([\w=]+)",window\.utimezone\.([a-z]+)\)""")
    private val INFO_EXTRAS =
        Regex("""name:"\w+/([a-z]+)",info:"([\w=]+)",extras:"([\w=]+)"""")

    suspend fun resolve(overrideId: String?, overrideSecret: String?): Creds = withContext(Dispatchers.IO) {
        if (!overrideId.isNullOrBlank() && !overrideSecret.isNullOrBlank()) {
            return@withContext Creds(overrideId.trim(), listOf(overrideSecret.trim()))
        }

        val loginHtml = body("https://play.qobuz.com/login")
        val bundlePath = BUNDLE_SRCS.firstNotNullOfOrNull { it.find(loginHtml)?.groupValues?.get(1) }
            ?: throw IOException("Qobuz: не нашёл bundle.js на странице входа — формат сайта изменился; введите app_id и app_secret вручную в Настройках → Учётные записи")
        val bundle = body("https://play.qobuz.com$bundlePath")

        APPID_SECRET.find(bundle)?.let {
            return@withContext Creds(it.groupValues[1], listOf(it.groupValues[2]))
        }
        APPID_SECRET_REV.find(bundle)?.let {
            return@withContext Creds(it.groupValues[2], listOf(it.groupValues[1]))
        }

        val appId = overrideId?.trim()?.ifBlank { null }
            ?: APPID_ONLY.find(bundle)?.groupValues?.get(1)
            ?: throw IOException("Qobuz: app_id не найден в bundle — введите app_id и app_secret вручную в Настройках")

        val seeds = SEED_TZ.findAll(bundle).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val infos = INFO_EXTRAS.findAll(bundle)
            .associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }

        val secrets = seeds.mapNotNull { (seed, tz) ->
            val (info, extras) = infos[tz] ?: return@mapNotNull null
            val joined = seed + info + extras
            runCatching {
                String(Base64.decode(joined.dropLast(44), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.length == 32 }
        }
        if (secrets.isEmpty() && overrideSecret.isNullOrBlank()) {
            throw IOException("Qobuz: could not reconstruct any app secret")
        }
        Creds(appId, (secrets + listOfNotNull(overrideSecret?.trim()?.ifBlank { null })).distinct())
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
