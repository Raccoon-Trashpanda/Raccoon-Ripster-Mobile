package net.ripster.mobile.core.bbc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request

/**
 * Свежие миксы BBC Sounds — самодостаточная копия ПК-модуля
 * (`ripster/routes/bbc.py`), без сопряжения и без аккаунта.
 *
 * Источник тот же: RMS-API `programmes/playable` по списку брендов (Essential
 * Mix, Pete Tong, Radio 1 Dance…). Ответ отдаёт эпизоды с датой, обложкой и
 * длительностью; настоящий PID эпизода лежит в `urn` (в `id` — VPID), и
 * скачивание уже умеет с ним работать через [net.ripster.mobile.service.bbc].
 *
 * Ключа не нужно: страница программ у BBC открытая. Сеть недоступна — вернём
 * пустой список, Главная просто не покажет секцию.
 */
object BbcShows {

    private const val RMS = "https://rms.api.bbc.co.uk/v2"
    private val json = Json { ignoreUnknownKeys = true }

    /** Бренды 1:1 с ПК-версией — тот же курируемый набор шоу. */
    val BRANDS: List<Brand> = listOf(
        Brand("b006wkfp", "Essential Mix"),
        Brand("b00f3pc4", "Classic Essential Mix"),
        Brand("b006ww0v", "Pete Tong"),
        Brand("m0009y7t", "Radio 1 Dance"),
        Brand("b01dmw9x", "Dance Anthems"),
        Brand("m002d2x6", "The 6 Mix"),
        Brand("m001dkv1", "Rave Forever"),
        Brand("b01fm4ss", "Gilles Peterson"),
        Brand("b0072ky7", "Craig Charles Funk & Soul"),
        Brand("m0021281", "DnB Allstars"),
        Brand("b006tp52", "Late Junction"),
    )

    data class Brand(val id: String, val label: String)

    data class Mix(
        val pid: String,
        val title: String,
        val subtitle: String,
        val show: String,
        val date: String,
        val imageUrl: String,
        val durationSec: Int,
    ) {
        val url: String get() = "https://www.bbc.co.uk/programmes/$pid"
    }

    /**
     * Последние миксы по всем брендам, свежие сверху.
     *
     * [perBrand] держим маленьким: на Главной нужна витрина, а не архив, и
     * каждый бренд — отдельный запрос. Бренды опрашиваются параллельно, но с
     * общим потолком: одна залипшая программа не должна задерживать секцию.
     */
    suspend fun latest(perBrand: Int = 3, limit: Int = 24): List<Mix> = coroutineScope {
        val lists = BRANDS.map { b ->
            async {
                withTimeoutOrNull(10_000) {
                    runCatching { episodes(b, perBrand) }.getOrNull()
                }.orEmpty()
            }
        }.awaitAll()
        lists.flatten()
            .filter { it.pid.isNotBlank() && it.date.isNotBlank() }
            .sortedByDescending { it.date }
            .take(limit)
    }

    private suspend fun episodes(brand: Brand, limit: Int): List<Mix> {
        val url = "$RMS/programmes/playable?container=${brand.id}" +
            "&sort=sequential&type=episode&experience=domestic&offset=0&limit=$limit"
        val body = get(url)
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val titles = o["titles"]?.jsonObject
                // id = VPID; настоящий PID эпизода — хвост urn.
                val vpid = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val urn = o["urn"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pid = urn.substringAfterLast(':', vpid).ifBlank { vpid }
                Mix(
                    pid = pid,
                    title = titles?.get("primary")?.jsonPrimitive?.contentOrNull
                        ?: o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    subtitle = titles?.get("secondary")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    show = brand.label,
                    date = o["release"]?.jsonObject?.get("date")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    imageUrl = imageOf(o),
                    durationSec = o["duration"]?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull
                        ?: o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }.getOrNull()
        }
    }

    /** У BBC ссылка на картинку — шаблон с `{recipe}`; подставляем размер. */
    private fun imageOf(o: kotlinx.serialization.json.JsonObject): String {
        val direct = o["image_url"]?.jsonPrimitive?.contentOrNull
        val tpl = direct ?: o["image"]?.jsonObject?.get("pid")?.jsonPrimitive?.contentOrNull
            ?.let { "https://ichef.bbci.co.uk/images/ic/{recipe}/$it.jpg" }
        return tpl?.replace("{recipe}", "480x480").orEmpty()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("BBC RMS ${r.code}")
            r.body?.string().orEmpty()
        }
    }
}
