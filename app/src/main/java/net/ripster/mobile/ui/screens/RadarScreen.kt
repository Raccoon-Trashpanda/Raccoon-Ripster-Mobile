@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package net.ripster.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.pair.PcBridge
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.ui.components.rememberReleaseCover
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Радар — витрина отслеживаемых артистов ПК-версии. Своего краулера на телефоне
 * нет (это большой ban-aware слой per-service); телефон в паре забирает
 * посчитанный вотчлист через `/api/pair/radar`.
 *
 * Экран: лента релизов орб-карточками (по канвасу скина Neon). Кнопка «⚙» в
 * шапке открывает настройки радара + полный список артистов.
 */
@Composable
fun RadarScreen(
    modifier: Modifier = Modifier,
    onOpenAlbum: (net.ripster.mobile.ui.components.ReleaseCardData) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenArtist: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenLabel: (String) -> Unit = {},
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val scope = rememberCoroutineScope()
    val bridge = app.pcBridge
    val queued = remember { mutableStateMapOf<String, Boolean>() }
    var helpOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // rememberSaveable — фильтр и поиск не сбрасываются при переключении вкладок.
    var query by rememberSaveable { mutableStateOf("") }
    var svcFilter by rememberSaveable { mutableStateOf<String?>(null) }

    if (!bridge.paired) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            BasicText(tr("radar.need_pair", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
        }
        return
    }

    val state by produceState<Result<List<PcBridge.RadarItem>>?>(initialValue = null) {
        value = bridge.radar()
    }

    Column(modifier.fillMaxSize().background(c.surface_canvas)) {
        // ── шапка ──
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(tr("nav.radar", lang), style = TextStyle(color = c.text_primary, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            HelpDot(open = helpOpen, c = c) { helpOpen = !helpOpen }
            Box(Modifier.weight(1f))
            IconBtn(c) { settingsOpen = true }
        }
        if (helpOpen) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(c.surface_raised, RoundedCornerShape(10.dp))
                    .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp)).padding(14.dp),
            ) {
                BasicText(tr("radar.explain", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp, lineHeight = 17.sp))
            }
        }

        val res = state
        when {
            res == null -> Centered(tr("radar.loading", lang), c)
            res.isFailure -> Centered(tr("radar.err", lang) + ": " + (res.exceptionOrNull()?.message ?: ""), c)
            else -> {
                val all = res.getOrDefault(emptyList())
                val allReleases = all.filter { it.latestUrl.isNotBlank() }.sortedByDescending { it.lastCheck ?: "" }
                val services = remember(allReleases) {
                    allReleases.map { it.service.lowercase().trim() }
                        .filter { it.isNotBlank() && it !in setOf("auto", "radar", "watchlist", "manual", "?") }
                        .distinct().sorted()
                }
                val releases = allReleases
                    .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    .filter { svcFilter == null || it.service.lowercase().trim() == svcFilter }

                // поиск по артисту
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                        .background(c.surface_raised, RoundedCornerShape(10.dp))
                        .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (query.isEmpty()) {
                        BasicText(tr("radar.search_hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
                    }
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = c.text_primary, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // выбор сервиса
                if (services.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterPill(tr("search.pick_all", lang), svcFilter == null, c) { svcFilter = null }
                        services.forEach { s ->
                            FilterPill(s, svcFilter == s, c) { svcFilter = if (svcFilter == s) null else s }
                        }
                    }
                }
                BasicText(
                    tr("radar.following", lang) + ": " + releases.size,
                    Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp),
                    style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                )
                if (releases.isEmpty()) {
                    Centered(tr("radar.empty", lang), c)
                } else {
                    // Лента сгруппирована по дате (для Spotify-записей last_check —
                    // это дата релиза): заголовок даты «липнет» сверху, пока идут
                    // релизы этой даты, затем сменяется следующей. Строка = пара
                    // карточек. Справа — быстрый ползунок.
                    val groups = remember(releases) {
                        releases.groupBy { dateKey(it.lastCheck) }
                            .toList()
                            .sortedByDescending { it.first }          // «—» (пустая) уедет вниз
                            .map { (k, v) -> k to v.chunked(2) }
                    }
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 14.dp, end = 14.dp, top = 4.dp, bottom = 120.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            groups.forEach { (key, rows) ->
                                stickyHeader(key = "h-$key") {
                                    Box(
                                        Modifier.fillMaxWidth().background(c.surface_canvas)
                                            .padding(top = 6.dp, bottom = 6.dp),
                                    ) {
                                        Box(
                                            Modifier.clip(RoundedCornerShape(999.dp))
                                                .background(c.surface_raised)
                                                .border(1.dp, c.border_subtle, RoundedCornerShape(999.dp))
                                                .padding(horizontal = 12.dp, vertical = 5.dp),
                                        ) {
                                            BasicText(
                                                dateLabel(key, lang),
                                                style = TextStyle(color = c.text_secondary, fontSize = 11.sp, fontWeight = FontWeight.W600),
                                            )
                                        }
                                    }
                                }
                                items(rows, key = { it.joinToString { r -> r.name + r.service } }) { pair ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        pair.forEach { item ->
                                            Box(Modifier.weight(1f)) { RadarReleaseTile(item, queued, app, scope, onOpenAlbum, onOpenArtist, onOpenLabel) }
                                        }
                                        if (pair.size == 1) Box(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        ListScrollbar(listState, c)
                    }
                }
            }
        }
    }

    // ── настройки радара + артисты (внутри радара) ──
    if (settingsOpen) {
        androidx.activity.compose.BackHandler(true) { settingsOpen = false }
        val all = state?.getOrNull().orEmpty()
        var aQuery by rememberSaveable { mutableStateOf("") }
        var aKind by rememberSaveable { mutableStateOf(0) }      // 0 все · 1 артисты · 2 лейблы
        var aSvc by rememberSaveable { mutableStateOf<String?>(null) }

        val aServices = remember(all) {
            all.map { it.service.lowercase().trim() }
                .filter { it.isNotBlank() && it !in setOf("auto", "radar", "watchlist", "manual", "?") }
                .distinct().sorted()
        }
        val filtered = all
            .filter { aQuery.isBlank() || it.name.contains(aQuery.trim(), ignoreCase = true) }
            .filter { aKind == 0 || (aKind == 1 && it.kind != "label") || (aKind == 2 && it.kind == "label") }
            .filter { aSvc == null || it.service.lowercase().trim() == aSvc }
        val letterGroups = filtered.sortedBy { it.name.lowercase() }
            .groupBy { letterKey(it.name) }.toList().sortedBy { it.first }

        Column(Modifier.fillMaxSize().background(c.surface_canvas)) {
            Row(
                Modifier.fillMaxWidth().clickable { settingsOpen = false }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("‹", style = TextStyle(color = c.text_secondary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(10.dp))
                BasicText(tr("radar.open_settings", lang), style = TextStyle(color = c.text_primary, fontSize = 17.sp, fontWeight = FontWeight.W700))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))

            // поиск по имени
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                    .background(c.surface_raised, RoundedCornerShape(10.dp))
                    .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (aQuery.isEmpty()) {
                    BasicText(tr("radar.search_hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
                }
                BasicTextField(
                    value = aQuery, onValueChange = { aQuery = it }, singleLine = true,
                    textStyle = TextStyle(color = c.text_primary, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // вид: все / артисты / лейблы
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterPill(tr("radar.kind_all", lang), aKind == 0, c) { aKind = 0 }
                FilterPill(tr("radar.kind_artists", lang), aKind == 1, c) { aKind = 1 }
                FilterPill(tr("radar.kind_labels", lang), aKind == 2, c) { aKind = 2 }
            }
            // сервис
            if (aServices.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterPill(tr("search.pick_all", lang), aSvc == null, c) { aSvc = null }
                    aServices.forEach { s -> FilterPill(s, aSvc == s, c) { aSvc = if (aSvc == s) null else s } }
                }
            }
            BasicText(
                tr("radar.artists", lang) + ": " + filtered.size + (if (filtered.size != all.size) " / " + all.size else ""),
                Modifier.padding(start = 18.dp, top = 4.dp, bottom = 4.dp),
                style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
            )

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .background(c.surface_raised, RoundedCornerShape(10.dp))
                            .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp)).padding(14.dp),
                    ) {
                        BasicText(tr("radar.settings_pc_note", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp, lineHeight = 17.sp))
                    }
                }
                letterGroups.forEach { (letter, arts) ->
                    stickyHeader(key = "L-$letter") {
                        Box(
                            Modifier.fillMaxWidth().background(c.surface_canvas)
                                .padding(start = 18.dp, top = 8.dp, bottom = 4.dp),
                        ) {
                            BasicText(
                                letter,
                                style = TextStyle(color = c.accent_text, fontSize = 12.sp, fontWeight = FontWeight.W800, letterSpacing = 1.sp),
                            )
                        }
                    }
                    items(arts, key = { it.name + it.service + it.kind + "s" }) { a ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    settingsOpen = false
                                    if (a.kind == "label") onOpenLabel(a.name)
                                    else onOpenArtist(a.name, a.service, a.artistId)
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(a.name, maxLines = 1, style = TextStyle(color = c.text_primary, fontSize = 13.sp, fontWeight = FontWeight.W600))
                                BasicText(
                                    buildString {
                                        if (a.kind == "label") append(tr("radar.kind_label_one", lang)).append("  ·  ")
                                        append(a.service.ifBlank { "auto" })
                                        append("  ·  ").append(tr("radar.checked", lang)).append(' ').append(relTime(a.lastCheck, lang))
                                    },
                                    style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                                )
                            }
                            if (a.auto) {
                                BasicText(
                                    tr("radar.auto_on", lang),
                                    style = TextStyle(color = c.accent_text.copy(alpha = 0.85f), fontSize = 10.sp),
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))
                    }
                }
            }
        }
    }
}


@Composable
private fun FilterPill(label: String, on: Boolean, c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (on) c.surface_active else c.surface_raised)
            .border(1.dp, if (on) c.accent_text else c.border_subtle, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(label, style = TextStyle(color = if (on) c.accent_text else c.text_tertiary, fontSize = 11.sp))
    }
}

/** Тонкий индикатор прокрутки справа у сетки (у LazyGrid своего нет). */
@Composable
private fun BoxScope.GridScrollbar(
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    c: net.ripster.mobile.ui.theme.RipsterColors,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible == 0 || visible >= total) return
    val thumbFrac = (visible.toFloat() / total).coerceIn(0.08f, 1f)
    val offFrac = (state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp)
            .padding(vertical = 8.dp),
    ) {
        val h = size.height
        val thumbH = (h * thumbFrac).coerceAtLeast(28f)
        drawRoundRect(
            color = c.border_strong,
            topLeft = Offset(0f, (h - thumbH) * offFrac),
            size = Size(size.width, thumbH),
            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
        )
    }
}

/** Тот же тонкий ползунок, но для LazyColumn (быстрая протяжка ленты радара). */
@Composable
private fun BoxScope.ListScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    c: net.ripster.mobile.ui.theme.RipsterColors,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible == 0 || visible >= total) return
    val thumbFrac = (visible.toFloat() / total).coerceIn(0.06f, 1f)
    val offFrac = (state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp).padding(vertical = 8.dp),
    ) {
        val h = size.height
        val thumbH = (h * thumbFrac).coerceAtLeast(28f)
        drawRoundRect(
            color = c.border_strong,
            topLeft = Offset(0f, (h - thumbH) * offFrac),
            size = Size(size.width, thumbH),
            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
        )
    }
}

/** Одна карточка релиза радара (вынесена из ленты, чтобы строка = пара плиток). */
@Composable
private fun RadarReleaseTile(
    item: PcBridge.RadarItem,
    queued: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    app: RipsterApp,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenAlbum: (net.ripster.mobile.ui.components.ReleaseCardData) -> Unit,
    onOpenArtist: (String, String, String) -> Unit,
    onOpenLabel: (String) -> Unit,
) {
    // Обложка: сначала готовая с ПК (Spotify отдаёт её сразу), иначе — скрейп по ссылке.
    val scraped = rememberReleaseCover(if (item.coverUrl != null) "" else item.latestUrl)
    val cover = item.coverUrl ?: scraped
    val cd = net.ripster.mobile.ui.components.ReleaseCardData(
        title = item.name, artist = item.name,
        service = item.service.ifBlank { "radar" },
        url = item.latestUrl, coverUrl = cover, isNew = true, dateText = null,
    )
    net.ripster.mobile.ui.components.ReleaseCard(
        data = cd,
        queued = queued[item.latestUrl] == true,
        onArtist = {
            if (item.kind == "label") onOpenLabel(item.name)
            else onOpenArtist(item.name, item.service, item.artistId)
        },
        onOpen = { if (item.latestUrl.isNotBlank()) onOpenAlbum(cd) },
        onDownload = {
            if (item.latestUrl.isNotBlank()) {
                queued[item.latestUrl] = true
                scope.launch { grabUrl(app, item.latestUrl) }
            }
        },
        onPlay = if (item.latestUrl.isBlank()) null else {
            {
                scope.launch {
                    val q = app.settings.state.value.qualityFor(onWifi = true)
                    val ok = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        net.ripster.mobile.core.service.ReleasePlayback.play(app.player, item.latestUrl, q)
                    } ?: false
                    if (!ok && item.latestUrl.isNotBlank()) onOpenAlbum(cd)   // не сыграло → откроем релиз
                }
            }
        },
    )
}

/** Первая буква имени для липкого алфавита; цифры/символы → «#». */
private fun letterKey(name: String): String {
    val ch = name.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (ch.isLetter()) ch.toString() else "#"
}

/** Ключ группы = дата (yyyy-MM-dd) из last_check; пусто → «zzz» уедет вниз. */
private fun dateKey(iso: String?): String {
    val d = iso?.take(10)?.trim().orEmpty()
    return if (d.length == 10 && d[4] == '-') d else "zzzz"
}

/** Человеческий заголовок даты: сегодня / вчера / 12 авг 2026. */
private fun dateLabel(key: String, lang: net.ripster.mobile.ui.i18n.AppLang): String {
    if (key == "zzzz") return tr("radar.date_unknown", lang)
    val d = runCatching { java.time.LocalDate.parse(key) }.getOrNull() ?: return key
    val today = java.time.LocalDate.now()
    return when (d) {
        today -> tr("radar.date_today", lang)
        today.minusDays(1) -> tr("radar.date_yesterday", lang)
        else -> {
            val loc = when (lang.name.lowercase()) {
                "ru" -> java.util.Locale("ru"); "ja" -> java.util.Locale.JAPAN
                "zh" -> java.util.Locale.CHINA; "hi" -> java.util.Locale("hi"); else -> java.util.Locale.ENGLISH
            }
            d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", loc))
        }
    }
}

/** Кружок «?» — раскрывает/прячет пояснение. Общий приём для всех экранов. */
@Composable
fun HelpDot(open: Boolean, c: net.ripster.mobile.ui.theme.RipsterColors, onToggle: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(if (open) c.surface_active else c.surface_raised)
            .border(1.dp, if (open) c.accent_text else c.border_subtle, CircleShape)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "?",
            style = TextStyle(color = if (open) c.accent_text else c.text_tertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun IconBtn(c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(c.surface_raised)
            .border(1.dp, c.border_subtle, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width
            drawCircle(c.text_secondary, radius = w * 0.30f, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.12f))
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45).toDouble())
                drawLine(
                    c.text_secondary,
                    Offset(w / 2 + (w * 0.34f) * Math.cos(a).toFloat(), w / 2 + (w * 0.34f) * Math.sin(a).toFloat()),
                    Offset(w / 2 + (w * 0.5f) * Math.cos(a).toFloat(), w / 2 + (w * 0.5f) * Math.sin(a).toFloat()),
                    strokeWidth = w * 0.11f,
                )
            }
        }
    }
}

@Composable
private fun Centered(text: String, c: net.ripster.mobile.ui.theme.RipsterColors) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        BasicText(text, style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
    }
}

/** Разобрать ссылку любым настроенным клиентом (или Apple-прокси) и поставить в очередь. */
private suspend fun grabUrl(app: RipsterApp, url: String) {
    runCatching {
        val sel = ServiceRegistry.all().firstNotNullOfOrNull { it.resolve(url) } ?: return
        sel.tracks.forEach { app.downloads.enqueue(it) }
    }
}

private fun relTime(iso: String?, lang: net.ripster.mobile.ui.i18n.AppLang): String {
    if (iso.isNullOrBlank()) return tr("radar.never", lang)
    // может прийти как datetime (вотчлист) или просто дата yyyy-MM-dd (Spotify)
    val instant = runCatching {
        java.time.LocalDateTime.parse(iso.take(19)).atZone(java.time.ZoneId.systemDefault()).toInstant()
    }.getOrNull() ?: runCatching {
        java.time.LocalDate.parse(iso.take(10)).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
    }.getOrNull() ?: return iso.take(10)
    val mins = ((java.time.Instant.now().toEpochMilli() - instant.toEpochMilli()) / 60000)
    return when {
        mins < 0 -> iso.take(10)          // релиз в будущем (предзаказ) — показываем дату
        mins < 60 -> "${mins}m"
        mins < 1440 -> "${mins / 60}h"
        else -> "${mins / 1440}d"
    }
}
