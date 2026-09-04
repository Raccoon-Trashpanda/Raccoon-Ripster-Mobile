package net.ripster.mobile.core.pair

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.core.settings.CredentialStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Клиент сопряжения с ПК-Рипстером (протокол — ARCH_2026-08-29_pc_phone_pairing.md
 * + переработка «намертво, как Spotify»).
 *
 * Что даёт сопряжение:
 *  1. рукопожатие по 8-значному коду с экрана ПК (`/api/pair/claim`);
 *  2. разовую/инкрементальную выкачку токенов сервисов (`/api/pair/credentials`)
 *     в шифрованный [CredentialStore] — дальше телефон качает сам нативными
 *     клиентами. Это «ключи от ПК», а не «загрузка через ПК»;
 *  3. живой адрес ПК: `endpoints[]` (LAN + mDNS + внешний, если у ЭТОГО ПК он
 *     есть). Телефон пробует по очереди, кэширует рабочий, а при смене IP дома
 *     заново находит СВОЙ ПК обходом подсети по `/api/pair/ping` (сверяя `pcId`).
 *
 * Связка привязана к СТАБИЛЬНОМУ `pcId` («папа») + `mobileId` («мама») — не к
 * сети и не к файлу состояния. Автономные движки работают на телефоне и с
 * выключенным/недосягаемым ПК.
 */
class PcBridge(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("pc_bridge", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ── идентичность связки ────────────────────────────────────────────────

    /** ID этого телефона — генерируется один раз, живёт всегда. «Мама». */
    val mobileId: String
        get() = prefs.getString("mobile_id", null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString("mobile_id", it).apply() }

    private val deviceName: String
        get() = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ").trim().ifBlank { "phone" }.take(48)

    /** Стабильный ID спаренного ПК. «Папа». */
    val pcId: String? get() = prefs.getString("pc_id", null)?.ifBlank { null }
    val pcName: String get() = prefs.getString("pc_name", "")?.ifBlank { "PC" } ?: "PC"

    val token: String? get() = prefs.getString("token", null)?.ifBlank { null }
    val deviceGroupId: String? get() = prefs.getString("dgid", null)
    val capabilities: Set<String> get() = prefs.getStringSet("caps", emptySet()) ?: emptySet()
    // Спарено = есть device-токен. `pcId` может отсутствовать у связок,
    // созданных до переработки идентичности — их `resolveBase` не сверяет id.
    val paired: Boolean get() = token != null

    /** Витрина Apple, в которой качает ПК (страна подписки). Для iTunes-запросов. */
    val appleStorefront: String get() = prefs.getString("apple_sf", "us")?.ifBlank { "us" } ?: "us"

    /** Режим fan-out по /ws на всю пару: mirror | initiator | isolation. */
    var fanoutMode: String
        get() = prefs.getString("mode", "mirror")?.takeIf { it in MODES } ?: "mirror"
        set(v) { if (v in MODES) prefs.edit().putString("mode", v).apply() }

    // ── адреса ПК ─────────────────────────────────────────────────────────

    /** Ручной адрес, введённый в Настройках (для стартового сопряжения и как
     *  «внешний адрес ПК» для тех, у кого свой туннель/DDNS/VPN). */
    var manualAddress: String
        get() = prefs.getString("manual_addr", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) { prefs.edit().putString("manual_addr", normalizeBase(v)).apply() }

    /** Для обратной совместимости со старым UI (поле «адрес ПК»). */
    var baseUrl: String
        get() = prefs.getString("last_good", null) ?: manualAddress
        set(v) { manualAddress = v }

    private var lastGood: String?
        get() = prefs.getString("last_good", null)?.ifBlank { null }
        set(v) { prefs.edit().putString("last_good", v ?: "").apply() }

    /** Все адреса ЭТОГО ПК из ответа claim/status, в порядке предпочтения. */
    fun endpoints(): List<String> = runCatching {
        val raw = prefs.getString("endpoints", "[]") ?: "[]"
        json.parseToJsonElement(raw).jsonArray.mapNotNull {
            it.jsonObject["url"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrDefault(emptyList())

    private fun storeEndpoints(arr: kotlinx.serialization.json.JsonArray?) {
        val urls = arr?.mapNotNull { el ->
            val o = el.jsonObject
            val u = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val k = o["kind"]?.jsonPrimitive?.contentOrNull ?: ""
            """{"url":${jsonStr(u)},"kind":${jsonStr(k)}}"""
        } ?: emptyList()
        prefs.edit().putString("endpoints", "[" + urls.joinToString(",") + "]").apply()
    }

    /**
     * Рабочий базовый URL ПК. Пробует: кэш last-good → каждый endpoint →
     * (на Wi-Fi) обход /24 подсети. Проверяет, что это ИМЕННО наш ПК (совпал
     * `pcId`). null ⇒ ПК сейчас не достать — вызывающий отдаёт понятную ошибку.
     */
    suspend fun resolveBase(deep: Boolean = false): String? = withContext(Dispatchers.IO) {
        val want = pcId
        suspend fun ok(base: String): Boolean {
            val hit = withTimeoutOrNull(2500) {
                runCatching {
                    val req = Request.Builder().url("$base/api/pair/ping").build()
                    RipsterHttp.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching false
                        val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                        val gotId = o["pc_id"]?.jsonPrimitive?.contentOrNull
                        want == null || gotId == want
                    }
                }.getOrDefault(false)
            } ?: false
            return hit
        }
        lastGood?.let { if (ok(it)) return@withContext it }
        val tries = buildList {
            addAll(endpoints())
            manualAddress.let { if (it.isNotBlank()) add(normalizeBase(it)) }
        }.distinct()
        for (b in tries) if (ok(b)) { lastGood = b; return@withContext b }
        if (deep) sweepLan(::ok)?.let { lastGood = it; return@withContext it }
        null
    }

    /** Обойти /24 своей Wi-Fi подсети в поисках `/api/pair/ping` нашего ПК. */
    private suspend fun sweepLan(ok: suspend (String) -> Boolean): String? = withContext(Dispatchers.IO) {
        val self = localIpv4() ?: return@withContext null
        val prefix = self.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return@withContext null
        // 254 хоста пачками по 24, быстрый таймаут — весь скан ~4–6с
        for (chunk in (1..254).chunked(24)) {
            val found = kotlinx.coroutines.coroutineScope {
                chunk.map { host ->
                    async {
                        val base = "http://$prefix.$host:$PORT"
                        if (base != "http://$self:$PORT" && ok(base)) base else null
                    }
                }.awaitAll().firstOrNull { it != null }
            }
            if (found != null) return@withContext found
        }
        null
    }

    private fun localIpv4(): String? = runCatching {
        val ifaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in java.util.Collections.list(nif.inetAddresses)) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return@runCatching addr.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    /** Обёртка: выполнить запрос по рабочему базовому URL, с одним пере-резолвом
     *  (в т.ч. глубоким — обход подсети) при сетевой ошибке. */
    private suspend fun <T> viaBase(block: suspend (String) -> T): T {
        val b1 = resolveBase(deep = false)
            ?: resolveBase(deep = true)
            ?: throw java.io.IOException(net.ripster.mobile.core.errors.EngineErrors.PC_OFFLINE)
        return try {
            block(b1)
        } catch (e: java.io.IOException) {
            val b2 = resolveBase(deep = true)
                ?: throw java.io.IOException(
                    net.ripster.mobile.core.errors.EngineErrors.code(
                        net.ripster.mobile.core.errors.EngineErrors.PC_OFFLINE, e.message))
            block(b2)
        }
    }

    // ── рукопожатие ──────────────────────────────────────────────────────

    /**
     * Ввести код с экрана ПК. При успехе сохраняет device-токен, стабильный
     * `pcId`/`pcName`, список адресов ПК и режим fan-out.
     *
     * Ре-пейр того же ПК (совпал `pcId`) НЕ трогает уже синхронизированные
     * учётки — просто перевыпуск ключа связки.
     */
    suspend fun claim(address: String, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val base = normalizeBase(address)
        val digits = code.filter { it.isDigit() }
        runCatching {
            val payload = """{"code":${jsonStr(digits)},"mobile_id":${jsonStr(mobileId)},"name":${jsonStr(deviceName)}}"""
            val req = Request.Builder().url("$base/api/pair/claim")
                .post(payload.toRequestBody(JSON_MEDIA)).build()
            RipsterHttp.client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                require(resp.isSuccessful) { "HTTP ${resp.code}: ${txt.take(180)}" }
                val o = json.parseToJsonElement(txt).jsonObject
                val tok = o["token"]?.jsonPrimitive?.contentOrNull ?: error("no token in response")
                val newPcId = o["pc_id"]?.jsonPrimitive?.contentOrNull
                    ?: o["device_group_id"]?.jsonPrimitive?.contentOrNull
                val caps = o["capabilities"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
                val samePc = newPcId != null && newPcId == pcId
                prefs.edit().apply {
                    putString("manual_addr", base)
                    putString("last_good", base)
                    putString("token", tok)
                    putString("pc_id", newPcId)
                    putString("pc_name", o["pc_name"]?.jsonPrimitive?.contentOrNull ?: "PC")
                    putString("dgid", o["device_group_id"]?.jsonPrimitive?.contentOrNull)
                    putStringSet("caps", caps)
                    putString("apple_sf", o["apple_storefront"]?.jsonPrimitive?.contentOrNull ?: "us")
                    putString("mode", o["mode"]?.jsonPrimitive?.contentOrNull?.takeIf { it in MODES } ?: "mirror")
                    if (!samePc) remove("synced_ok")   // новый ПК — учётки перекачать заново
                    apply()
                }
                storeEndpoints(o["endpoints"]?.jsonArray)
            }
        }
    }

    /**
     * Выкачать токены сервисов с ПК в [store]. Возвращает число реально
     * записанных ключей (свой более свежий ручной ввод не затирается —
     * см. [CredentialStore.mergeFromPc]).
     */
    suspend fun syncCredentials(store: CredentialStore): Result<Int> = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        runCatching {
            viaBase { base ->
                val req = Request.Builder()
                    .url("$base/api/pair/credentials")
                    .header("Authorization", "Bearer $tok")
                    .build()
                RipsterHttp.client.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    require(resp.isSuccessful) { "HTTP ${resp.code}: ${txt.take(180)}" }
                    val root = json.parseToJsonElement(txt).jsonObject
                    val updatedAt = root["updated_at"]?.jsonPrimitive?.longOrNull
                        ?: System.currentTimeMillis()
                    val creds = root["credentials"]?.jsonObject ?: return@use 0
                    var written = 0
                    for ((id, el) in creds) {
                        val key = CredentialStore.Key.entries.firstOrNull { it.id == id } ?: continue
                        val value = el.jsonPrimitive.contentOrNull ?: continue
                        if (store.mergeFromPc(key, value, updatedAt)) written++
                    }
                    prefs.edit().putBoolean("synced_ok", true).apply()
                    written
                }
            }
        }
    }

    /**
     * Отправить на ПК, что телефон скачал и что слушал — чтобы история
     * ПК-версии видела активность телефона. Best-effort, дедуп на стороне ПК
     * по (title, artist, время). `playsJson` / `downloadsJson` — уже готовые
     * JSON-массивы объектов.
     */
    suspend fun pushActivity(playsJson: String, downloadsJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        runCatching {
            viaBase { base ->
                val body = """{"plays":$playsJson,"downloads":$downloadsJson}"""
                val req = Request.Builder().url("$base/api/pair/activity")
                    .header("Authorization", "Bearer $tok")
                    .post(body.toRequestBody(JSON_MEDIA)).build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    require(r.isSuccessful) { "HTTP ${r.code}" }
                }
            }
        }
    }

    /** Экранировать строку для ручной сборки JSON (публично — нужно вызывающему). */
    fun jsonEscape(s: String): String = jsonStr(s)

    /** Задать режим fan-out на всю пару (применяется и к ПК). */
    suspend fun setMode(mode: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (mode !in MODES) return@withContext Result.failure(IllegalArgumentException(mode))
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        runCatching {
            viaBase { base ->
                val req = Request.Builder().url("$base/api/pair/mode")
                    .header("Authorization", "Bearer $tok")
                    .post("""{"mode":${jsonStr(mode)}}""".toRequestBody(JSON_MEDIA)).build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    require(r.isSuccessful) { "HTTP ${r.code}" }
                }
            }
            fanoutMode = mode
        }
    }

    /** WebSocket-URL живого зеркала ПК (`/ws?pair=<token>`). null ⇒ не спарено. */
    suspend fun wsUrl(): String? {
        val tok = token ?: return null
        val base = resolveBase(deep = true) ?: return null
        val ws = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        return "$ws/ws?pair=$tok"
    }

    /** Отвязаться ОБОЮДНО: сначала просим ПК убрать наш токен, потом чистим
     *  локально. Если ПК недоступен — всё равно чистим у себя (не залипаем). */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        val tok = token
        if (tok != null) {
            runCatching {
                viaBase { base ->
                    val req = Request.Builder().url("$base/api/pair/unpair")
                        .header("Authorization", "Bearer $tok")
                        .post("".toRequestBody(JSON_MEDIA)).build()
                    RipsterHttp.client.newCall(req).execute().close()
                }
            }
        }
        prefs.edit().clear().apply()
    }

    // ── Apple Music через ПК ────────────────────────────────────────────────

    data class AppleJob(
        val status: String,   // queued | running | done | error
        val progress: Int,
        val title: String,
        val artist: String,
        val error: String,
        val note: String,
    )

    /** Поставить Apple-ссылку в очередь ПК. Возвращает task_id. */
    suspend fun appleFetch(url: String, quality: String): Result<String> = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        runCatching {
            viaBase { base ->
                val payload = """{"url":${jsonStr(url)},"quality":${jsonStr(quality)}}"""
                val req = Request.Builder().url("$base/api/pair/fetch")
                    .header("Authorization", "Bearer $tok")
                    .post(payload.toRequestBody(JSON_MEDIA)).build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    require(r.isSuccessful) { "HTTP ${r.code}: ${txt.take(180)}" }
                    json.parseToJsonElement(txt).jsonObject["task_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("no task_id")
                }
            }
        }
    }

    suspend fun appleStatus(taskId: String): Result<AppleJob> = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        runCatching {
            viaBase { base ->
                val req = Request.Builder().url("$base/api/pair/fetch/$taskId")
                    .header("Authorization", "Bearer $tok").build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    require(r.isSuccessful) { "HTTP ${r.code}: ${txt.take(180)}" }
                    val o = json.parseToJsonElement(txt).jsonObject
                    AppleJob(
                        status = o["status"]?.jsonPrimitive?.contentOrNull ?: "queued",
                        progress = o["progress"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.toInt() ?: 0,
                        title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        artist = o["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        error = o["error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }
        }
    }

    /** Скачать готовый файл Apple-задачи в [out]. */
    suspend fun appleFile(taskId: String, out: java.io.File): Result<Long> = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        runCatching {
            viaBase { base ->
                val req = Request.Builder().url("$base/api/pair/file/$taskId")
                    .header("Authorization", "Bearer $tok").build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    require(r.isSuccessful) { "HTTP ${r.code}" }
                    val body = r.body ?: error("empty body")
                    body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            out.length()
        }
    }

    private fun jsonStr(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ── Радар ─────────────────────────────────────────────────────────────

    data class RadarItem(
        val name: String,
        val service: String,
        val lastCheck: String?,
        val latestUrl: String,
        val auto: Boolean,
        val seenCount: Int,
        val kind: String = "artist",   // artist | label
        val coverUrl: String? = null,  // готовая обложка релиза с ПК (Spotify отдаёт сразу)
        val artistId: String = "",     // id артиста в сервисе — для перехода в дискографию
        val date: String = "",         // дата релиза (ISO). Будущая → поток ещё не существует
        /**
         * Название последнего релиза. Пустое — ПК его не знает.
         *
         * Раньше карточка радара печатала имя артиста дважды (и в заголовке, и
         * в подписи), потому что названия в модели просто не было: жалоба
         * владельца 04.09.2026 «у всех карточек название совпадает с артистом».
         */
        val latestTitle: String = "",
    )

    // ── страница артиста (дискография) ──
    data class ArtistRelease(
        val id: String, val title: String, val coverUrl: String?,
        val year: String, val date: String, val trackCount: Int?,
        val type: String, val url: String, val service: String,
        /** Если это не собственный релиз артиста, а его трек в сборнике/миксе —
         *  название этого трека. Пусто = собственный релиз. */
        val appearsAs: String = "",
        /** Главный артист чужого релиза (для «С этим артистом»): напр. имя
         *  куратора микса или «разные артисты». Пусто для собственных. */
        val albumArtist: String = "",
    )
    data class ArtistPage(
        val name: String, val pictureUrl: String?,
        val releases: List<ArtistRelease>, val error: String? = null,
    )

    suspend fun radar(): Result<List<RadarItem>> = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        runCatching {
            viaBase { base ->
                val req = Request.Builder().url("$base/api/pair/radar")
                    .header("Authorization", "Bearer $tok").build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    require(r.isSuccessful) { "HTTP ${r.code}: ${txt.take(160)}" }
                    (json.parseToJsonElement(txt).jsonObject["items"]?.jsonArray ?: emptyList()).map { el ->
                        val o = el.jsonObject
                        RadarItem(
                            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            service = o["service"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            lastCheck = o["last_check"]?.jsonPrimitive?.contentOrNull,
                            latestUrl = o["latest_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            auto = o["auto"]?.jsonPrimitive?.contentOrNull == "true",
                            seenCount = o["seen_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                            kind = o["kind"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "artist",
                            coverUrl = o["cover_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            artistId = o["artist_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            date = o["date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            latestTitle = o["latest_title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    /** Дискография артиста с ПК (`/api/pair/artist`). Нужен `artistId`. */
    suspend fun artist(service: String, artistId: String): Result<ArtistPage> =
        artistOrLabel("$normPairArtistPath?service=${enc(service)}&id=${enc(artistId)}",
            requireId = artistId.isBlank())

    /** Релизы лейбла с ПК (`/api/pair/label`). */
    suspend fun label(name: String): Result<ArtistPage> =
        artistOrLabel("$normPairLabelPath?name=${enc(name)}", requireId = name.isBlank())

    private val normPairArtistPath get() = "/api/pair/artist"
    private val normPairLabelPath get() = "/api/pair/label"
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun artistOrLabel(pathAndQuery: String, requireId: Boolean): Result<ArtistPage> = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.failure(IllegalStateException("not paired"))
        if (requireId) return@withContext Result.failure(IllegalArgumentException("no id/name"))
        runCatching {
            viaBase { base ->
                val u = "$base$pathAndQuery"
                val req = Request.Builder().url(u).header("Authorization", "Bearer $tok").build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    require(r.isSuccessful) { "HTTP ${r.code}: ${txt.take(160)}" }
                    val root = json.parseToJsonElement(txt).jsonObject
                    val art = root["artist"]?.jsonObject
                    val rels = (root["releases"]?.jsonArray ?: emptyList()).mapNotNull { el ->
                        val o = el.jsonObject
                        val url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        ArtistRelease(
                            id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            coverUrl = o["cover"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            year = o["year"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            date = o["date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            trackCount = o["tracks"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                            type = o["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "album",
                            url = url,
                            service = o["service"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            appearsAs = o["appears_as"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            albumArtist = o["album_artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ).takeIf { it.title.isNotBlank() }
                    }
                    ArtistPage(
                        name = art?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        pictureUrl = (art?.get("picture") ?: art?.get("picture_xl"))
                            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        releases = rels,
                        error = root["error"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        }
    }

    private fun normalizeBase(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return DEFAULT_BASE
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    companion object {
        private const val DEFAULT_BASE = "http://10.0.2.2:7799"
        private const val PORT = 7799
        private val JSON_MEDIA = "application/json".toMediaType()
        val MODES = listOf("mirror", "initiator", "isolation")
    }
}
