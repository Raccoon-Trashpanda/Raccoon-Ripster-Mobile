package net.ripster.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.lyrics.LyricsClient
import net.ripster.mobile.player.AudioEffects
import net.ripster.mobile.ui.components.QualityBadge
import net.ripster.mobile.ui.components.QualityBadgeState
import net.ripster.mobile.ui.components.SeekPlaybackState
import net.ripster.mobile.ui.components.SeekStrip
import net.ripster.mobile.ui.components.drawNextGlyph
import net.ripster.mobile.ui.components.drawPrevGlyph
import net.ripster.mobile.ui.components.drawRepeatGlyph
import net.ripster.mobile.ui.components.drawShuffleGlyph
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Второй вид экрана «Сейчас играет» — по макету владельца
 * (`design/screens/player_full.png` + `design/v003/player_demo.html`), а не по
 * дизайн-системе `NowPlayingScreen`. Отличия макета: круглая кнопка Play с
 * розовым градиентом (подпись Грока), тонкая линия перемотки, ряд действий
 * чипами, шапка «СЕЙЧАС ИГРАЕТ» со сворачиванием и меню.
 *
 * Тот же [NowPlayingState] на входе — подставляется без переписывания
 * вызывающего кода. Контракт честности качества сохранён: [QualityBadge]
 * остаётся (не измерено / совпало / расхождение / подделка).
 */
@Composable
fun ReferencePlayerScreen(
    state: NowPlayingState,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDownloadAlbum: () -> Unit,
    onCollapse: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val app = RipsterApp.from(LocalContext.current)
    val settings by app.settings.state.collectAsState()
    val pbState by app.player.state.collectAsState()

    // 0 — нет, 1 — трек-лист, 2 — текст, 3 — спектр, 4 — эквалайзер, 5 — инфо
    var sheet by remember { mutableStateOf(0) }
    var liked by remember { mutableStateOf(false) }

    // Обложки нет по URL → достаём встроенную в файл картинку.
    val embeddedArt = rememberEmbeddedArt(
        if (state.artworkUrl.isNullOrBlank()) pbState.currentPath else null,
    )
    // Средний цвет обложки → амбиентная заливка фона (если включены адаптивные
    // цвета). Как в канвасе скина Neon.
    val coverAvg = rememberCoverAvg(state.artworkUrl, embeddedArt)
        .takeIf { settings.adaptiveColors }

    // Фон НЕ красим — под плеером просвечивает глобальный ambilight из AppShell
    // (раньше непрозрачный surface_canvas закрывал его на всю область плеера).
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Квадрат фиксированного размера от доступной высоты — не fillMaxWidth
        // + aspectRatio (тот перебивал heightIn и налезал на подписи).
        // Обложка — максимально крупная, но чтобы подписи оставались в зоне
        // прокрутки (вся нижняя часть — seek/транспорт/действия — закреплена).
        val coverSide = (maxHeight * 0.46f)
            .coerceAtMost(maxWidth - 24.dp)
            .coerceIn(240.dp, 400.dp)

        // Адаптивная заливка по тону обложки теперь глобальная — на весь экран
        // Ripster из AppShell (края в края), здесь её больше нет.

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
        ) {
          // Прокручивается только верх (обложка/подписи). Полоса перемотки,
          // транспорт и ряд действий ЗАКРЕПЛЕНЫ снизу — всегда на экране, и
          // SeekStrip получает жесты без конкуренции с вертикальным скроллом
          // (из-за неё «скрол по треку» и не работал).
          Column(
              Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
          ) {
            Spacer(Modifier.height(10.dp))

            // ── обложка — герой (без наложений; вердикт качества — на строке
            //    формата под артистом, тап по ней → «Всё о потоке») ──
            net.ripster.mobile.ui.components.Cover(
                url = state.artworkUrl,
                modifier = Modifier.size(coverSide).align(Alignment.CenterHorizontally)
                    .border(1.dp, c.border_subtle, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                fallbackModel = embeddedArt,
            )

            Spacer(Modifier.height(12.dp))

            // ── название / артист / альбом + ♥ ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        state.title,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = c.text_primary, fontSize = 22.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.3).sp),
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        if (state.album.isNotBlank() && !state.album.equals(state.title, ignoreCase = true))
                            "${state.artist}  ·  ${state.album}" else state.artist,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = c.text_secondary, fontSize = 14.sp),
                    )
                    if (state.format.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        // тап по строке качества → «Всё о потоке»
                        val warnDot = state.quality is QualityBadgeState.Mismatch ||
                            state.quality is QualityBadgeState.Fake
                        Row(
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) { sheet = 5 },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (warnDot) {
                                Canvas(Modifier.size(6.dp)) {
                                    drawCircle(if (state.quality is QualityBadgeState.Fake) c.danger_text else c.warning_text)
                                }
                            }
                            BasicText(
                                state.format,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    color = if (warnDot) c.warning_text else c.text_secondary,
                                    fontSize = 11.5.sp,
                                ),
                            )
                            Canvas(Modifier.size(11.dp)) {
                                val w = size.width
                                drawCircle(c.text_tertiary, w * 0.46f, style = Stroke(w * 0.1f))
                                drawLine(c.text_tertiary, Offset(w * 0.5f, w * 0.42f), Offset(w * 0.5f, w * 0.74f), w * 0.13f, StrokeCap.Round)
                                drawCircle(c.text_tertiary, w * 0.07f, Offset(w * 0.5f, w * 0.28f))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                LikeButton(liked = liked) { liked = !liked }
            }

          } // конец прокручиваемого верха (обложка + подписи)

            Spacer(Modifier.height(10.dp))

            // ── полоса перемотки (общий SeekStrip: толщина 6/14dp, ручка,
            //     реальное ведение пальцем, строка времени с целью и дельтой) ──
            SeekStrip(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
                bufferedMs = state.bufferedMs.coerceAtLeast(state.positionMs),
                state = if (state.isPlaying) SeekPlaybackState.Playing else SeekPlaybackState.Paused,
                tint = coverAvg?.let {
                    net.ripster.mobile.ui.theme.clampCoverTint(it, c.surface_canvas)
                } ?: c.accent_text,
                surfaceBehind = c.surface_canvas,
            )

            Spacer(Modifier.height(10.dp))

            // ── транспорт — 🔀 ⏮ [play 64] ⏭ 🔁. Правила адаптивности
            //    (UI_RESPONSIVE_2026-09-01.md): без фиксированных spacedBy —
            //    свободное место раскладывает ряд (SpaceBetween) внутри padding
            //    20dp, каждый контрол ≥48dp. На узком 360dp пятая иконка больше
            //    не срезается; на планшете ряд не расползается за счёт widthIn.
            Row(
                Modifier.fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SideGlyph(onClick = onToggleShuffle, cd = "Перемешать", box = 48.dp, glyph = 22.dp,
                    tint = if (state.shuffle) c.accent_text else c.text_tertiary) { drawShuffleGlyph(it) }
                SideGlyph(onClick = onPrevious, cd = "Предыдущий", box = 48.dp, glyph = 22.dp) { drawPrevGlyph(it) }
                RefPlayButton(isPlaying = state.isPlaying, onClick = onPlayPause)
                SideGlyph(onClick = onNext, cd = "Следующий", box = 48.dp, glyph = 22.dp) { drawNextGlyph(it) }
                SideGlyph(onClick = onToggleRepeat, cd = "Повтор", box = 48.dp, glyph = 22.dp,
                    tint = if (state.repeat) c.accent_text else c.text_tertiary) { drawRepeatGlyph(it) }
            }

            Spacer(Modifier.height(6.dp))

            // ── действия: 5 квадратных кнопок. При 5+ пунктах — прокручиваемый
            //    ряд с contentPadding 20dp (UI_RESPONSIVE_2026-09-01.md): пятый
            //    чип честно доезжает свайпом, а не срезается, и двухстрочная
            //    подпись «На колонку» не ломает высоту соседей.
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SquareAction(tr("ref.tracklist", lang), onClick = { sheet = 1 }) { listGlyph(it) }
                SquareAction(tr("ref.lyrics", lang), onClick = { sheet = 2 }) { lyricsGlyph(it) }
                SquareAction(tr("ref.spectrum", lang), onClick = { sheet = 3 }) { barsGlyph(it) }
                SquareAction(tr("ref.equalizer", lang), onClick = { sheet = 4 }) { eqGlyph(it) }
                SquareAction(tr("ref.cast", lang), onClick = { sheet = 6 }) { castGlyph(it) }
            }

            Spacer(Modifier.height(6.dp))
        }

        // ── панели поверх плеера ─────────────────────────────────────
        if (sheet != 0) {
            BackHandler(enabled = true) { sheet = 0 }
            Column(
                Modifier.fillMaxSize().background(c.surface_canvas),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { sheet = 0 }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("‹", style = TextStyle(color = c.text_secondary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        tr(when (sheet) {
                            1 -> "ref.tracklist"; 2 -> "ref.lyrics"; 3 -> "ref.spectrum"
                            5 -> "ref.stream_info"; 6 -> "ref.cast"; else -> "ref.equalizer"
                        }, lang),
                        style = TextStyle(color = c.text_primary, fontSize = 17.sp, fontWeight = FontWeight.W700),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (sheet) {
                        1 -> TracklistPanel(app, c) { sheet = 0 }
                        2 -> LyricsPanel(state, c, lang)
                        3 -> SpectrumPanel(app, c, lang)
                        5 -> StreamInfoPanel(app, c, lang)
                        6 -> androidx.compose.foundation.layout.Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        ) { net.ripster.mobile.ui.screens.cast.YandexStationBlock() }
                        else -> EqPanel(c, lang)
                    }
                }
            }
        }
    }
}

@Composable
private fun TracklistPanel(
    app: RipsterApp,
    c: net.ripster.mobile.ui.theme.RipsterColors,
    onPicked: () -> Unit,
) {
    val pb by app.player.state.collectAsState()
    if (pb.queue.isEmpty()) {
        Centered("—", c)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(pb.queue, key = { i, e -> "$i:${e.id}" }) { i, entry ->
            val current = i == pb.queueIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (current) Modifier.background(c.surface_raised) else Modifier)
                    .clickable { app.player.playIndex(i); onPicked() }
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                    if (current) {
                        Canvas(Modifier.size(9.dp)) {
                            drawCircle(c.accent_text, size.minDimension / 2f)
                        }
                    } else {
                        BasicText("${i + 1}", style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                    }
                }
                net.ripster.mobile.ui.components.Cover(
                    url = entry.artworkUrl,
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                Column(Modifier.weight(1f)) {
                    BasicText(
                        entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (current) c.accent_text else c.text_primary,
                            fontSize = 13.5.sp,
                            fontWeight = if (current) FontWeight.W700 else FontWeight.W500,
                        ),
                    )
                    Spacer(Modifier.height(1.dp))
                    BasicText(
                        buildString {
                            append(entry.artist)
                            entry.label?.let { append("  ·  "); append(it) }
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                    )
                    if (entry.spec.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            entry.spec,
                            style = TextStyle(
                                color = if (entry.lossless) c.accent_text.copy(alpha = 0.85f) else c.text_tertiary,
                                fontSize = 10.sp,
                            ),
                        )
                    }
                }
                if (entry.durationSec > 0) {
                    BasicText(
                        "%d:%02d".format(entry.durationSec / 60, entry.durationSec % 60),
                        style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                    )
                }
                // «убрать из очереди» — недоступно для текущего трека
                Box(
                    Modifier
                        .size(34.dp)
                        .clickable(enabled = !current) { app.player.removeFromQueue(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(12.dp)) {
                        val col = if (current) c.text_disabled else c.text_tertiary
                        drawLine(col, Offset(0f, 0f), Offset(size.width, size.height), size.minDimension * 0.14f, StrokeCap.Round)
                        drawLine(col, Offset(size.width, 0f), Offset(0f, size.height), size.minDimension * 0.14f, StrokeCap.Round)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))
        }
    }
}

@Composable
private fun LyricsPanel(
    state: NowPlayingState,
    c: net.ripster.mobile.ui.theme.RipsterColors,
    lang: net.ripster.mobile.ui.i18n.AppLang,
) {
    val lyrics by produceState<LyricsClient.Lyrics?>(
        initialValue = null,
        key1 = state.title, key2 = state.artist,
    ) {
        value = runCatching {
            LyricsClient.fetch(state.artist, state.title, (state.durationMs / 1000).toInt())
        }.getOrNull() ?: LyricsClient.Lyrics(null, emptyList())
    }

    val listState = rememberLazyListState()
    val body = lyrics
    when {
        body == null ->
            Centered(tr("search.checking", lang), c)
        body.synced.isNotEmpty() -> {
            val activeIdx = body.synced.indexOfLast { it.atMs <= state.positionMs }.coerceAtLeast(0)
            // Активная строка держится по центру: скроллим к ней с отрицательным
            // смещением на пол-вьюпорта — текст «плывёт» вверх, центр статичен.
            LaunchedEffect(activeIdx) {
                val info = listState.layoutInfo
                val vp = info.viewportSize.height
                val lineH = info.visibleItemsInfo.firstOrNull { it.index == activeIdx }?.size ?: 0
                val off = -((vp / 2) - (lineH / 2)).coerceAtLeast(0)
                runCatching { listState.animateScrollToItem(activeIdx, off) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 240.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(body.synced) { i, line ->
                    val d = i - activeIdx
                    val active = d == 0
                    // ушедшее вверх — глуше; предстоящее внизу — чуть светлее.
                    val alpha = when {
                        active -> 1f
                        d < 0 -> (0.40f - 0.06f * (-d)).coerceIn(0.10f, 0.40f)
                        else -> (0.60f - 0.07f * d).coerceIn(0.16f, 0.60f)
                    }
                    val fs by animateFloatAsState(if (active) 19f else 15.5f, tween(220), label = "ly-fs")
                    BasicText(
                        line.text.ifBlank { "♪" },
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        style = TextStyle(
                            color = (if (active) c.text_primary else c.text_secondary).copy(alpha = alpha),
                            fontSize = fs.sp,
                            lineHeight = (fs + 7f).sp,
                            fontWeight = if (active) FontWeight.W700 else FontWeight.W500,
                        ),
                    )
                }
            }
        }
        !body.plain.isNullOrBlank() ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
                BasicText(body.plain, style = TextStyle(color = c.text_secondary, fontSize = 15.sp, lineHeight = 24.sp))
                Spacer(Modifier.height(24.dp))
            }
        else ->
            Centered(tr("ref.no_lyrics", lang), c)
    }
}

@Composable
private fun SpectrumPanel(
    app: RipsterApp,
    c: net.ripster.mobile.ui.theme.RipsterColors,
    lang: net.ripster.mobile.ui.i18n.AppLang,
) {
    val ctx = LocalContext.current
    val pb by app.player.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    val path = pb.currentPath
    val ext = path?.substringAfterLast('.', "")?.lowercase()?.take(5)
    val style = net.ripster.mobile.core.audio.Spectrogram.Style.byId(settings.spectrumStyle)

    // «Локальный» = уже скачанный файл (file://, content://, голый путь).
    val isLocal = path != null && !path.startsWith("http", ignoreCase = true)

    // 0 — строю, 1 — готово, 2 — не удалось, 3 — нет трека вообще
    var phase by remember(path, style) { mutableStateOf(0) }
    var result by remember(path, style) { mutableStateOf<net.ripster.mobile.core.audio.Spectrogram.Result?>(null) }
    LaunchedEffect(path, style) {
        phase = 0; result = null
        if (path == null) { phase = 3; return@LaunchedEffect }
        // Локальный файл — разбираем как есть. Сетевой поток (он УЖЕ играет,
        // значит байты доступны) — тянем начало во временный файл (Deezer по
        // пути расшифровываем) и строим спектр по нему.
        val src: String? = if (isLocal) path else
            net.ripster.mobile.core.audio.SpectrumSource.fetchPlayingToTemp(ctx, path)?.absolutePath
        if (src == null) { phase = 2; return@LaunchedEffect }
        val srcExt = if (isLocal) ext else src.substringAfterLast('.', "").lowercase().take(5)
        val r = runCatching {
            net.ripster.mobile.core.audio.Spectrogram.analyze(ctx, src, style, heightPx = 360, containerExt = srcExt)
        }.getOrNull()
        if (!isLocal) runCatching { java.io.File(src).delete() }
        result = r
        phase = if (r != null) 1 else 2
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ── пять пресетов вида (как в ПК-версии) ─────────────────────
        val styles = net.ripster.mobile.core.audio.Spectrogram.Style.entries
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            styles.forEach { s ->
                val on = s == style
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) c.surface_active else c.surface_raised)
                        .border(1.dp, if (on) c.accent_text else c.border_subtle, RoundedCornerShape(20.dp))
                        .clickable { app.settings.update { it.copy(spectrumStyle = s.id) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    BasicText(
                        tr("spec.${s.id}", lang),
                        style = TextStyle(color = if (on) c.accent_text else c.text_tertiary, fontSize = 11.sp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            phase == 0 -> Centered(tr("ref.spectrum_building", lang), c)
            phase == 3 -> Centered(tr("ref.no_track", lang), c)
            phase == 2 || result == null -> Centered(tr("ref.spectrum_fail", lang), c)
            else -> {
                val r = result!!
                Image(
                    bitmap = r.bitmap.asImageBitmap(),
                    contentDescription = tr("ref.spectrum", lang),
                    modifier = Modifier.fillMaxWidth().aspectRatio(900f / 460f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF111318))
                        .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(12.dp))
                // ── вердикт по всему потоку ─────────────────────────
                val (mark, col) = when (r.verdict) {
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.LOSSLESS -> "✓" to c.accent_text
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.LOSSLESS_SOFT -> "✓" to c.text_secondary
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.LOSSY -> "·" to c.text_secondary
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.FAKE -> "⚠" to c.warning_text
                    else -> "?" to c.text_tertiary
                }
                val vkey = "spec.v_${r.verdict.name.lowercase()}"
                var hintOpen by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(
                        "$mark  " + tr(vkey, lang)
                            .replace("{cut}", "%.1f".format(r.cutoffKHz))
                            .replace("{ny}", "%.1f".format(r.sampleRateHz / 2000f)),
                        modifier = Modifier.weight(1f),
                        style = TextStyle(color = col, fontSize = 12.5.sp, fontWeight = FontWeight.W600, lineHeight = 18.sp),
                    )
                    HelpDot(open = hintOpen, c = c) { hintOpen = !hintOpen }
                }
                if (hintOpen) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        tr("ref.spectrum_hint", lang),
                        style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, lineHeight = 16.sp),
                    )
                }
            }
        }
    }
}

// ── «всё про поток» — инспектор: метаданные + спектр + вердикт ──
@Composable
private fun StreamInfoPanel(
    app: RipsterApp,
    c: net.ripster.mobile.ui.theme.RipsterColors,
    lang: net.ripster.mobile.ui.i18n.AppLang,
) {
    val ctx = LocalContext.current
    val pb by app.player.state.collectAsState()
    val path = pb.currentPath
    val ext = path?.substringAfterLast('.', "")?.lowercase()?.take(5)

    val info by produceState<net.ripster.mobile.core.audio.StreamInfo?>(initialValue = null, path) {
        value = if (path == null) null else runCatching { net.ripster.mobile.core.audio.StreamProbe.probe(ctx, path) }.getOrNull()
    }
    var specPhase by remember(path) { mutableStateOf(0) }
    var spec by remember(path) { mutableStateOf<net.ripster.mobile.core.audio.Spectrogram.Result?>(null) }
    LaunchedEffect(path) {
        specPhase = 0; spec = null
        if (path == null) { specPhase = 2; return@LaunchedEffect }
        val r = runCatching {
            net.ripster.mobile.core.audio.Spectrogram.analyze(ctx, path, net.ripster.mobile.core.audio.Spectrogram.Style.RIPSTER, heightPx = 360, containerExt = ext)
        }.getOrNull()
        spec = r; specPhase = if (r != null) 1 else 2
    }

    if (path == null) { Centered("—", c); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // таблица характеристик
        info?.let { i ->
            i.row().forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    BasicText(tr(k, lang), Modifier.width(120.dp), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
                    BasicText(v, style = TextStyle(color = c.text_primary, fontSize = 12.sp, fontWeight = FontWeight.W600))
                }
            }
        } ?: BasicText(tr("tools.analyzing", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))

        Spacer(Modifier.height(14.dp))
        // спектр
        when {
            specPhase == 0 -> BasicText(tr("ref.spectrum_building", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
            specPhase == 2 || spec == null -> BasicText(tr("ref.spectrum_fail", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
            else -> {
                val r = spec!!
                Image(
                    bitmap = r.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(900f / 460f)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF111318))
                        .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(10.dp))
                val (mark, col) = when (r.verdict) {
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.LOSSLESS -> "✓" to c.accent_text
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.LOSSLESS_SOFT -> "✓" to c.text_secondary
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.LOSSY -> "·" to c.text_secondary
                    net.ripster.mobile.core.audio.Spectrogram.Verdict.FAKE -> "⚠" to c.warning_text
                    else -> "?" to c.text_tertiary
                }
                BasicText(
                    "$mark  " + tr("spec.v_${r.verdict.name.lowercase()}", lang)
                        .replace("{cut}", "%.1f".format(r.cutoffKHz))
                        .replace("{ny}", "%.1f".format(r.sampleRateHz / 2000f)),
                    style = TextStyle(color = col, fontSize = 12.5.sp, fontWeight = FontWeight.W600, lineHeight = 18.sp),
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    BasicText("Срез", Modifier.width(120.dp), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
                    BasicText("%.1f kHz  (Nyquist %.1f)".format(r.cutoffKHz, r.sampleRateHz / 2000f),
                        style = TextStyle(color = c.text_primary, fontSize = 12.sp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    BasicText("Кирпичная стена", Modifier.width(120.dp), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
                    BasicText(if (r.brickwall) "да" else "нет", style = TextStyle(color = c.text_primary, fontSize = 12.sp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(path, style = TextStyle(color = c.text_tertiary, fontSize = 10.sp, lineHeight = 14.sp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Centered(text: String, c: net.ripster.mobile.ui.theme.RipsterColors) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        BasicText(text, style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
    }
}

// ── детали ──────────────────────────────────────────────────────────────

@Composable
private fun RefPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val c = RipsterTheme.colors
    val brush = Brush.linearGradient(listOf(c.accent_hover, c.accent_fill))
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, label = "play-press")
    Box(Modifier.size(72.dp).scale(s), contentAlignment = Alignment.Center) {
      // неоновое свечение primary-кнопки (design-system-neon.md: --glow)
      Canvas(Modifier.matchParentSize()) {
          drawCircle(
              Brush.radialGradient(
                  listOf(c.accent_fill.copy(alpha = 0.55f), Color.Transparent),
                  radius = size.minDimension / 2f,
              ),
              radius = size.minDimension / 2f,
          )
      }
      Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(brush)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
      ) {
        Canvas(Modifier.size(26.dp)) {
            val col = c.text_on_fill
            if (isPlaying) {
                val bw = size.width * 0.26f
                drawLine(col, Offset(size.width * 0.32f, size.height * 0.08f), Offset(size.width * 0.32f, size.height * 0.92f), bw, StrokeCap.Round)
                drawLine(col, Offset(size.width * 0.68f, size.height * 0.08f), Offset(size.width * 0.68f, size.height * 0.92f), bw, StrokeCap.Round)
            } else {
                val p = Path().apply {
                    moveTo(size.width * 0.20f, size.height * 0.10f)
                    lineTo(size.width * 0.90f, size.height * 0.50f)
                    lineTo(size.width * 0.20f, size.height * 0.90f)
                    close()
                }
                drawPath(p, col)
            }
        }
      }
    }
}

@Composable
private fun SideGlyph(
    onClick: () -> Unit,
    cd: String,
    box: androidx.compose.ui.unit.Dp = 48.dp,
    glyph: androidx.compose.ui.unit.Dp = 24.dp,
    tint: Color? = null,
    draw: DrawScope.(Color) -> Unit,
) {
    val c = RipsterTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.82f else 1f, label = "side-press")
    Box(
        Modifier.size(box).scale(s)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(glyph)) { draw(tint ?: c.text_secondary) } }
}

// ── ♥ — крупная, с анимацией нажатия, заливается при активe ──
@Composable
private fun LikeButton(liked: Boolean, onClick: () -> Unit) {
    val c = RipsterTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.82f else 1f, label = "like-press")
    Box(
        Modifier.size(48.dp).scale(s)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val col = if (liked) c.accent_text else c.text_tertiary
            // тот же выверенный контур, что heartPath — не вылезает за Canvas
            val w = size.width; val h = size.height
            val p = Path().apply {
                moveTo(w * 0.5f, h * 0.86f)
                cubicTo(w * 0.06f, h * 0.56f, w * 0.14f, h * 0.12f, w * 0.5f, h * 0.32f)
                cubicTo(w * 0.86f, h * 0.12f, w * 0.94f, h * 0.56f, w * 0.5f, h * 0.86f)
                close()
            }
            if (liked) drawPath(p, col)
            else drawPath(p, col, style = Stroke(width = size.minDimension * 0.10f))
        }
    }
}

// ── визуальный знак честности качества на углу обложки (без слов) ──
@Composable
private fun QualityMark(
    state: QualityBadgeState,
    c: net.ripster.mobile.ui.theme.RipsterColors,
    modifier: Modifier = Modifier,
) {
    val (kind, col) = when (state) {
        QualityBadgeState.Match -> 0 to c.accent_text
        QualityBadgeState.NotMeasured -> 1 to c.text_tertiary
        is QualityBadgeState.Measuring -> 1 to c.text_tertiary
        is QualityBadgeState.Mismatch -> 2 to c.warning_text
        is QualityBadgeState.Fake -> 3 to c.danger_text
    }
    Box(
        modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
            .background(Color.Black.copy(alpha = 0.44f))
            .border(1.dp, col.copy(alpha = 0.55f), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val w = size.width; val h = size.height
            when (kind) {
                0 -> {
                    drawLine(col, Offset(w * 0.12f, h * 0.55f), Offset(w * 0.40f, h * 0.82f), w * 0.16f, StrokeCap.Round)
                    drawLine(col, Offset(w * 0.40f, h * 0.82f), Offset(w * 0.88f, h * 0.18f), w * 0.16f, StrokeCap.Round)
                }
                1 -> drawCircle(col, w * 0.16f, Offset(w * 0.5f, h * 0.5f))
                else -> {
                    drawLine(col, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.62f), w * 0.18f, StrokeCap.Round)
                    drawCircle(col, w * 0.11f, Offset(w * 0.5f, h * 0.86f))
                }
            }
        }
    }
}

// ── компактный знак честности качества (замена полноразмерного QualityBadge) ──
@Composable
private fun CompactQuality(
    state: QualityBadgeState,
    c: net.ripster.mobile.ui.theme.RipsterColors,
    lang: net.ripster.mobile.ui.i18n.AppLang,
) {
    val (txt, col, warn) = when (state) {
        QualityBadgeState.Match -> Triple(tr("q.ok", lang), c.text_tertiary, false)
        QualityBadgeState.NotMeasured -> Triple(tr("q.raw", lang), c.text_tertiary, false)
        is QualityBadgeState.Measuring -> Triple(tr("q.check", lang), c.text_tertiary, false)
        is QualityBadgeState.Mismatch -> Triple(tr("q.mismatch", lang), c.warning_text, true)
        is QualityBadgeState.Fake -> Triple(tr("q.fake", lang), c.warning_text, true)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(7.dp)) { drawCircle(col) }
        BasicText(
            txt,
            style = TextStyle(color = col, fontSize = 11.sp, fontWeight = if (warn) FontWeight.W700 else FontWeight.W500),
        )
    }
}

// ── квадратная кнопка действия (иконка + подпись, анимация нажатия) ──
@Composable
private fun SquareAction(label: String, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val c = RipsterTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, label = "sq-press")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier
                .size(54.dp)
                .scale(s)
                .clip(RoundedCornerShape(15.dp))
                .background(c.surface_raised)
                .border(1.dp, c.border_subtle, RoundedCornerShape(15.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(21.dp)) { draw(c.text_secondary) } }
        BasicText(label, style = TextStyle(color = c.text_tertiary, fontSize = 10.sp))
    }
}

// ── эквалайзер прямо в плеере (нативный audiofx) ──
@Composable
private fun EqPanel(
    c: net.ripster.mobile.ui.theme.RipsterColors,
    lang: net.ripster.mobile.ui.i18n.AppLang,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { runCatching { AudioEffects.init(ctx.applicationContext) } }
    val cfg by AudioEffects.config.collectAsState()
    val bands by AudioEffects.bands.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(tr("eq.enabled", lang), style = TextStyle(color = c.text_primary, fontSize = 14.sp, fontWeight = FontWeight.W600))
            Spacer(Modifier.weight(1f))
            Toggle(cfg.enabled) { AudioEffects.setEnabled(it) }
        }
        if (bands.presetNames.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            BasicText(tr("eq.unavailable", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
        } else {
            Spacer(Modifier.height(16.dp))
            BasicText(tr("eq.preset", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                bands.presetNames.forEachIndexed { i, name ->
                    val on = cfg.preset == i
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (on) c.surface_active else c.surface_raised)
                            .border(1.dp, if (on) c.accent_text else c.border_subtle, RoundedCornerShape(16.dp))
                            .clickable { AudioEffects.setPreset(i) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        BasicText(name, style = TextStyle(color = if (on) c.accent_text else c.text_tertiary, fontSize = 11.sp))
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        // Потолки занижены сознательно: bass/virt 1000 и loudness +20 дБ
        // гарантированно клиппят на громком материале (жалоба «странный звук»).
        FxSlider(tr("eq.bass", lang), cfg.bassBoost, 700) { AudioEffects.setBassBoost(it) }
        FxSlider(tr("eq.stereo", lang), cfg.virtualizer, 700) { AudioEffects.setVirtualizer(it) }
        FxSlider(tr("eq.loudness", lang), cfg.loudnessMdB, 700) { AudioEffects.setLoudness(it) }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface_raised)
                .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp))
                .clickable { AudioEffects.resetFlat() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) { BasicText(tr("eq.reset", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = RipsterTheme.colors
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (on) c.accent_fill else c.surface_active)
            .clickable { onChange(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(20.dp).clip(CircleShape).background(c.text_on_fill)) }
}

@Composable
private fun FxSlider(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    val c = RipsterTheme.colors
    var widthPx by remember { mutableStateOf(1) }
    val frac = (value.toFloat() / max).coerceIn(0f, 1f)
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = TextStyle(color = c.text_secondary, fontSize = 12.sp))
            Spacer(Modifier.weight(1f))
            BasicText("${(frac * 100).toInt()}%", style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(26.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(max) {
                    detectTapGestures { o -> onChange(((o.x / widthPx) * max).toInt().coerceIn(0, max)) }
                }
                .pointerInput(max) {
                    detectHorizontalDragGestures { change, _ ->
                        onChange(((change.position.x / widthPx) * max).toInt().coerceIn(0, max))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surface_active))
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent_fill))
        }
    }
}

private fun DrawScope.listGlyph(color: Color) {
    val w = size.width; val h = size.height
    for (i in 0..2) {
        val y = h * (0.22f + i * 0.28f)
        drawLine(color, Offset(w * 0.08f, y), Offset(w * 0.16f, y), w * 0.1f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.30f, y), Offset(w * 0.92f, y), w * 0.1f, StrokeCap.Round)
    }
}

private fun DrawScope.lyricsGlyph(color: Color) {
    val w = size.width; val h = size.height
    val widths = listOf(0.9f, 0.6f, 0.82f, 0.45f)
    widths.forEachIndexed { i, fw ->
        val y = h * (0.16f + i * 0.23f)
        drawLine(color, Offset(w * 0.08f, y), Offset(w * fw, y), w * 0.09f, StrokeCap.Round)
    }
}

private fun DrawScope.barsGlyph(color: Color) {
    val w = size.width; val h = size.height
    val hs = listOf(0.5f, 0.85f, 0.35f, 0.7f, 0.55f)
    hs.forEachIndexed { i, fh ->
        val x = w * (0.12f + i * 0.19f)
        drawLine(color, Offset(x, h * 0.92f), Offset(x, h * (0.92f - fh)), w * 0.1f, StrokeCap.Round)
    }
}

private fun DrawScope.eqGlyph(color: Color) {
    val w = size.width; val h = size.height
    val knobs = listOf(0.35f, 0.62f, 0.45f)
    knobs.forEachIndexed { i, ky ->
        val x = w * (0.2f + i * 0.3f)
        drawLine(color.copy(alpha = 0.45f), Offset(x, h * 0.1f), Offset(x, h * 0.9f), w * 0.06f, StrokeCap.Round)
        drawCircle(color, w * 0.1f, Offset(x, h * ky))
    }
}

/** Каст на Станцию — рамка «экрана» + волны сигнала в углу. */
private fun DrawScope.castGlyph(color: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawRect(
        color, topLeft = Offset(w * 0.12f, h * 0.18f),
        size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.52f),
        style = Stroke(width = sw),
    )
    drawArc(color, 210f, 60f, false, Offset(w * 0.02f, h * 0.5f), androidx.compose.ui.geometry.Size(w * 0.34f, h * 0.34f), style = Stroke(sw, cap = StrokeCap.Round))
    drawArc(color, 210f, 60f, false, Offset(w * -0.06f, h * 0.42f), androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.5f), style = Stroke(sw, cap = StrokeCap.Round))
    drawCircle(color, w * 0.06f, Offset(w * 0.16f, h * 0.82f))
}

@Composable
private fun CircleGlyph(
    onClick: () -> Unit,
    cd: String,
    tint: Color? = null,
    plain: Boolean = false,
    draw: DrawScope.(Color) -> Unit,
) {
    val c = RipsterTheme.colors
    Box(
        Modifier
            .size(34.dp)
            .then(if (plain) Modifier else Modifier.clip(CircleShape).background(c.surface_raised))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(16.dp)) { draw(tint ?: c.text_secondary) } }
}

@Composable
private fun IconChip(active: Boolean, onClick: () -> Unit, cd: String, draw: DrawScope.(Color) -> Unit) {
    val c = RipsterTheme.colors
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) c.surface_active else c.surface_raised)
            .border(1.dp, if (active) c.accent_text else c.border_subtle, RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(16.dp)) { draw(if (active) c.accent_text else c.text_secondary) } }
}

@Composable
private fun TextChip(label: String, onClick: () -> Unit) {
    val c = RipsterTheme.colors
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface_raised)
            .border(1.dp, c.border_subtle, RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = TextStyle(color = c.text_secondary, fontSize = 12.sp)) }
}

/** Средний цвет обложки (Coil → маленький bitmap → усреднение RGB).
 *  Берёт URL, а если его нет — байты встроенной в файл картинки. */
@Composable
private fun rememberCoverAvg(url: String?, embedded: ByteArray? = null): Color? {
    val ctx = LocalContext.current
    return produceState<Color?>(initialValue = null, url, embedded) {
        value = null
        val data: Any = url?.takeIf { it.startsWith("http") } ?: embedded ?: return@produceState
        value = runCatching {
            val req = ImageRequest.Builder(ctx).data(data).size(24).allowHardware(false).build()
            val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap(24, 24)
                ?: return@runCatching null
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24 and 0xFF) < 128) continue
                r += (p ushr 16 and 0xFF); g += (p ushr 8 and 0xFF); b += (p and 0xFF); n++
            }
            if (n == 0L) null else Color(r.toInt() / n.toInt(), g.toInt() / n.toInt(), b.toInt() / n.toInt())
        }.getOrNull()
    }.value
}

/** Встроенная в аудиофайл обложка (ID3/MP4 covr) → байты, или null. */
@Composable
private fun rememberEmbeddedArt(path: String?): ByteArray? {
    val ctx = LocalContext.current
    return produceState<ByteArray?>(initialValue = null, path) {
        value = null
        val p = path ?: return@produceState
        value = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    if (p.startsWith("content://") || p.startsWith("file://")) {
                        mmr.setDataSource(ctx, android.net.Uri.parse(p))
                    } else {
                        mmr.setDataSource(p)
                    }
                    mmr.embeddedPicture
                } finally {
                    runCatching { mmr.release() }
                }
            }
        }.getOrNull()
    }.value
}

private fun DrawScope.heartPath(color: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.86f)
        cubicTo(w * 0.06f, h * 0.56f, w * 0.14f, h * 0.12f, w * 0.5f, h * 0.32f)
        cubicTo(w * 0.86f, h * 0.12f, w * 0.94f, h * 0.56f, w * 0.5f, h * 0.86f)
        close()
    }
    drawPath(p, color)
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
