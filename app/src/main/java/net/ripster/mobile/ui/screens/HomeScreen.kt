package net.ripster.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.ui.components.Cover
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.navigation.RipsterDestination
import net.ripster.mobile.ui.theme.RipsterColors
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Главная — точка входа. Два ЯВНО разных источника (не одна размытая лента):
 *  • «Из вашей коллекции» — реально скачанное, зелёный бейдж «Скачано» на
 *    обложке (обещание «сыграет офлайн», не догадка алгоритма);
 *  • «Рекомендуем послушать» — из стриминга, бейдж сервиса + обоснование по
 *    истории скачиваний/прослушиваний.
 * Плюс «Продолжить прослушивание», «Новые релизы» (радар), «Источники» и
 * компактная ссылка в «Раскопки». Дизайн: ripster-neon-skin_2 / Home.dc.html.
 * Все строки — через tr(), без хардкода.
 */
@Composable
fun HomeScreen(
    onOpen: (RipsterDestination) -> Unit,
    onOpenSearchQuery: (String) -> Unit = {},
    onOpenAlbum: (net.ripster.mobile.ui.components.ReleaseCardData) -> Unit = {},
    onOpenArtist: (String, String, String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors

    val scope = rememberCoroutineScope()
    val library by app.db.library().observeAll().collectAsState(initial = emptyList())
    val pb by app.player.state.collectAsState()
    val playedByGenre by app.db.plays().observeByGenre().collectAsState(initial = emptyList())
    val downloads by app.db.downloads().observeAll().collectAsState(initial = emptyList())

    // Экран должен выглядеть по-разному при каждом заходе — иначе «Главная
    // мёртвая». Один seed на открытие экрана: им тасуем витрины (коллекция,
    // рекомендации, станции), но не порядок недавнего/загрузок (там смысл в
    // хронологии). meue меняется при возврате на Home, не при каждом кадре.
    val visitSeed = remember { System.nanoTime() }
    // Подписки локального радара нужны выше по коду, чем объявлен localWatches.
    val localWatchesForSc by app.localRadar.feed().collectAsState(initial = emptyList())

    // Свежие миксы BBC — автономный модуль, грузится один раз за заход.
    // Сеть недоступна → список пуст → секции просто нет.
    val bbcMixes by androidx.compose.runtime.produceState(
        initialValue = emptyList<net.ripster.mobile.core.bbc.BbcShows.Mix>(),
    ) {
        value = runCatching { net.ripster.mobile.core.bbc.BbcShows.latest() }.getOrDefault(emptyList())
    }

    // SoundCloud: новое у аккаунтов, на которые подписан ПРЯМО В ТЕЛЕФОНЕ.
    // Подписки лежат в том же локальном радаре, что и артисты других сервисов,
    // поэтому ПК для этой секции не нужен.
    val scFollowIds = remember(localWatchesForSc) {
        localWatchesForSc.filter { it.serviceId == "soundcloud" && it.artistId.isNotBlank() }
            .map { it.artistId }
    }
    val scFeed by androidx.compose.runtime.produceState(
        initialValue = emptyList<net.ripster.mobile.core.model.Track>(),
        scFollowIds,
    ) {
        value = runCatching {
            net.ripster.mobile.core.soundcloud.ScFeed.latest(scFollowIds)
        }.getOrDefault(emptyList())
    }

    // Активные и упавшие загрузки — то, что делает Главную «живой»: пока что-то
    // качается, экран меняется сам.
    val dlActive = remember(downloads) {
        downloads.filter { it.state == "RUNNING" || it.state == "QUEUED" }
    }
    val dlFailed = remember(downloads) {
        downloads.filter { it.state == "FAILED" }.take(3)
    }
    // Обложка карточки загрузки: у DownloadEntity своего поля обложки нет, но в
    // trackJson лежит весь сериализованный Track с artworkUrl. Раньше сюда
    // жёстко шёл art=null → в секции FAILED DOWNLOADS всегда пустые серые
    // плитки (жалоба 03.09.2026, видео). id → url, разобрано один раз на выборку.
    val dlArt = remember(dlActive, dlFailed) {
        val j = Json { ignoreUnknownKeys = true }
        (dlActive + dlFailed).associate { d ->
            d.id to runCatching {
                j.decodeFromString(net.ripster.mobile.core.model.Track.serializer(), d.trackJson).artworkUrl
            }.getOrNull()
        }
    }

    // Недавно добавленное в библиотеку — по времени добавления, не по алфавиту.
    val recentlyAdded = remember(library) {
        library.sortedByDescending { it.addedAt }.take(10)
    }
    // Витрина коллекции — случайная выборка на этот заход, не первые 12.
    val collectionShow = remember(library, visitSeed) {
        library.shuffled(kotlin.random.Random(visitSeed)).take(12)
    }
    // Жанровые станции — крутим 10 из полного набора за заход.
    val waveShow = remember(visitSeed) {
        WAVE_STATIONS.shuffled(kotlin.random.Random(visitSeed xor 0x9E3779B9L)).take(10)
    }
    var building by remember { mutableStateOf<String?>(null) }   // id станции в процессе сборки
    var stationMsg by remember { mutableStateOf<String?>(null) }

    // Локальный радар (без ПК) — за кем следит сам телефон.
    val localWatches by app.localRadar.feed().collectAsState(initial = emptyList())
    val pcRadar by produceState<List<net.ripster.mobile.core.pair.PcBridge.RadarItem>>(initialValue = emptyList()) {
        value = if (!app.pcBridge.paired) emptyList()
        else runCatching { app.pcBridge.radar().getOrDefault(emptyList()) }.getOrDefault(emptyList())
    }
    val radarAll = remember(localWatches, pcRadar) {
        val local = localWatches.filter { it.latestUrl.isNotBlank() }.map { w ->
            net.ripster.mobile.core.pair.PcBridge.RadarItem(
                name = w.name, service = w.serviceId, lastCheck = w.latestDate.ifBlank { null },
                latestUrl = w.latestUrl, auto = false, seenCount = 0, kind = w.kind,
                coverUrl = w.latestCoverUrl ?: w.coverUrl, artistId = w.artistId, date = w.latestDate,
            )
        }
        val names = local.map { it.name.lowercase() }.toSet()
        local + pcRadar.filter { it.name.lowercase() !in names }
    }
    // Витрина «новые релизы»: только то, что реально можно проиграть — релиз с
    // датой в будущем ни один поток ещё не отдаёт (напр. Spotify-радар владельца
    // видит анонс за неделю). Плюс перемешиваем за заход, как коллекцию/станции.
    val radar = remember(radarAll, visitSeed) {
        val today = java.time.LocalDate.now().toString()   // ISO, лексикографически сравнимо
        radarAll
            .filter { it.latestUrl.isNotBlank() }
            .filter { it.date.isBlank() || it.date <= today }
            .shuffled(kotlin.random.Random(visitSeed xor 0x2545F4914F6CDD1DL))
            .take(12)
    }
    // «Рекомендуем» — простая честная эвристика: артисты из вашей коллекции,
    // где скачано не всё. Тап — поиск этого артиста в выбранных сервисах.
    val recos = remember(library, visitSeed) {
        library.groupBy { it.artist.ifBlank { it.title } }
            .entries.filter { it.key.isNotBlank() }
            .sortedByDescending { it.value.size }
            .take(15)                                   // релевантный пул
            .shuffled(kotlin.random.Random(visitSeed xor 0x27D4EB2FL))
            .take(6)                                    // но каждый заход другой срез
            .map { it.key to it.value.size }
    }

    // Амбиент по макету Home.dc.html: маджента-свечение сверху-справа,
    // фиолетовое снизу-слева, near-black основа. Рисуем на самом экране, чтобы
    // Главная выглядела «как рендер» даже без играющего трека.
    Box(
        modifier.fillMaxSize().background(c.surface_canvas).drawBehind {
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Color(0x33FF4D8F), Color(0x00FF4D8F)),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.82f, -size.height * 0.10f),
                    radius = size.width * 1.35f,
                ),
            )
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Color(0x29A238FF), Color(0x00A238FF)),
                    center = androidx.compose.ui.geometry.Offset(-size.width * 0.08f, size.height * 1.08f),
                    radius = size.width * 1.25f,
                ),
            )
        },
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // запас снизу, чтобы последние секции можно было пролистать выше
        // мини-плеера и нижней навигации (они висят отдельным слоем).
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp),
    ) {
        item("greet") {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 4.dp)) {
                BasicText(tr("home.greeting", lang), style = TextStyle(color = c.text_primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(6.dp))
                BasicText(tr("home.greeting_sub", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
            }
        }

        // ── Продолжить прослушивание ──────────────────────────────────────
        if (pb.hasItem) {
            item("resume") {
                Section(tr("home.resume", lang), c) { onOpen(RipsterDestination.Player) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    WideCard(
                        title = pb.title, subtitle = pb.artist, art = pb.artworkUrl,
                        progress = if (pb.durationMs > 0) (pb.positionMs.toFloat() / pb.durationMs).coerceIn(0f, 1f) else 0f,
                        c = c, onClick = { onOpen(RipsterDestination.Player) },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Качается сейчас ──────────────────────────────────────────────
        if (dlActive.isNotEmpty() || dlFailed.isNotEmpty()) {
            item("downloading") {
                Section(
                    if (dlActive.isNotEmpty())
                        tr("home.downloading", lang) + "  ·  " + dlActive.size
                    else tr("home.dl_failed", lang),
                    c,
                ) { onOpen(RipsterDestination.Downloads) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    dlActive.take(8).forEach { d ->
                        WideCard(
                            title = d.title.ifBlank { tr("home.dl_track", lang) },
                            subtitle = d.artist.ifBlank {
                                if (d.state == "QUEUED") tr("home.dl_queued", lang) else ""
                            },
                            art = dlArt[d.id],
                            progress = (d.fraction ?: 0f).coerceIn(0f, 1f),
                            c = c, onClick = { onOpen(RipsterDestination.Downloads) },
                        )
                    }
                    dlFailed.forEach { d ->
                        WideCard(
                            title = d.title.ifBlank { tr("home.dl_track", lang) },
                            subtitle = (d.errorReason ?: tr("home.dl_failed", lang)).take(60),
                            art = dlArt[d.id], progress = 0f,
                            c = c, onClick = { onOpen(RipsterDestination.Downloads) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Из вашей коллекции ───────────────────────────────────────────
        if (library.isNotEmpty()) {
            item("collection") {
                Section(tr("home.collection", lang), c) { onOpen(RipsterDestination.Library) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    collectionShow.forEach { it0 ->
                        SquareCard(
                            title = it0.title,
                            subtitle = listOf(it0.artist, it0.container.uppercase()).filter { it.isNotBlank() }.joinToString(" · "),
                            art = it0.artworkUrl, downloaded = true, c = c, lang = lang,
                            onClick = {
                                val idx = library.indexOfFirst { x -> x.id == it0.id }.coerceAtLeast(0)
                                app.player.playQueue(library, idx); onOpen(RipsterDestination.Player)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Недавно добавленное ─────────────────────────────────────────
        if (recentlyAdded.size > 3) {
            item("recent_added") {
                Section(tr("home.recent_added", lang), c) { onOpen(RipsterDestination.Library) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    recentlyAdded.forEach { it0 ->
                        SquareCard(
                            title = it0.title,
                            subtitle = listOf(it0.artist, it0.container.uppercase()).filter { it.isNotBlank() }.joinToString(" · "),
                            art = it0.artworkUrl, downloaded = true, c = c, lang = lang,
                            onClick = {
                                val idx = library.indexOfFirst { x -> x.id == it0.id }.coerceAtLeast(0)
                                app.player.playQueue(library, idx); onOpen(RipsterDestination.Player)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Жанры и настроения — станции по жанру/настроению, тап собирает
        //    поток и включает. Тот же набор, что digs на ПК.
        item("wave") {
            Section(tr("home.wave", lang), c, null)
            BasicText(
                if (building != null) tr("wave.building", lang)
                else stationMsg ?: tr("home.wave_sub", lang),
                Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp),
                style = TextStyle(color = if (stationMsg != null) c.warning_text else c.text_disabled, fontSize = 11.sp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                waveShow.forEach { st ->
                    WaveTile(
                        name = if (st.nameKey != null) tr(st.nameKey, lang) else st.display,
                        seed = st.id, c = c, loading = building == st.id,
                        onClick = {
                            if (building != null) return@WaveTile
                            building = st.id; stationMsg = null
                            scope.launch {
                                val q = app.settings.state.value.qualityFor(onWifi = true)
                                val tracks = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                                    net.ripster.mobile.core.service.StationBuilder.build(st.scSlug, st.query, st.ya)
                                } ?: emptyList()
                                // Первые 6 — быстро, чтобы сразу заиграло; остальное дорезолвим фоном.
                                val head = net.ripster.mobile.core.service.StreamResolver
                                    .toStreamItems(tracks.take(6), q, limit = 6)
                                building = null
                                if (head.isEmpty()) { stationMsg = tr("wave.empty", lang); return@launch }
                                app.player.playStream(head)
                                onOpen(RipsterDestination.Player)
                                if (tracks.size > 6) {
                                    val tail = net.ripster.mobile.core.service.StreamResolver
                                        .toStreamItems(tracks.drop(6), q, limit = 24)
                                    app.player.appendStream(tail)
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── Рекомендуем послушать ────────────────────────────────────────
        if (recos.isNotEmpty()) {
            item("recos") {
                Section(tr("home.recommend", lang), c, null)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    recos.forEach { (artist, n) ->
                        RecoCard(
                            title = artist,
                            reason = tr("home.reco_reason", lang).replace("{artist}", artist).replace("{n}", n.toString()),
                            c = c, onClick = { onOpenSearchQuery(artist) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Раскопки — компактная ссылка ────────────────────────────────
        item("digs") {
            Box(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, c.border_subtle, RoundedCornerShape(14.dp))
                        .background(c.surface_raised).clickable { onOpen(RipsterDestination.Radar) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(width = 32.dp, height = 24.dp).clip(RoundedCornerShape(8.dp)).background(c.accent_fill))
                    BasicText(tr("home.digs", lang), Modifier.weight(1f), style = TextStyle(color = c.text_secondary, fontSize = 13.sp))
                    BasicText("→", style = TextStyle(color = c.text_tertiary, fontSize = 14.sp))
                }
            }
        }

        // ── Что игралось — память по жанрам (даже вне библиотеки) ───────
        if (playedByGenre.isNotEmpty()) {
            item("played") {
                Section(tr("home.played", lang), c, null)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    playedByGenre.take(12).forEach { p ->
                        Column(
                            Modifier.width(120.dp).clickable {
                                net.ripster.mobile.core.service.SearchBus.request(p.genre)
                                onOpen(RipsterDestination.Search)
                            },
                        ) {
                            Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(c.surface_active)) {
                                Cover(url = p.artworkUrl, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp))
                                Box(
                                    Modifier.fillMaxSize().background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                                        ),
                                    ),
                                )
                                BasicText(
                                    p.genre.replaceFirstChar { it.uppercase() },
                                    Modifier.align(Alignment.BottomStart).padding(10.dp),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Новые релизы (радар) — та же карточка, что в Радаре ─────────
        if (radar.isNotEmpty()) {
            item("releases") {
                Section(tr("home.new_releases", lang), c) { onOpen(RipsterDestination.Radar) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    radar.forEach { r ->
                        // готовая обложка с ПК приоритетнее; скрейп зовём только если её нет
                        val scraped = net.ripster.mobile.ui.components.rememberReleaseCover(
                            if (r.coverUrl != null) "" else r.latestUrl,
                        )
                        val cover = r.coverUrl ?: scraped
                        val cd = net.ripster.mobile.ui.components.ReleaseCardData(
                            title = r.name, artist = r.name, service = r.service.ifBlank { "radar" },
                            url = r.latestUrl, coverUrl = cover, isNew = true, dateText = null,
                        )
                        var buffering by remember { mutableStateOf(false) }
                        net.ripster.mobile.ui.components.ReleaseCard(
                            data = cd, modifier = Modifier.width(150.dp),
                            buffering = buffering,
                            onArtist = { onOpenArtist(r.name, r.service, r.artistId) },
                            onOpen = { if (r.latestUrl.isNotBlank()) onOpenAlbum(cd) else onOpen(RipsterDestination.Radar) },
                            onDownload = { onOpen(RipsterDestination.Radar) },
                            onPlay = if (r.latestUrl.isBlank()) null else {
                                {
                                    scope.launch {
                                        buffering = true
                                        val q = app.settings.state.value.qualityFor(onWifi = true)
                                        val ok = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                                            net.ripster.mobile.core.service.ReleasePlayback.play(app.player, r.latestUrl, q, fallbackArtwork = cover)
                                        } ?: false
                                        buffering = false
                                        if (ok) onOpen(RipsterDestination.Player)
                                    }
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Миксы BBC ────────────────────────────────────────────────────
        //    Автономно: RMS-API BBC по тем же брендам, что в ПК-версии. Ни
        //    аккаунта, ни сопряжения — секция появляется сама, если сеть есть.
        if (bbcMixes.isNotEmpty()) {
            item("bbc") {
                Section(tr("home.bbc", lang), c, null)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    bbcMixes.forEach { m ->
                        val cd = net.ripster.mobile.ui.components.ReleaseCardData(
                            title = m.subtitle.ifBlank { m.title },
                            artist = m.show,
                            service = "bbc",
                            url = m.url,
                            coverUrl = m.imageUrl.ifBlank { null },
                            isNew = true,
                            dateText = m.date.take(10),
                        )
                        var buffering by remember { mutableStateOf(false) }
                        net.ripster.mobile.ui.components.ReleaseCard(
                            data = cd, modifier = Modifier.width(150.dp),
                            buffering = buffering,
                            onOpen = { onOpenAlbum(cd) },
                            onDownload = { scope.launch { grabBbc(app, m.url) } },
                            onPlay = {
                                scope.launch {
                                    buffering = true
                                    val q = app.settings.state.value.qualityFor(onWifi = true)
                                    val ok = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                                        net.ripster.mobile.core.service.ReleasePlayback
                                            .play(app.player, m.url, q, fallbackArtwork = m.imageUrl)
                                    } ?: false
                                    buffering = false
                                    if (ok) onOpen(RipsterDestination.Player)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── SoundCloud: новое у подписок ─────────────────────────────────
        //    Аккаунты берутся из локального радара — на кого подписался прямо
        //    в телефоне. Пусто = ни на кого не подписан, секции просто нет.
        if (scFeed.isNotEmpty()) {
            item("sc_feed") {
                Section(tr("home.sc_feed", lang), c, null)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    scFeed.forEach { t ->
                        val permalink = t.raw["permalink"].orEmpty()
                        val cd = net.ripster.mobile.ui.components.ReleaseCardData(
                            title = t.title,
                            artist = t.artist,
                            service = "soundcloud",
                            url = permalink,
                            coverUrl = t.artworkUrl,
                            isNew = true,
                            dateText = t.year?.toString(),
                        )
                        var buffering by remember { mutableStateOf(false) }
                        net.ripster.mobile.ui.components.ReleaseCard(
                            data = cd, modifier = Modifier.width(150.dp),
                            buffering = buffering,
                            onArtist = { onOpenArtist(t.artist, "soundcloud", t.raw["artId"].orEmpty()) },
                            onOpen = { onOpenAlbum(cd) },
                            onDownload = { scope.launch { app.downloads.enqueue(t) } },
                            onPlay = {
                                scope.launch {
                                    buffering = true
                                    val q = app.settings.state.value.qualityFor(onWifi = true)
                                    val items = net.ripster.mobile.core.service.StreamResolver
                                        .toStreamItems(listOf(t), q, limit = 1, fallbackArtwork = t.artworkUrl)
                                    buffering = false
                                    if (items.isNotEmpty()) {
                                        app.player.playStream(items)
                                        onOpen(RipsterDestination.Player)
                                    }
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // «Источники»-плашка убрана: на карточках релизов сервис и так подписан,
        // а кружки были некликабельны — дублирование без действия.

        // пустая коллекция и нет пары — как в Библиотеке, зовём в поиск
        if (library.isEmpty() && recos.isEmpty()) {
            item("empty") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.accent_fill)
                            .clickable { onOpen(RipsterDestination.Search) }
                            .padding(horizontal = 22.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicText("🔍", style = TextStyle(fontSize = 15.sp))
                        BasicText(tr("lib.find_music", lang), style = TextStyle(color = c.text_on_fill, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Section(title: String, c: RipsterColors, onMore: (() -> Unit)? = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            title.uppercase(),
            Modifier.weight(1f),
            style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
        )
        if (onMore != null) {
            BasicText("→", Modifier.clickable { onMore() }, style = TextStyle(color = c.accent_text, fontSize = 12.sp))
        }
    }
}

@Composable
private fun WideCard(title: String, subtitle: String, art: String?, progress: Float, c: RipsterColors, onClick: () -> Unit) {
    Column(Modifier.width(144.dp).clickable { onClick() }) {
        Box(Modifier.size(width = 144.dp, height = 110.dp).clip(RoundedCornerShape(14.dp)).background(c.surface_active)) {
            Cover(url = art, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(14.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomStart).background(c.border_subtle)) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(c.accent_fill))
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_primary, fontSize = 13.sp))
        BasicText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
    }
}

@Composable
private fun SquareCard(title: String, subtitle: String, art: String?, downloaded: Boolean, c: RipsterColors, lang: net.ripster.mobile.ui.i18n.AppLang, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clickable { onClick() }) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(c.surface_active)) {
            Cover(url = art, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp))
            if (downloaded) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    BasicText("↓", style = TextStyle(color = c.success_text, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                    BasicText(tr("home.badge_downloaded", lang), style = TextStyle(color = c.success_text, fontSize = 9.sp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_primary, fontSize = 12.sp))
        BasicText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
    }
}

@Composable
private fun RecoCard(title: String, reason: String, c: RipsterColors, onClick: () -> Unit) {
    Row(
        Modifier.width(200.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp, c.border_subtle, RoundedCornerShape(16.dp)).background(c.surface_raised)
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(c.accent_fill))
        Column(Modifier.weight(1f)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_primary, fontSize = 12.5.sp))
            Spacer(Modifier.height(2.dp))
            BasicText(reason, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_tertiary, fontSize = 10.5.sp, lineHeight = 13.sp))
        }
    }
}

// ── Волна: станции по жанрам и настроениям ─────────────────────────────────
// Жанровое ядро = _DIGS_GENRE_PICKS с ПК (digs.js) — то, что осмысленно
// искать этой качалкой. Плюс несколько тематических станций как у Apple/Яндекса.
private data class WaveStation(
    val id: String,
    val scSlug: String,             // жанр SoundCloud charts ("" → только поиск-фолбэк)
    val query: String,              // фолбэк-запрос для сборки из всех сервисов
    val ya: String? = null,         // id станции Яндекс rotor (genre:/mood:/activity:)
    val display: String = "",       // готовое имя (жанры — как есть, латиницей)
    val nameKey: String? = null,    // либо ключ i18n (настроения)
)

private val WAVE_STATIONS = listOf(
    WaveStation("deephouse", "deephouse", "deep house", "genre:house", "Deep House"),
    WaveStation("proghouse", "house", "progressive house", "genre:house", "Progressive House"),
    WaveStation("meltech", "techno", "melodic techno", "genre:techno", "Melodic Techno"),
    WaveStation("techno", "techno", "techno", "genre:techno", "Techno"),
    WaveStation("trance", "trance", "trance", "genre:trance", "Trance"),
    WaveStation("ambient", "ambient", "ambient", "genre:ambient", "Ambient"),
    WaveStation("downtempo", "triphop", "downtempo", "genre:electronics", "Downtempo"),
    WaveStation("dnb", "drumbass", "drum and bass", "genre:dnb", "Drum & Bass"),
    WaveStation("lofi", "", "lofi hip hop", "mood:calm", "Lo-Fi"),
    WaveStation("synthwave", "", "synthwave", "genre:electronics", "Synthwave"),
    WaveStation("idm", "electronic", "idm", "genre:electronics", "IDM"),
    WaveStation("dubtechno", "techno", "dub techno", "genre:techno", "Dub Techno"),
    WaveStation("jazz", "jazzblues", "jazz", "genre:jazz", "Jazz"),
    WaveStation("classical", "classical", "classical", "genre:classical", "Classical"),
    WaveStation("focus", "", "focus concentration music", "activity:study", nameKey = "wave.focus"),
    WaveStation("workout", "", "workout energy mix", "activity:sport", nameKey = "wave.workout"),
    WaveStation("party", "danceedm", "party dance mix", "activity:party", nameKey = "wave.party"),
    WaveStation("sleep", "ambient", "sleep calm ambient", "mood:calm", nameKey = "wave.sleep"),
    WaveStation("sunset", "deephouse", "sunset chill balearic", "mood:romantic", nameKey = "wave.sunset"),
    WaveStation("rain", "", "rainy day mellow lofi", "mood:sentimental", nameKey = "wave.rain"),
)

private val WAVE_PALETTE = listOf(
    Color(0xFFFF4D8F), Color(0xFFA238FF), Color(0xFF3A5FD9),
    Color(0xFF1ECBE1), Color(0xFFFF5C3C), Color(0xFF38E0A0),
)

@Composable
private fun WaveTile(name: String, seed: String, c: RipsterColors, loading: Boolean, onClick: () -> Unit) {
    val h = kotlin.math.abs(seed.hashCode())
    val a = WAVE_PALETTE[h % WAVE_PALETTE.size]
    val b = WAVE_PALETTE[(h / 7 + 3) % WAVE_PALETTE.size]
    Box(
        Modifier.width(150.dp).height(88.dp).clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(a.copy(alpha = 0.9f), b.copy(alpha = 0.55f))))
            .clickable { onClick() }
            .padding(14.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (loading) {
            BasicText("…", Modifier.align(Alignment.TopEnd), style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        } else {
            BasicText("▶", Modifier.align(Alignment.TopEnd), style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp))
        }
        BasicText(
            name, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
    }
}


/** Поставить эпизод BBC в очередь: резолвим ссылку и кладём треки. */
private suspend fun grabBbc(app: net.ripster.mobile.RipsterApp, url: String) {
    runCatching {
        val sel = net.ripster.mobile.core.service.ServiceRegistry.all()
            .firstNotNullOfOrNull { it.resolve(url) }
        sel?.tracks?.forEach { app.downloads.enqueue(it) }
    }
}
