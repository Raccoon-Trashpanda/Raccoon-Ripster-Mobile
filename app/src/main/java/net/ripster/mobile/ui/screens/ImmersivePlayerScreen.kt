package net.ripster.mobile.ui.screens

import net.ripster.mobile.ui.components.MARQUEE_SECOND_LINE_DELAY
import net.ripster.mobile.ui.components.ripsterMarquee
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.ui.components.Cover
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Третий стиль плеера — «Погружение». Обложка на весь экран, край в край;
 * снизу — тёмный градиент под подписи и тонкая стеклянная панель управления.
 * Никаких вкладок и рамок: обложка И ЕСТЬ экран. Свайп влево/вправо по
 * обложке — предыдущий/следующий трек.
 */
@Composable
fun ImmersivePlayerScreen(
    state: NowPlayingState,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val app = RipsterApp.from(LocalContext.current)
    var dragAcc by remember { mutableStateOf(0f) }
    // 0 — нет, 1 — трек-лист, 2 — текст, 3 — спектр, 4 — эквалайзер, 6 — каст.
    // Те же панели, что у Mockup-плеера (владелец 13.09.2026: «в иммерсиве только
    // три кнопки, где эквалайзер и прочие»). Переиспользуем их 1-в-1.
    var sheet by remember { mutableStateOf(0) }
    // Таймер сна: подпись кнопки показывает обратный отсчёт, пока он активен.
    val sleep by app.player.sleep.collectAsState()
    var sleepTick by remember { mutableStateOf(0L) }
    LaunchedEffect(sleep.active) {
        while (sleep.active) { sleepTick = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(1000) }
    }
    val sleepLabel = when {
        !sleep.active -> tr("ref.sleep", lang)
        sleep.endOfTrack -> "♪ →"
        else -> {
            val left = (sleep.fireAtElapsed - (sleepTick.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime())).coerceAtLeast(0L)
            "${left / 60000}:${((left / 1000) % 60).toString().padStart(2, '0')}"
        }
    }
    // Честная волна для сик-бара (декод локального файла, кэш); нет/стрим → линия.
    val wfCtx = LocalContext.current
    val currentPath = app.player.state.collectAsState().value.currentPath
    val wfPeaks by androidx.compose.runtime.produceState<FloatArray?>(null, currentPath) {
        value = net.ripster.mobile.core.audio.Waveform.peaks(wfCtx, currentPath)
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF07070A))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAcc <= -120f) onNext() else if (dragAcc >= 120f) onPrevious()
                        dragAcc = 0f
                    },
                ) { _, d -> dragAcc += d }
            },
    ) {
        // обложка — на весь экран, ЖИВАЯ: наш собственный моушн (дыхание +
        // магический перелив по палитре), генерим из статики, а не тащим чужое
        // видео (решение владельца 13.09.2026).
        net.ripster.mobile.ui.components.LivingCover(
            url = state.artworkUrl,
            modifier = Modifier.fillMaxSize(),
        )
        // Низ отливает ПАЛИТРОЙ обложки, а не чёрным (референс владельца
        // 13.09.2026): верх — чистая обложка, книзу цвет плавно сгущается в
        // тёмный тон самой картинки, и на нём читается текст. Тон берём из
        // краёв обложки (та же проба, что у ореола) — так фон «живой».
        val pal = net.ripster.mobile.ui.components.rememberCoverEdgePalette(state.artworkUrl)
        val tint = run {
            // средний цвет нижних краёв, затемнённый до фона под текст
            val base = pal.getOrElse(2) { Color(0xFF14141A) }
            val b2 = pal.getOrElse(3) { base }
            androidx.compose.ui.graphics.lerp(
                androidx.compose.ui.graphics.lerp(base, b2, 0.5f), Color(0xFF07070A), 0.55f)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,                 // верх — обложка как есть
                    0.45f to Color.Transparent,
                    0.62f to tint.copy(alpha = 0.35f),
                    0.80f to tint.copy(alpha = 0.75f),       // низ отливает палитрой
                    1f to tint.copy(alpha = 0.96f),
                ),
            ),
        )

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp),
        ) {
            BasicText(
                state.title,
                maxLines = 1,
                modifier = Modifier.ripsterMarquee(),
                style = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.W800, letterSpacing = (-0.4).sp),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                if (state.album.isNotBlank() && !state.album.equals(state.title, true))
                    "${state.artist}  ·  ${state.album}" else state.artist,
                maxLines = 1,
                modifier = Modifier.ripsterMarquee(MARQUEE_SECOND_LINE_DELAY),
                style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp),
            )
            if (state.format.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                // Тап по строке формата → «Паспорт трека» (вердикт качества +
                // спектр + метаданные). Вход в диагностику из иммерсива.
                Row(
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { sheet = 5 },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    BasicText(
                        state.format,
                        style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp),
                    )
                    Canvas(Modifier.size(11.dp)) {
                        val w = size.width
                        drawCircle(Color.White.copy(alpha = 0.4f), w * 0.46f, style = Stroke(w * 0.1f))
                        drawLine(Color.White.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.42f),
                            androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.74f), w * 0.13f, StrokeCap.Round)
                        drawCircle(Color.White.copy(alpha = 0.4f), w * 0.07f, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.28f))
                    }
                }
            }
            if (state.title.isNotBlank()) {
                val route = rememberOutputRoute(lang)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(Modifier.size(12.dp)) { outGlyph(route.kind, Color.White.copy(alpha = 0.4f)) }
                    BasicText(
                        route.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.5.sp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Полоса позиции. Есть честные пики локального трека → волновой бар
            // (белым, читаемо на любой обложке); иначе тонкая линия.
            val wf = wfPeaks
            if (wf != null && state.durationMs > 0) {
                net.ripster.mobile.ui.components.WaveformSeek(
                    peaks = wf,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeek = onSeek,
                    tint = Color.White,
                    idle = Color.White.copy(alpha = 0.28f),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
            } else {
                val frac = if (state.durationMs > 0)
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                Canvas(
                    Modifier.fillMaxWidth().height(18.dp).pointerInput(state.durationMs) {
                        detectHorizontalDragGestures { change, _ ->
                            if (state.durationMs > 0)
                                onSeek((change.position.x / size.width * state.durationMs).toLong().coerceIn(0, state.durationMs))
                        }
                    },
                ) {
                    val y = size.height / 2
                    drawLine(Color.White.copy(alpha = 0.22f), androidx.compose.ui.geometry.Offset(0f, y),
                        androidx.compose.ui.geometry.Offset(size.width, y), 3f, StrokeCap.Round)
                    drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, y),
                        androidx.compose.ui.geometry.Offset(size.width * frac, y), 3f, StrokeCap.Round)
                    drawCircle(Color.White, 5f, androidx.compose.ui.geometry.Offset(size.width * frac, y))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(fmtT(state.positionMs), style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp))
                BasicText(fmtT(state.durationMs), style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp))
            }

            Spacer(Modifier.height(10.dp))

            // управление — стеклянная панель
            Row(
                Modifier.fillMaxWidth().clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphBtn(48.dp, onPrevious) { w ->
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.62f, w * 0.28f); lineTo(w * 0.62f, w * 0.72f); lineTo(w * 0.30f, w * 0.5f); close()
                    }
                    drawPath(p, Color.White)
                    drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.24f, w * 0.28f), androidx.compose.ui.geometry.Size(w * 0.05f, w * 0.44f))
                }
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(c.accent_fill),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading) {
                        // Индикация загрузки: видно, что нажатие сработало и трек
                        // готовится (жалоба владельца 13.09.2026 — «нет реакции»).
                        androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(26.dp), color = Color(0xFF0D0F13), strokeWidth = 2.5.dp,
                        )
                    } else Canvas(Modifier.size(24.dp)) {
                        val w = size.width
                        if (state.isPlaying) {
                            drawRect(Color(0xFF0D0F13), androidx.compose.ui.geometry.Offset(w * 0.16f, 0f), androidx.compose.ui.geometry.Size(w * 0.22f, w))
                            drawRect(Color(0xFF0D0F13), androidx.compose.ui.geometry.Offset(w * 0.62f, 0f), androidx.compose.ui.geometry.Size(w * 0.22f, w))
                        } else {
                            val p = androidx.compose.ui.graphics.Path().apply {
                                moveTo(w * 0.18f, 0f); lineTo(w * 0.18f, w); lineTo(w, w * 0.5f); close()
                            }
                            drawPath(p, Color(0xFF0D0F13))
                        }
                    }
                    // прозрачная кнопка поверх — пока грузится, тап игнорируем,
                    // чтобы повторные нажатия не перезапускали поток.
                    Box(Modifier.fillMaxSize().clip(CircleShape).pointerInput(state.loading) {
                        detectTapGestures(onTap = { if (!state.loading) onPlayPause() })
                    })
                }
                GlyphBtn(48.dp, onNext) { w ->
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.38f, w * 0.28f); lineTo(w * 0.38f, w * 0.72f); lineTo(w * 0.70f, w * 0.5f); close()
                    }
                    drawPath(p, Color.White)
                    drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.71f, w * 0.28f), androidx.compose.ui.geometry.Size(w * 0.05f, w * 0.44f))
                }
            }

            Spacer(Modifier.height(14.dp))

            // Действия — те же, что в Mockup-плеере, но в стекле под иммерсив:
            // трек-лист, текст, спектр, эквалайзер, каст. Открывают панель поверх.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                ImmAction(tr("ref.tracklist", lang), onClick = { sheet = 1 }) { listGlyph(it) }
                ImmAction(tr("ref.lyrics", lang), onClick = { sheet = 2 }) { lyricsGlyph(it) }
                ImmAction(tr("ref.spectrum", lang), onClick = { sheet = 3 }) { barsGlyph(it) }
                ImmAction(tr("ref.equalizer", lang), onClick = { sheet = 4 }) { eqGlyph(it) }
                ImmAction(sleepLabel, onClick = { sheet = 7 }, active = sleep.active) { moonGlyph(it) }
                ImmAction(tr("ref.cast", lang), onClick = { sheet = 6 }) { castGlyph(it) }
            }
        }

        // ── панель поверх плеера (те же панели, что у Mockup) ──────────
        if (sheet != 0) {
            BackHandler(enabled = true) { sheet = 0 }
            Column(
                Modifier.fillMaxSize().background(Color(0xFF07070A)),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { sheet = 0 }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("‹", style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        tr(when (sheet) {
                            1 -> "ref.tracklist"; 2 -> "ref.lyrics"; 3 -> "ref.spectrum"
                            5 -> "pass.title"; 6 -> "ref.cast"; 7 -> "sleep.title"; else -> "ref.equalizer"
                        }, lang),
                        style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.W700),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (sheet) {
                        1 -> TracklistPanel(app, c) { sheet = 0 }
                        2 -> LyricsPanel(state, c, lang)
                        3 -> SpectrumPanel(app, c, lang)
                        5 -> StreamInfoPanel(app, c, lang)
                        7 -> SleepPanel(app, c, lang)
                        6 -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        ) { net.ripster.mobile.ui.screens.cast.YandexStationBlock() }
                        else -> EqPanel(c, lang)
                    }
                }
            }
        }
    }
}

/** Стеклянная кнопка действия для иммерсив-плеера: иконка + подпись, полупрозрачная
 *  плитка поверх обложки (белым, чтобы читалось на любой картинке). */
@Composable
private fun ImmAction(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    draw: DrawScope.(Color) -> Unit,
) {
    // Активное действие (напр. заведённый таймер сна) — акцентная подсветка плитки.
    val accent = Color(0xFFFF6B8B)
    val glyphColor = if (active) accent else Color.White.copy(alpha = 0.9f)
    Column(
        modifier = Modifier.width(58.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                .background(if (active) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.12f))
                .border(1.dp, if (active) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(20.dp)) { draw(glyphColor) } }
        BasicText(
            label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = if (active) accent.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun GlyphBtn(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit) {
    Box(
        Modifier.size(size).pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.5f)) { draw(this.size.width) }
    }
}

private fun fmtT(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
