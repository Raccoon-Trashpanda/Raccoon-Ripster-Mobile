package net.ripster.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.pair.PcBridge
import net.ripster.mobile.core.service.ReleasePlayback
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.ui.components.Cover
import net.ripster.mobile.ui.components.pressable
import net.ripster.mobile.ui.components.ReleaseCard
import net.ripster.mobile.ui.components.ReleaseCardData
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Страница артиста с дискографией — полноценный «переход на артиста», как в
 * ПК-версии. Источник данных:
 *   1. в паре с ПК + есть artist_id → `/api/pair/artist` (зрелый движковый
 *      `get_artist` ПК: полная дискография, все сервисы).
 *   2. иначе → поиск по имени в «простых» сервисах (Deezer/Qobuz/Tidal/SC),
 *      альбомы из выдачи.
 * Релизы сгруппированы по типу, карточка — та же [ReleaseCard]. Тап по
 * релизу открывает экран альбома; ▶ — стрим; ↓ — в очередь.
 */
@Composable
fun ArtistScreen(
    name: String,
    service: String,
    artistId: String,
    onBack: () -> Unit,
    onOpenAlbum: (ReleaseCardData) -> Unit,
    isLabel: Boolean = false,
) {
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val scope = rememberCoroutineScope()
    val queued = remember { mutableStateMapOf<String, Boolean>() }

    BackHandler { onBack() }

    val page by produceState<PcBridge.ArtistPage?>(initialValue = null, name, service, artistId, isLabel) {
        value = null
        // 1. НАТИВНО из клиента сервиса — работает БЕЗ ПК: своя дискография
        //    + секция «С этим артистом» (компиляции/миксы с треком артиста).
        if (!isLabel && artistId.isNotBlank()) {
            val svc = Service.entries.firstOrNull { it.id == service }
            val native = svc?.let { s ->
                withTimeoutOrNull(20_000) {
                    runCatching { ServiceRegistry.get(s)?.getArtist(artistId) }.getOrNull()
                }
            }
            if (native != null && native.error == null && native.releases.isNotEmpty()) {
                value = native; return@produceState
            }
        }
        // 2. с ПК (релизы лейбла; или если натив ничего не дал)
        var pcPage: PcBridge.ArtistPage? = null
        if (app.pcBridge.paired && (isLabel || artistId.isNotBlank())) {
            pcPage = withTimeoutOrNull(25_000) {
                if (isLabel) app.pcBridge.label(name) else app.pcBridge.artist(service, artistId)
            }?.getOrNull()
            if (pcPage != null && pcPage.error == null && pcPage.releases.isNotEmpty()) {
                value = pcPage; return@produceState
            }
        }
        // 2. фолбэк: поиск по имени в «простых» сервисах (для лейбла слабее, но лучше пустоты).
        //    Общий потолок — чтобы экран НИКОГДА не висел в «анализирует…»:
        //    не успели за 20 с → показываем пустую страницу (art.empty), не спиннер.
        val fb = withTimeoutOrNull(20_000) { searchFallback(name) }
            ?: PcBridge.ArtistPage(name = name, pictureUrl = null, releases = emptyList())
        // если фолбэк тоже пуст, а ПК вернул причину — покажем её
        value = if (fb.releases.isEmpty() && pcPage?.error != null) {
            PcBridge.ArtistPage(name = name, pictureUrl = null, releases = emptyList(), error = pcPage.error)
        } else fb
    }

    Box(Modifier.fillMaxSize().background(c.surface_canvas)) {
        val p = page
        if (p == null) {
            Column(Modifier.fillMaxSize()) {
                ArtistHeader(name, null, c, onBack)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    BasicText(
                        tr("tools.analyzing", lang),
                        Modifier.padding(24.dp),
                        style = TextStyle(color = c.text_tertiary, fontSize = 13.sp),
                    )
                }
            }
            return
        }

        if (p.releases.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                ArtistHeader(p.name.ifBlank { name }, p.pictureUrl, c, onBack)
                BasicText(
                    p.error?.takeIf { it.isNotBlank() } ?: tr("art.empty", lang),
                    Modifier.padding(24.dp),
                    style = TextStyle(color = c.text_tertiary, fontSize = 13.sp, lineHeight = 18.sp),
                )
            }
            return
        }

        // порядок групп: альбомы → синглы/EP → сборники → концерты → прочее
        val order = listOf("album", "single", "ep", "compilation", "live")
        val groups = p.releases
            .sortedByDescending { it.date.ifBlank { it.year } }
            .groupBy { normType(it.type) }
            .toList()
            .sortedBy { (k, _) -> order.indexOf(k).let { if (it < 0) 99 else it } }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ArtistHeader(p.name.ifBlank { name }, p.pictureUrl, c, onBack)
            }
            // ── «Скачать дискографию» — как в ПК-версии: в очередь все релизы
            //    артиста, у которых есть ссылка (участия в сборниках пропускаем).
            val ownDl = p.releases.filter { it.url.isNotBlank() && it.type != "compilation" }
            if (ownDl.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    var busy by remember { mutableStateOf(false) }
                    Row(
                        Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (busy) c.surface_active else c.accent_fill)
                            .pressable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    ownDl.forEach { r ->
                                        queued[r.url] = true
                                        runCatching {
                                            val sel = ServiceRegistry.all()
                                                .firstNotNullOfOrNull { it.resolve(r.url) }
                                            sel?.tracks?.forEach { app.downloads.enqueue(it) }
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        BasicText("↓", style = TextStyle(color = c.text_on_fill, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        BasicText(
                            tr(if (busy) "art.dl_all_busy" else "art.dl_all", lang) + "  ·  " + ownDl.size,
                            style = TextStyle(color = c.text_on_fill, fontSize = 12.5.sp, fontWeight = FontWeight.W600),
                        )
                    }
                }
            }
            // ── «Следить» — локальный радар (работает БЕЗ ПК): телефон сам
            //    проверяет новые релизы этого артиста раз в ~6 ч.
            if (!isLabel && artistId.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    var followed by remember(artistId) { mutableStateOf(false) }
                    LaunchedEffect(artistId, service) {
                        followed = app.localRadar.isFollowed(service, artistId)
                    }
                    Row(
                        Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, if (followed) c.accent_text else c.border_subtle, RoundedCornerShape(999.dp))
                            .background(if (followed) c.surface_active else c.surface_raised)
                            .pressable {
                                scope.launch {
                                    if (followed) {
                                        app.localRadar.unfollow("$service:artist:$artistId")
                                        followed = false
                                    } else {
                                        app.localRadar.follow(service, artistId, p.name.ifBlank { name }, p.pictureUrl)
                                        followed = true
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        BasicText(
                            if (followed) "✓" else "+",
                            style = TextStyle(color = if (followed) c.accent_text else c.text_secondary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        )
                        BasicText(
                            tr(if (followed) "art.following" else "art.follow", lang),
                            style = TextStyle(color = if (followed) c.accent_text else c.text_secondary, fontSize = 12.5.sp, fontWeight = FontWeight.W600),
                        )
                    }
                }
            }
            groups.forEach { (type, rels) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BasicText(
                        tr(groupLabelKey(type), lang) + "  ·  " + rels.size,
                        Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                        style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.W700, letterSpacing = 1.sp),
                    )
                }
                // Индекс в ключе: у релиза может не быть id (search-fallback,
                // компиляции), и два одноимённых дают один ключ → LazyColumn
                // роняет весь экран.
                itemsIndexed(rels, key = { i, it -> "${it.service}|${it.title.lowercase()}|${it.id}|$i" }) { _, r ->
                    // Для участия в сборнике/миксе показываем ЧЕСТНО: чей это
                    // релиз (куратор микса / «разные артисты») и КАКОЙ трек
                    // артиста туда входит — а не имя артиста и общее число.
                    val isAppears = normType(r.type) == "compilation"
                    val cd = ReleaseCardData(
                        title = r.title,
                        artist = if (isAppears) {
                            val who = r.albumArtist.ifBlank { tr("art.va", lang) }
                            val track = if (r.appearsAs.isNotBlank())
                                "  ·  " + tr("art.appears_as", lang) + " " + r.appearsAs else ""
                            who + track
                        } else p.name.ifBlank { name },
                        service = r.service,
                        url = r.url,
                        type = type,
                        coverUrl = r.coverUrl,
                        trackCount = r.trackCount,
                        dateText = r.year.ifBlank { r.date.take(4) },
                    )
                    var buffering by remember { mutableStateOf(false) }
                    // У релиза-участия часто нет id/ссылки (сервис не дал album-id)
                    // — тогда открываем/играем весь релиз поиском по «куратор
                    // название», а не молчим и не показываем один трек.
                    val fallbackQuery = listOf(
                        r.albumArtist.takeIf { it.isNotBlank() && !it.equals("various artists", true) }
                            ?: p.name.ifBlank { name }.takeIf { !isAppears }.orEmpty(),
                        r.title,
                    ).filter { it.isNotBlank() }.joinToString(" ").trim()
                    fun playRelease() {
                        scope.launch {
                            buffering = true
                            val q = app.settings.state.value.qualityFor(onWifi = true)
                            val ok = withTimeoutOrNull(25_000) {
                                if (r.url.isNotBlank()) ReleasePlayback.play(app.player, r.url, q, r.coverUrl)
                                else ReleasePlayback.playSearch(
                                    app.player, fallbackQuery, q, r.coverUrl,
                                    // Без имени артиста подмена запрещена — см. playSearch.
                                    expectArtist = p.name.ifBlank { name },
                                )
                            } ?: false
                            buffering = false
                            if (!ok && r.url.isNotBlank()) onOpenAlbum(cd)
                        }
                    }
                    ReleaseCard(
                        data = cd,
                        queued = queued[r.url] == true,
                        buffering = buffering,
                        onOpen = { if (r.url.isNotBlank()) onOpenAlbum(cd) else playRelease() },
                        onDownload = {
                            if (r.url.isNotBlank()) {
                                queued[r.url] = true
                                scope.launch {
                                    runCatching {
                                        val sel = ServiceRegistry.all().firstNotNullOfOrNull { it.resolve(r.url) }
                                        sel?.tracks?.forEach { app.downloads.enqueue(it) }
                                    }
                                }
                            }
                        },
                        onPlay = if (r.url.isBlank() && fallbackQuery.isBlank()) null else {
                            { playRelease() }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(name: String, picture: String?, c: net.ripster.mobile.ui.theme.RipsterColors, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).border(1.dp, c.border_subtle, CircleShape)
                .background(c.surface_raised).clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) { BasicText("‹", style = TextStyle(color = c.text_secondary, fontSize = 20.sp)) }
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.surface_active)) {
            Cover(url = picture, modifier = Modifier.fillMaxSize(), shape = CircleShape)
        }
        BasicText(
            name, maxLines = 1,
            style = TextStyle(color = c.text_primary, fontSize = 19.sp, fontWeight = FontWeight.Bold),
        )
    }
}

private fun normType(t: String) = when (t.lowercase()) {
    "single" -> "single"
    "ep" -> "ep"
    "compilation", "compile" -> "compilation"
    "live" -> "live"
    else -> "album"
}
private fun groupLabelKey(t: String) = when (t) {
    "single" -> "art.singles"
    "ep" -> "art.eps"
    // «сборник» в дискографии = чужой релиз с треком артиста → называем честно
    "compilation" -> "art.appears_group"
    "live" -> "art.live"
    else -> "art.albums"
}

/**
 * Нет ПК / artist_id — собираем «дискографию» из поиска по имени. Мобильные
 * клиенты в `search()` отдают ТРЕКИ (без альбомов), поэтому группируем треки
 * по названию альбома: одна запись на альбом, обложка/год — от первого трека.
 */
/** Обложка артиста из публичного Deezer (без ключа) — чтобы в шапке был не
 *  обезличенный кружок. Deezer отдаёт нормальные фото артистов. */
private suspend fun deezerArtistPic(name: String): String? = runCatching {
    kotlinx.coroutines.withTimeoutOrNull(8_000) {
        val u = "https://api.deezer.com/search/artist?limit=1&q=" +
            java.net.URLEncoder.encode(name, "UTF-8")
        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            net.ripster.mobile.core.net.RipsterHttp.client
                .newCall(okhttp3.Request.Builder().url(u).build())
                .execute().use { it.body?.string().orEmpty() }
        }
        val a = kotlinx.serialization.json.Json.parseToJsonElement(body)
            .jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@withTimeoutOrNull null
        (a["picture_xl"] ?: a["picture_big"] ?: a["picture_medium"])
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}.getOrNull()

private suspend fun searchFallback(name: String): PcBridge.ArtistPage {
    val clients = listOf(Service.DEEZER, Service.QOBUZ, Service.TIDAL, Service.SOUNDCLOUD)
        .mapNotNull { ServiceRegistry.get(it) }
    val pic = coroutineScope { async { deezerArtistPic(name) } }
    val sels = coroutineScope {
        clients.map { c ->
            async {
                // Потолок на КАЖДЫЙ сервис: без него один зависший login/
                // ensureToken держал экран артиста в «анализирует…» вечно
                // (awaitAll ждал самого медленного).
                runCatching {
                    withTimeoutOrNull(12_000) { c.search(name) }
                }.getOrNull()
            }
        }.awaitAll()
    }.filterNotNull()

    val want = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
    fun nameMatches(a: String): Boolean {
        val got = a.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        return a.contains(name, true) || name.contains(a, true) ||
            (want.isNotEmpty() && want.any { it in got })
    }

    // ключ = только название (без сервиса) — один альбом, не дубль qobuz/tidal
    val byAlbum = LinkedHashMap<String, PcBridge.ArtistRelease>()
    // 1) собственные релизы артиста (альбом в выдаче кредитован на него)
    sels.flatMap { it.albums }.forEach { a ->
        if (!nameMatches(a.artist)) return@forEach
        val k = a.title.trim().lowercase()
        byAlbum.getOrPut(k) {
            PcBridge.ArtistRelease(
                id = a.id, title = a.title, coverUrl = a.artworkUrl,
                year = a.year?.toString().orEmpty(), date = a.year?.toString().orEmpty(),
                trackCount = a.trackCount,
                type = if ((a.trackCount ?: 99) in 1..3) "single" else "album",
                url = albumUrl(a.service.id, a.id), service = a.service.id,
            )
        }
    }
    // 2) участие: трек артиста лежит в чужом релизе (сборник / DJ-микс / сплит).
    //    Ключ по названию альбома; помечаем appearsAs = название трека, а тип —
    //    compilation, если у трека есть альбом-артист и он НЕ этот артист.
    sels.flatMap { it.tracks }.forEach { t ->
        val al = t.albumTitle?.takeIf { it.isNotBlank() } ?: return@forEach
        if (!nameMatches(t.artist)) return@forEach
        val k = al.trim().lowercase()
        if (byAlbum.containsKey(k)) return@forEach          // уже как собственный релиз
        val albId = t.raw["albId"]?.takeIf { it.isNotBlank() && it != "0" }
        // В выдаче ПОИСКА album_artist почти всегда = сам артист (сервисы так
        // отдают), поэтому одного его мало. Плюс — эвристика по названию: серии
        // и радио-компиляции узнаются по имени.
        val compHint = Regex(
            """(?i)\b(vol\.?\s*\d+|radio|mansion|sessions?|selected|mixed|dj[ -]?mix|compilation|present[s]?|pres\.|anjunadeep\s*\d+|caf[eé] del mar|all day i dream|this never happened|best of|various)\b""",
        ).containsMatchIn(al)
        val ownRelease = !compHint &&
            (t.albumArtist.isNullOrBlank() || nameMatches(t.albumArtist!!))
        byAlbum[k] = PcBridge.ArtistRelease(
            id = albId.orEmpty(), title = al, coverUrl = t.artworkUrl,
            year = t.year?.toString().orEmpty(), date = t.year?.toString().orEmpty(),
            trackCount = null,
            type = if (ownRelease) "album" else "compilation",
            url = albumUrl(t.service.id, albId.orEmpty()),
            service = t.service.id,
            appearsAs = if (ownRelease) "" else t.title,
            albumArtist = if (ownRelease) "" else (t.albumArtist ?: "").trim(),
        )
    }
    // порядок: сначала собственные релизы, потом участия — и то и другое по годам
    val ordered = byAlbum.values.sortedWith(
        compareBy({ it.type == "compilation" }, { -(it.year.toIntOrNull() ?: 0) }),
    )
    return PcBridge.ArtistPage(
        name = name,
        pictureUrl = pic.await(),
        releases = ordered,
    )
}

/** URL релиза для `resolve()` / `AlbumScreen` из id + сервиса. */
private fun albumUrl(serviceId: String, id: String): String {
    if (id.isBlank() || id == "0") return ""
    return when (serviceId) {
        "deezer" -> "https://www.deezer.com/album/$id"
        "qobuz" -> "https://open.qobuz.com/album/$id"
        "tidal" -> "https://listen.tidal.com/album/$id"
        "yandex" -> "https://music.yandex.ru/album/$id"
        "soundcloud" -> id.takeIf { it.startsWith("http") }.orEmpty()
        else -> ""
    }
}
