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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        // 1. с ПК (зрелый get_artist / релизы лейбла)
        var pcPage: PcBridge.ArtistPage? = null
        if (app.pcBridge.paired && (isLabel || artistId.isNotBlank())) {
            pcPage = withTimeoutOrNull(25_000) {
                if (isLabel) app.pcBridge.label(name) else app.pcBridge.artist(service, artistId)
            }?.getOrNull()
            if (pcPage != null && pcPage.error == null && pcPage.releases.isNotEmpty()) {
                value = pcPage; return@produceState
            }
        }
        // 2. фолбэк: поиск по имени в «простых» сервисах (для лейбла слабее, но лучше пустоты)
        val fb = searchFallback(name)
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
            groups.forEach { (type, rels) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BasicText(
                        tr(groupLabelKey(type), lang) + "  ·  " + rels.size,
                        Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                        style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.W700, letterSpacing = 1.sp),
                    )
                }
                items(rels, key = { it.service + "|" + it.title.lowercase() + "|" + it.id }) { r ->
                    val cd = ReleaseCardData(
                        title = r.title,
                        artist = p.name.ifBlank { name },
                        service = r.service,
                        url = r.url,
                        type = type,
                        coverUrl = r.coverUrl,
                        trackCount = r.trackCount,
                        dateText = r.year.ifBlank { r.date.take(4) },
                    )
                    ReleaseCard(
                        data = cd,
                        queued = queued[r.url] == true,
                        onOpen = { if (r.url.isNotBlank()) onOpenAlbum(cd) },
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
                        onPlay = if (r.url.isBlank()) null else {
                            {
                                scope.launch {
                                    val q = app.settings.state.value.qualityFor(onWifi = true)
                                    val ok = withTimeoutOrNull(25_000) {
                                        ReleasePlayback.play(app.player, r.url, q)
                                    } ?: false
                                    if (!ok) onOpenAlbum(cd)
                                }
                            }
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
    "compilation" -> "art.comps"
    "live" -> "art.live"
    else -> "art.albums"
}

/**
 * Нет ПК / artist_id — собираем «дискографию» из поиска по имени. Мобильные
 * клиенты в `search()` отдают ТРЕКИ (без альбомов), поэтому группируем треки
 * по названию альбома: одна запись на альбом, обложка/год — от первого трека.
 */
private suspend fun searchFallback(name: String): PcBridge.ArtistPage {
    val clients = listOf(Service.DEEZER, Service.QOBUZ, Service.TIDAL, Service.SOUNDCLOUD)
        .mapNotNull { ServiceRegistry.get(it) }
    val sels = coroutineScope {
        clients.map { c -> async { runCatching { c.search(name) }.getOrNull() } }.awaitAll()
    }.filterNotNull()

    val want = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
    fun nameMatches(a: String): Boolean {
        val got = a.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        return a.contains(name, true) || name.contains(a, true) ||
            (want.isNotEmpty() && want.any { it in got })
    }

    // ключ = только название (без сервиса) — один альбом, не дубль qobuz/tidal
    val byAlbum = LinkedHashMap<String, PcBridge.ArtistRelease>()
    // 1) реальные альбомы, если сервис их всё-таки вернул
    sels.flatMap { it.albums }.forEach { a ->
        if (!nameMatches(a.artist)) return@forEach
        val k = a.title.trim().lowercase()
        byAlbum.getOrPut(k) {
            PcBridge.ArtistRelease(
                id = a.id, title = a.title, coverUrl = a.artworkUrl,
                year = a.year?.toString().orEmpty(), date = a.year?.toString().orEmpty(),
                trackCount = a.trackCount,
                type = if ((a.trackCount ?: 99) in 1..3) "single" else "album",
                url = "", service = a.service.id,
            )
        }
    }
    // 2) из треков — по названию альбома
    sels.flatMap { it.tracks }.forEach { t ->
        val al = t.albumTitle?.takeIf { it.isNotBlank() } ?: return@forEach
        if (!nameMatches(t.artist)) return@forEach
        val k = al.trim().lowercase()
        byAlbum.getOrPut(k) {
            PcBridge.ArtistRelease(
                id = "", title = al, coverUrl = t.artworkUrl,
                year = t.year?.toString().orEmpty(), date = t.year?.toString().orEmpty(),
                trackCount = null, type = "album", url = "", service = t.service.id,
            )
        }
    }
    return PcBridge.ArtistPage(
        name = name, pictureUrl = null,
        releases = byAlbum.values.sortedByDescending { it.year },
    )
}
