package net.ripster.mobile.ui

import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.produceState
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.db.LibraryEntity
import net.ripster.mobile.core.model.DownloadItem
import net.ripster.mobile.core.model.DownloadState
import net.ripster.mobile.ui.components.DownloadOrb
import net.ripster.mobile.ui.components.MiniPlayer
import net.ripster.mobile.ui.components.MiniPlayerState
import net.ripster.mobile.ui.components.QualityBadgeState
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.navigation.BottomNav
import net.ripster.mobile.ui.navigation.RipsterDestination
import net.ripster.mobile.ui.screens.DownloadTask
import net.ripster.mobile.ui.screens.DownloadTaskStatus
import net.ripster.mobile.ui.screens.DownloadsQueueScreen
import net.ripster.mobile.ui.screens.LibraryItem
import net.ripster.mobile.ui.screens.LibraryScreen
import net.ripster.mobile.ui.screens.NowPlayingScreen
import net.ripster.mobile.ui.screens.NowPlayingState
import net.ripster.mobile.ui.screens.SearchScreen
import net.ripster.mobile.ui.screens.settings.SettingsHost
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.RipsterThemeName

/**
 * Реальная оболочка приложения (не витрина): верхняя панель с поиском и
 * настройками, содержимое по вкладке BottomNav, орб загрузок над навигацией.
 * Поиск и Настройки — полноэкранные оверлеи. Плеер — заглушка, пока нет
 * аудиодвижка.
 */
@Composable
fun AppShell(startInAccountsSettings: Boolean = false) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val app = RipsterApp.from(LocalContext.current)
    val scope = rememberCoroutineScope()

    // Первый экран — Плеер, если есть что продолжить (восстановленная очередь);
    // иначе Библиотека.
    var dest by remember {
        mutableStateOf(
            if (app.player.state.value.hasItem) RipsterDestination.Player
            else RipsterDestination.Home,
        )
    }
    var userNavigated by remember { mutableStateOf(false) }
    // Куда возвращаться при сворачивании плеера (⌄ / свайп / Back).
    var lastTab by remember { mutableStateOf(RipsterDestination.Home) }
    // Восстановление очереди асинхронное — если оно доехало за время заставки и
    // человек ещё никуда не тыкал, показываем Плеер (стартует на паузе).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(16) {
            kotlinx.coroutines.delay(120)
            if (!userNavigated && app.player.state.value.hasItem) {
                dest = RipsterDestination.Player; return@LaunchedEffect
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(dest) {
        if (dest != RipsterDestination.Player) lastTab = dest
    }
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(startInAccountsSettings) }
    // одноразовый заход в раздел учёток сразу после первого запуска
    var settingsToAccounts by remember { mutableStateOf(startInAccountsSettings) }
    // открытый детальный экран альбома (модальный поверх вкладок), null = закрыт
    var albumTarget by remember { mutableStateOf<net.ripster.mobile.ui.components.ReleaseCardData?>(null) }
    // открытая страница артиста/лейбла (дискография), null = закрыта
    var artistTarget by remember { mutableStateOf<ArtistNav?>(null) }
    val openArtist: (String, String, String) -> Unit = { n, s, id -> artistTarget = ArtistNav(n, s, id, false) }
    val openLabel: (String) -> Unit = { n -> artistTarget = ArtistNav(n, "", "", true) }

    val queue by app.downloads.observeQueue().collectAsState(initial = emptyList())
    val library by app.db.library().observeAll().collectAsState(initial = emptyList())
    val playback by app.player.state.collectAsState()
    val settings by app.settings.state.collectAsState()

    // Neon-скин: near-black + радиальные подсветки. Плюс AMBILIGHT: НЕ картинка
    // обложки, а мягкая меш-заливка из её средней палитры — несколько
    // приглушённых пятен, сбалансированно разложенных по всему экрану. При
    // смене АЛЬБОМА палитра плавно перетекает; при смене трека внутри альбома
    // палитра та же, но пятна хаотично перекладываются и чуть светлее/темнее.
    val neon = RipsterTheme.name == RipsterThemeName.Neon
    val ambiOn = settings.adaptiveColors && playback.hasItem
    val paletteNew = rememberPalette(
        if (ambiOn) playback.artworkUrl else null,
        if (ambiOn) playback.currentPath else null,
    )
    var meshCur by remember { mutableStateOf<List<AmbiBlob>>(emptyList()) }
    var meshPrev by remember { mutableStateOf<List<AmbiBlob>>(emptyList()) }
    val xfade = remember { androidx.compose.animation.core.Animatable(1f) }
    // seed раскладки пятен: от альбома+трека. НЕ анимируем (раньше был
    // animateFloatAsState tween(1400) между двумя несвязанными хэшами — пятна
    // «ползли» по экрану 1.4с после старта трека, это и был баг). Пятна теперь
    // встают на новые места мгновенно, под прикрытием кроссфейда палитры.
    val layoutSeed = (playback.album + "|" + playback.title).hashCode()
    androidx.compose.runtime.LaunchedEffect(paletteNew, layoutSeed) {
        val paletteChanged = paletteNew != meshCur.map { it.base }
        if (paletteNew.isNotEmpty()) {
            // геометрию считаем ОДИН раз здесь, не в каждом кадре drawBehind
            meshPrev = meshCur
            meshCur = buildAmbiMesh(paletteNew, layoutSeed)
            xfade.snapTo(0f)
            // смена палитры (новый альбом) — помягче; тот же альбом, другой трек —
            // быстрый «пересбор» пятен
            xfade.animateTo(1f, androidx.compose.animation.core.tween(if (paletteChanged) 620 else 340))
        } else if (meshCur.isNotEmpty()) {
            meshPrev = meshCur; meshCur = emptyList()
            xfade.snapTo(0f); xfade.animateTo(1f, androidx.compose.animation.core.tween(500))
        }
    }
    val ditherBrush = rememberDitherBrush()
    Box(
        Modifier.fillMaxSize().background(c.surface_canvas).drawBehind {
            fun mesh(blobs: List<AmbiBlob>, a: Float) {
                if (blobs.isEmpty() || a <= 0.01f) return
                for (b in blobs) {
                    val t = b.tint.copy(alpha = b.tint.alpha * a)
                    drawRect(
                        // 3 стопа вместо 2: пологое плечо в середине даёт мягче
                        // спад, чем линейный [цвет → прозрачность], и колец меньше
                        Brush.radialGradient(
                            0f to t,
                            0.55f to t.copy(alpha = t.alpha * 0.32f),
                            1f to Color.Transparent,
                            center = Offset(size.width * b.cx, size.height * b.cy),
                            radius = size.width * 0.98f,
                        ),
                    )
                }
            }
            mesh(meshPrev, 1f - xfade.value)
            mesh(meshCur, xfade.value)
            if (neon) {
                drawRect(
                    Brush.radialGradient(
                        0f to Color(0x24FF4D8F), 0.6f to Color(0x0BFF4D8F), 1f to Color(0x00FF4D8F),
                        center = Offset(size.width * 0.84f, -size.height * 0.04f),
                        radius = size.width * 1.10f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        0f to Color(0x1CA238FF), 0.6f to Color(0x08A238FF), 1f to Color(0x00A238FF),
                        center = Offset(-size.width * 0.06f, size.height * 1.06f),
                        radius = size.width * 1.05f,
                    ),
                )
            }
            // дизер: тонкая шумовая плёнка поверх всех градиентов — ломает
            // 8-битные кольца в зерно. Нужна только когда фон вообще есть.
            if (neon || meshCur.isNotEmpty() || meshPrev.isNotEmpty()) {
                drawRect(ditherBrush, alpha = 0.028f)
            }
        },
    ) {
        // Фон (ambilight) рисуется от края до края под барами; контент —
        // внутри системных инсетов, чтобы шапка и нижняя навигация не залезали
        // под статус-бар / вырез / жестовую полосу на любом телефоне.
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        net.ripster.mobile.R.drawable.ic_ripster,
                    ),
                    contentDescription = "Ripster",
                    modifier = Modifier.size(30.dp),
                )
                Box(Modifier.weight(1f))
                // Явный вход в Плеер — Player убран из нижней навигации по
                // дизайну v2, но открыть последний трек надо уметь всегда.
                if (playback.hasItem && dest != RipsterDestination.Player) {
                    GlyphButton(onClick = { userNavigated = true; dest = RipsterDestination.Player }) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.30f, size.height * 0.20f)
                            lineTo(size.width * 0.30f, size.height * 0.80f)
                            lineTo(size.width * 0.82f, size.height * 0.50f)
                            close()
                        }
                        drawPath(p, c.accent_text)
                    }
                    Box(Modifier.size(10.dp))
                }
                GlyphButton(onClick = { userNavigated = true; dest = RipsterDestination.Search }) { drawSearchGlyph(c.text_secondary) }
                Box(Modifier.size(10.dp))
                // Прямоугольная кнопка настроек (не шестерня) — единственная
                // точка входа в настройки; дубль-шестерёнку из Библиотеки убрали.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, c.border_subtle, RoundedCornerShape(9.dp))
                        .clickable { showSettings = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    BasicText(
                        tr("nav.settings", lang),
                        style = androidx.compose.ui.text.TextStyle(
                            color = c.text_secondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }

            Box(Modifier.weight(1f)) {
              // Переход между вкладками — со скольжением+растворением. Плеер
              // «разворачивается» снизу, назад к списку — «сворачивается» вниз.
              androidx.compose.animation.AnimatedContent(
                targetState = dest,
                transitionSpec = {
                    val toPlayer = targetState == RipsterDestination.Player
                    val fromPlayer = initialState == RipsterDestination.Player
                    // Слайды — по пружине (Motion): плеер большой → gentle, без
                    // отскока; смена вкладок мельче → standard, с едва заметным
                    // доводчиком. Растворение остаётся коротким линейным.
                    when {
                        toPlayer -> (androidx.compose.animation.slideInVertically(
                            animationSpec = net.ripster.mobile.ui.theme.Motion.gentleOffset,
                        ) { it / 2 } + androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeMed))) togetherWith
                            androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeFast))
                        fromPlayer -> androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeMed)) togetherWith
                            (androidx.compose.animation.slideOutVertically(
                                animationSpec = net.ripster.mobile.ui.theme.Motion.gentleOffset,
                            ) { it / 2 } + androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeMed)))
                        else -> (androidx.compose.animation.slideInHorizontally(
                            animationSpec = net.ripster.mobile.ui.theme.Motion.standardOffset,
                        ) { it / 6 } + androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeFast))) togetherWith
                            (androidx.compose.animation.slideOutHorizontally(
                                animationSpec = net.ripster.mobile.ui.theme.Motion.standardOffset,
                            ) { -it / 6 } + androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeFast)))
                    }
                },
                label = "tab-switch",
              ) { d ->
                when (d) {
                    RipsterDestination.Player -> if (playback.hasItem) {
                        val npState = NowPlayingState(
                            title = playback.title,
                            artist = playback.artist,
                            album = playback.album,
                            positionMs = playback.positionMs,
                            bufferedMs = playback.bufferedMs,
                            durationMs = playback.durationMs,
                            isPlaying = playback.isPlaying,
                            shuffle = playback.shuffle,
                            repeat = playback.repeat,
                            quality = when {
                                playback.fakeLossless -> QualityBadgeState.Fake("lossless-контейнер, lossy-поток")
                                playback.qualityMismatch -> QualityBadgeState.Mismatch(
                                    promised = "FLAC / Hi-Res",
                                    actual = playback.format,
                                )
                                playback.format.isBlank() -> QualityBadgeState.NotMeasured
                                else -> QualityBadgeState.Match
                            },
                            format = playback.format,
                            artworkUrl = playback.artworkUrl,
                        )
                        val minimize = { userNavigated = true; dest = lastTab }
                        val close = { app.player.stop(); userNavigated = true; dest = lastTab }
                        androidx.activity.compose.BackHandler(enabled = true) { minimize() }
                        Box(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                var accX = 0f
                                var accY = 0f
                                detectDragGestures(
                                    onDragEnd = {
                                        // свайп вбок (любой) — закрыть плеер и остановить;
                                        // свайп вниз — просто свернуть (плеер играет дальше)
                                        if (kotlin.math.abs(accX) > 140f && kotlin.math.abs(accX) > kotlin.math.abs(accY)) close()
                                        else if (accY > 160f) minimize()
                                        accX = 0f; accY = 0f
                                    },
                                    onDragCancel = { accX = 0f; accY = 0f },
                                ) { ch, dr ->
                                    accX += dr.x; accY += dr.y
                                    if (kotlin.math.abs(dr.x) > kotlin.math.abs(dr.y)) ch.consume()
                                }
                            },
                        ) {
                            if (settings.playerStyle == "immersive") {
                                net.ripster.mobile.ui.screens.ImmersivePlayerScreen(
                                    state = npState,
                                    onSeek = { app.player.seekTo(it) },
                                    onPlayPause = { app.player.togglePlay() },
                                    onNext = { app.player.next() },
                                    onPrevious = { app.player.previous() },
                                )
                            } else if (settings.playerStyle == "studio") {
                                NowPlayingScreen(
                                    state = npState,
                                    onSeek = { app.player.seekTo(it) },
                                    onScrubPreview = {},
                                    onPlayPause = { app.player.togglePlay() },
                                    onNext = { app.player.next() },
                                    onPrevious = { app.player.previous() },
                                    onToggleShuffle = { app.player.toggleShuffle() },
                                    onToggleRepeat = { app.player.cycleRepeat() },
                                    onDownloadAlbum = {},
                                )
                            } else {
                                net.ripster.mobile.ui.screens.ReferencePlayerScreen(
                                    state = npState,
                                    onSeek = { app.player.seekTo(it) },
                                    onPlayPause = { app.player.togglePlay() },
                                    onNext = { app.player.next() },
                                    onPrevious = { app.player.previous() },
                                    onToggleShuffle = { app.player.toggleShuffle() },
                                    onToggleRepeat = { app.player.cycleRepeat() },
                                    onDownloadAlbum = {},
                                )
                            }
                            // свернуть (⌄) + закрыть (×) — одна аккуратная капсула
                            // в правом верхнем углу поверх плеера, приглушённо
                            Row(
                                Modifier.align(Alignment.TopEnd)
                                    .windowInsetsPadding(WindowInsets.systemBars)
                                    .padding(top = 8.dp, end = 8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.26f))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val glyph = Color.White.copy(alpha = 0.60f)
                                Box(
                                    Modifier.size(width = 40.dp, height = 34.dp).clickable { minimize() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Canvas(Modifier.size(16.dp)) {
                                        drawLine(glyph, Offset(size.width * 0.22f, size.height * 0.40f),
                                            Offset(size.width * 0.5f, size.height * 0.64f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                                        drawLine(glyph, Offset(size.width * 0.5f, size.height * 0.64f),
                                            Offset(size.width * 0.78f, size.height * 0.40f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                                    }
                                }
                                Box(Modifier.size(width = 1.dp, height = 16.dp).background(Color.White.copy(alpha = 0.14f)))
                                Box(
                                    Modifier.size(width = 40.dp, height = 34.dp).clickable { close() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Canvas(Modifier.size(14.dp)) {
                                        drawLine(glyph, Offset(size.width * 0.2f, size.height * 0.2f),
                                            Offset(size.width * 0.8f, size.height * 0.8f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                                        drawLine(glyph, Offset(size.width * 0.8f, size.height * 0.2f),
                                            Offset(size.width * 0.2f, size.height * 0.8f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                                    }
                                }
                            }
                        }
                    } else {
                        Placeholder(tr("player.pick_track", lang))
                    }
                    RipsterDestination.Home -> net.ripster.mobile.ui.screens.HomeScreen(
                        onOpen = { userNavigated = true; dest = it },
                        onOpenSearchQuery = { userNavigated = true; dest = RipsterDestination.Search },
                        onOpenAlbum = { albumTarget = it },
                        onOpenArtist = openArtist,
                    )
                    RipsterDestination.Search -> Box(Modifier.fillMaxSize()) { SearchScreen(onOpenArtist = openArtist, onOpenPlayer = { userNavigated = true; dest = RipsterDestination.Player }) }
                    RipsterDestination.Radar -> net.ripster.mobile.ui.screens.RadarScreen(
                        onOpenAlbum = { albumTarget = it },
                        onOpenArtist = openArtist,
                        onOpenLabel = openLabel,
                        onOpenPlayer = { userNavigated = true; dest = RipsterDestination.Player },
                    )
                    RipsterDestination.Library -> LibraryScreen(
                        items = library.map { it.toItem() },
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onItemClick = { picked ->
                            val idx = library.indexOfFirst { it.id == picked.id }.coerceAtLeast(0)
                            app.player.playQueue(library, idx)
                            dest = RipsterDestination.Player
                        },
                        onOpenSettings = { showSettings = true },
                        onOpenSearch = { userNavigated = true; dest = RipsterDestination.Search },
                    )
                    RipsterDestination.Downloads -> DownloadsQueueScreen(
                        tasks = queue.map { it.toTask() },
                        onRetry = { t -> scope.launch { app.downloads.retry(t.id) } },
                        onCancel = { t -> app.downloads.cancel(t.id) },
                        onClearFinished = { scope.launch { app.downloads.clearFinished() } },
                        onClearAll = {
                            queue.forEach { di ->
                                if (di.state == DownloadState.QUEUED || di.state == DownloadState.RUNNING) {
                                    app.downloads.cancel(di.id)
                                }
                            }
                            scope.launch { app.downloads.clearFinished() }
                        },
                    )
                    RipsterDestination.Tools -> net.ripster.mobile.ui.screens.ToolsScreen()
                }
              }
            }

            // Полоса рядом с кружком загрузки не пропадает зря: краткий статус
            // очереди (качается / в очереди / готово / ошибки) → тап открывает
            // вкладку «Загрузки».
            Box(Modifier.fillMaxWidth()) {
                DownloadStrip(
                    items = queue,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 76.dp),
                ) { userNavigated = true; dest = RipsterDestination.Downloads }
                DownloadOrb(items = queue, modifier = Modifier.padding(bottom = 6.dp))
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = playback.hasItem && dest != RipsterDestination.Player,
                enter = androidx.compose.animation.slideInVertically(
                    net.ripster.mobile.ui.theme.Motion.standardOffset,
                ) { it } + androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeMed)),
                exit = androidx.compose.animation.slideOutVertically(
                    net.ripster.mobile.ui.theme.Motion.standardOffset,
                ) { it } + androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(net.ripster.mobile.ui.theme.Motion.fadeFast)),
            ) {
                MiniPlayer(
                    state = MiniPlayerState(
                        title = playback.title,
                        artist = playback.artist,
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                        isPlaying = playback.isPlaying,
                        artworkUrl = playback.artworkUrl,
                    ),
                    onPlayPause = { app.player.togglePlay() },
                    onPrev = { app.player.previous() },
                    onNext = { app.player.next() },
                    onClose = { app.player.stop() },
                    onExpand = { dest = RipsterDestination.Player },
                )
            }
            BottomNav(current = dest, onSelect = { userNavigated = true; dest = it })
        }

        if (showSearch) {
            androidx.activity.compose.BackHandler(true) { showSearch = false }
            Column(Modifier.fillMaxSize().background(c.surface_canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
                OverlayBar(tr("nav.search", lang)) { showSearch = false }
                Box(Modifier.weight(1f)) { SearchScreen(onOpenPlayer = { showSearch = false; userNavigated = true; dest = RipsterDestination.Player }) }
                // Только текстовый статус очереди — без анимированного кружка:
                // на тяжёлых перекомпоновках экрана поиска второй экземпляр орба
                // ремонтировался и мерцал. Орб живёт единственным — в оболочке.
                DownloadStrip(
                    items = queue,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
                ) { showSearch = false; userNavigated = true; dest = RipsterDestination.Downloads }
            }
        }
        if (showSettings) {
            Box(Modifier.fillMaxSize().background(c.surface_canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
                SettingsHost(
                    onExit = { showSettings = false; settingsToAccounts = false },
                    openAccounts = settingsToAccounts,
                )
            }
        }
        albumTarget?.let { at ->
            Box(Modifier.fillMaxSize().background(c.surface_canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
                net.ripster.mobile.ui.screens.AlbumScreen(
                    url = at.url, service = at.service,
                    fallbackTitle = at.title, fallbackArtist = at.artist, fallbackCover = at.coverUrl,
                    onBack = { albumTarget = null },
                    onOpenPlayer = { albumTarget = null; dest = RipsterDestination.Player },
                    onOpenArtist = { n, s, id -> albumTarget = null; openArtist(n, s, id) },
                )
            }
        }
        artistTarget?.let { at ->
            Box(Modifier.fillMaxSize().background(c.surface_canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
                net.ripster.mobile.ui.screens.ArtistScreen(
                    name = at.name, service = at.service, artistId = at.id, isLabel = at.isLabel,
                    onBack = { artistTarget = null },
                    onOpenAlbum = { albumTarget = it },
                )
            }
        }
    }
}

private data class ArtistNav(val name: String, val service: String, val id: String, val isLabel: Boolean)

/** Одно пятно ambilight: позиция (доли экрана) + цвет с уже вложенной альфой.
 *  Геометрия считается ОДИН раз на смену трека, не в каждом кадре. */
private data class AmbiBlob(val base: Color, val cx: Float, val cy: Float, val tint: Color)

private val AMBI_BASE = listOf(
    0.16f to 0.12f, 0.86f to 0.16f, 0.20f to 0.62f, 0.82f to 0.84f, 0.50f to 0.42f,
)

/** Разложить палитру по экрану: базовые зоны + детерминированный джиттер от
 *  seed (альбом+трек) + лёгкий сдвиг светлоты. Чистая функция — без Compose. */
private fun buildAmbiMesh(pal: List<Color>, seed: Int): List<AmbiBlob> {
    if (pal.isEmpty()) return emptyList()
    fun rnd(k: Int): Float {
        val x = kotlin.math.sin((seed.toFloat() + k * 37.13f) * 12.9898f) * 43758.5453f
        return (x - kotlin.math.floor(x)) - 0.5f          // -0.5..0.5
    }
    return pal.mapIndexed { i, col ->
        val (bx, by) = AMBI_BASE[i % AMBI_BASE.size]
        val cx = (bx + rnd(i) * 0.16f).coerceIn(0f, 1f)
        val cy = (by + rnd(i + 100) * 0.16f).coerceIn(0f, 1f)
        val lift = rnd(i + 200) * 0.12f
        val cc = androidx.compose.ui.graphics.lerp(
            col, if (lift >= 0f) Color.White else Color.Black, kotlin.math.abs(lift),
        )
        AmbiBlob(base = col, cx = cx, cy = cy, tint = cc.copy(alpha = 0.13f))
    }
}

/**
 * Тайловый шум для дизеринга. Радиальные градиенты ambilight — это очень
 * пологие переходы; в 8 бит на канал и без дизера Skia рисует их видимыми
 * кольцами («лесенка», «полигоны»). Тонкая шумовая плёнка поверх (SrcOver,
 * ~3%) размывает 8-битные ступени в зерно — бандинг пропадает. Битмап
 * строится ОДИН раз и тайлится шейдером; на кадр — одна заливка прямоугольника.
 */
@Composable
private fun rememberDitherBrush(): ShaderBrush {
    val bmp = remember {
        val n = 64
        val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(0x5EED)
        val px = IntArray(n * n) {
            val v = rnd.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        b.setPixels(px, 0, n, 0, 0, n, n)
        b.asImageBitmap()
    }
    return remember(bmp) { ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated)) }
}

/** Средняя палитра обложки — до 5 приглушённых оттенков по зонам (TL/TR/BL/BR/
 *  центр). НЕ картинка: используется для мягкой меш-заливки, распределённой по
 *  всему экрану. */
@Composable
private fun rememberPalette(url: String?, path: String?): List<Color> {
    val ctx = LocalContext.current
    return produceState<List<Color>>(initialValue = emptyList(), url, path) {
        value = emptyList()
        val data: Any? = url?.takeIf { it.startsWith("http") } ?: run {
            val p = path ?: return@run null
            runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    if (p.startsWith("content://") || p.startsWith("file://")) mmr.setDataSource(ctx, android.net.Uri.parse(p))
                    else mmr.setDataSource(p)
                    mmr.embeddedPicture
                } finally { runCatching { mmr.release() } }
            }.getOrNull()
        } ?: return@produceState
        value = runCatching {
            val req = ImageRequest.Builder(ctx).data(data).size(36).allowHardware(false).build()
            val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap(36, 36)
                ?: return@runCatching emptyList<Color>()
            val w = bmp.width; val h = bmp.height
            fun avg(x0: Int, y0: Int, x1: Int, y1: Int): Color {
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                for (y in y0 until y1) for (x in x0 until x1) {
                    val p = bmp.getPixel(x, y)
                    if ((p ushr 24 and 0xFF) < 128) continue
                    r += (p ushr 16 and 0xFF); g += (p ushr 8 and 0xFF); b += (p and 0xFF); n++
                }
                if (n == 0L) return Color(0x22, 0x22, 0x2A)
                // приглушаем: тянем к среднему серому (мягкий оттенок, не кричит)
                val rr = (r / n).toInt(); val gg = (g / n).toInt(); val bb = (b / n).toInt()
                val mid = 128
                fun soft(v: Int) = (v + (mid - v) * 0.42f).toInt().coerceIn(0, 255)
                return Color(soft(rr), soft(gg), soft(bb))
            }
            listOf(
                avg(0, 0, w / 2, h / 2),
                avg(w / 2, 0, w, h / 2),
                avg(0, h / 2, w / 2, h),
                avg(w / 2, h / 2, w, h),
                avg(w / 3, h / 3, w * 2 / 3, h * 2 / 3),
            )
        }.getOrDefault(emptyList())
    }.value
}

@Composable
private fun DownloadStrip(items: List<DownloadItem>, modifier: Modifier, onOpen: () -> Unit) {
    if (items.isEmpty()) return
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val running = items.firstOrNull { it.state == DownloadState.RUNNING }
    val queued = items.count { it.state == DownloadState.QUEUED }
    val done = items.count { it.state == DownloadState.DONE }
    val failed = items.count { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED }
    val text = when {
        running != null -> {
            val pct = ((running.fraction ?: 0f) * 100).toInt()
            "↓ " + running.track.title + (if (pct > 0) "  ·  $pct%" else "")
        }
        queued > 0 -> "$queued ${tr("dl.in_queue", lang)}" +
            (if (failed > 0) "  ·  $failed ${tr("dl.with_error", lang)}" else "")
        failed > 0 -> "$failed ${tr("dl.with_error", lang)}"
        done > 0 -> "$done ${tr("dl.done_n", lang)}"
        else -> return
    }
    val warn = running == null && failed > 0 && queued == 0
    androidx.compose.foundation.layout.Row(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (warn) c.warning_text else c.text_tertiary, fontSize = 11.sp),
        )
    }
}

@Composable
private fun GlyphButton(onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    Box(
        Modifier.size(30.dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) { draw() }
    }
}

@Composable
private fun OverlayBar(title: String, onBack: () -> Unit) {
    val c = RipsterTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(16.dp)) { drawBackGlyph(c.text_secondary) }
        }
        Box(Modifier.size(8.dp))
        BasicText(title, style = TextStyle(color = c.text_primary, fontSize = 17.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Placeholder(text: String) {
    val c = RipsterTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
    }
}

private fun DownloadItem.toTask() = DownloadTask(
    id = id,
    title = track.title,
    artist = track.artist,
    status = when (state) {
        DownloadState.QUEUED -> DownloadTaskStatus.Queued
        DownloadState.RUNNING -> DownloadTaskStatus.Downloading
        DownloadState.DONE -> DownloadTaskStatus.Done
        DownloadState.FAILED, DownloadState.CANCELLED -> DownloadTaskStatus.Failed
    },
    progress = fraction,
    errorReason = errorReason,
)

private fun LibraryEntity.toItem() = LibraryItem(
    id = id,
    title = title,
    artist = artist,
    format = net.ripster.mobile.player.PlayerController.formatLine(this) +
        if (fakeLossless) "  ⚠" else "",
    trackCount = 1,
    artworkUrl = artworkUrl,
)

// ── глифы верхней панели (Canvas-примитивы, как в BottomNav) ──

private fun DrawScope.drawSearchGlyph(color: Color) {
    drawCircle(
        color, size.minDimension * 0.34f,
        center = Offset(size.width * 0.42f, size.height * 0.42f),
        style = Stroke(size.minDimension * 0.11f),
    )
    drawLine(
        color, Offset(size.width * 0.64f, size.height * 0.64f),
        Offset(size.width * 0.92f, size.height * 0.92f), strokeWidth = size.minDimension * 0.12f,
    )
}

private fun DrawScope.drawGearGlyph(color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawCircle(color, size.minDimension * 0.30f, center = Offset(cx, cy), style = Stroke(size.minDimension * 0.12f))
    for (i in 0 until 8) {
        val a = Math.toRadians((i * 45).toDouble())
        val cos = Math.cos(a).toFloat()
        val sin = Math.sin(a).toFloat()
        drawLine(
            color,
            Offset(cx + size.minDimension * 0.34f * cos, cy + size.minDimension * 0.34f * sin),
            Offset(cx + size.minDimension * 0.5f * cos, cy + size.minDimension * 0.5f * sin),
            strokeWidth = size.minDimension * 0.11f,
        )
    }
}

private fun DrawScope.drawBackGlyph(color: Color) {
    val w = size.width
    val h = size.height
    drawLine(color, Offset(w * 0.6f, h * 0.15f), Offset(w * 0.2f, h * 0.5f), strokeWidth = w * 0.13f)
    drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.6f, h * 0.85f), strokeWidth = w * 0.13f)
}
