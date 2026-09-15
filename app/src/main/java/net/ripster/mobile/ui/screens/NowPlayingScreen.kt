package net.ripster.mobile.ui.screens

import net.ripster.mobile.ui.components.MARQUEE_SECOND_LINE_DELAY
import net.ripster.mobile.ui.components.ripsterMarquee
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import net.ripster.mobile.ui.components.WaveformSeek
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.components.QualityBadge
import net.ripster.mobile.ui.components.QualityBadgeState
import net.ripster.mobile.ui.components.SeekPlaybackState
import net.ripster.mobile.ui.components.SeekStrip
import net.ripster.mobile.ui.components.drawNextGlyph
import net.ripster.mobile.ui.components.drawPrevGlyph
import net.ripster.mobile.ui.components.drawRepeatGlyph
import net.ripster.mobile.ui.components.drawShuffleGlyph
import net.ripster.mobile.ui.components.rememberCoverEdgePalette
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr

/**
 * Now Playing — тема «Studio». Данные приходят параметром [NowPlayingState].
 */
data class NowPlayingState(
    val title: String,
    val artist: String,
    val album: String,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long = 0,
    val isPlaying: Boolean,
    val loading: Boolean = false,
    val shuffle: Boolean,
    val repeat: Boolean,
    val quality: QualityBadgeState,
    val format: String,
    val artworkUrl: String? = null,
)

/**
 * Тема «Studio» — кинематографичный now-playing (редизайн 14.09.2026 по дизайн-
 * процессу apply-aesthetic; владелец: прежняя «параша/убожество»).
 *
 * Решения против ИИ-клише:
 *  · ФОН — палитровая заливка ИЗ обложки (её же цвета, затемнённые в near-black),
 *    а НЕ сырое фото и не серая стеклянная коробка. Под обложкой — мягкое цветное
 *    свечение из той же палитры (глубина без дорогого blur).
 *  · Контролы «плавают» на заливке, без тяжёлой карты.
 *  · Есть полноценный ряд действий (Трек-лист/Текст/Спектр/Эквалайзер/Каст) —
 *    раньше в этой теме их не было вовсе; «Скачать альбом» — компактной иконкой
 *    среди них, а не огромной кнопкой.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingState,
    onSeek: (Long) -> Unit,
    onScrubPreview: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDownloadAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val app = RipsterApp.from(LocalContext.current)
    var sheet by remember { mutableStateOf(0) }
    // Таймер сна: подпись кнопки — обратный отсчёт, пока активен.
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

    // Волновой сик-бар: честные пики трека. Декод локального файла на IO + кэш;
    // нет пути/стрим/не вышло → null → откат на обычную полосу.
    val ctx = LocalContext.current
    val currentPath by remember { derivedStateOf { app.player.state.value.currentPath } }
    val peaks by androidx.compose.runtime.produceState<FloatArray?>(null, currentPath) {
        value = net.ripster.mobile.core.audio.Waveform.peaks(ctx, currentPath)
    }

    // Палитра краёв обложки → цвет заливки/свечения. Затемняем к near-black.
    val palette = rememberCoverEdgePalette(state.artworkUrl)
    val deep = Color(0xFF07070A)
    val topTint = lerp(palette.getOrElse(0) { deep }, deep, 0.62f)
    val glow = palette.getOrElse(1) { c.accent_fill }

    BoxWithConstraints(modifier.fillMaxSize().background(deep)) {
        // ── амбиент: вертикальная заливка цветами обложки → тьма ──
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to topTint, 0.55f to lerp(topTint, deep, 0.7f), 1f to deep),
            ),
        )

        val coverSide = (maxHeight * 0.36f).coerceAtMost(320.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(34.dp))
            // Обложка с мягким цветным свечением снизу.
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(coverSide + 60.dp)) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.34f), Color.Transparent),
                            radius = size.minDimension / 2f,
                        ),
                        radius = size.minDimension / 2f,
                        center = Offset(size.width / 2f, size.height * 0.62f),
                    )
                }
                net.ripster.mobile.ui.components.Cover(
                    url = state.artworkUrl,
                    modifier = Modifier.size(coverSide).clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
            BasicText(
                state.title, maxLines = 1, modifier = Modifier.ripsterMarquee(),
                style = TextStyle(color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.W800,
                    letterSpacing = (-0.5).sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                if (state.album.isNotBlank() && !state.album.equals(state.title, true))
                    "${state.artist}  ·  ${state.album}" else state.artist,
                maxLines = 1, modifier = Modifier.ripsterMarquee(MARQUEE_SECOND_LINE_DELAY),
                style = TextStyle(color = Color.White.copy(alpha = 0.64f), fontSize = 14.5.sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(12.dp))
            // формат + вердикт качества, тап → «Паспорт трека»
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { sheet = 5 }.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (state.format.isNotBlank()) BasicText(
                    state.format,
                    style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                )
                QualityBadge(state = state.quality)
            }

            if (state.title.isNotBlank()) {
                val route = rememberOutputRoute(lang)
                Spacer(Modifier.height(9.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(Modifier.size(13.dp)) { outGlyph(route.kind, Color.White.copy(alpha = 0.42f)) }
                    BasicText(
                        route.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = Color.White.copy(alpha = 0.42f), fontSize = 11.sp),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            val wf = peaks
            if (wf != null && state.durationMs > 0) {
                WaveformSeek(
                    peaks = wf, positionMs = state.positionMs, durationMs = state.durationMs,
                    onSeek = onSeek, onScrubChange = onScrubPreview,
                    tint = c.accent_fill,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
            } else {
                SeekStrip(
                    positionMs = state.positionMs, durationMs = state.durationMs,
                    onSeek = onSeek, onScrubChange = onScrubPreview,
                    state = if (state.isPlaying) SeekPlaybackState.Playing else SeekPlaybackState.Paused,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(22.dp))
            // Транспорт: шафл · prev · [Play] · next · повтор
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleGlyph(active = state.shuffle, onClick = onToggleShuffle, accent = c.accent_text) { drawShuffleGlyph(it) }
                GlassCircle(52.dp, onPrevious) { drawPrevGlyph(it) }
                AccentPlay(isPlaying = state.isPlaying, loading = state.loading, accent = c.accent_fill, onClick = onPlayPause)
                GlassCircle(52.dp, onNext) { drawNextGlyph(it) }
                ToggleGlyph(active = state.repeat, onClick = onToggleRepeat, accent = c.accent_text) { drawRepeatGlyph(it) }
            }

            Spacer(Modifier.height(24.dp))
            // Ряд действий (как в других темах) + компактная «Скачать».
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                StudioAction(tr("ref.tracklist", lang), onClick = { sheet = 1 }) { listGlyph(it) }
                StudioAction(tr("ref.lyrics", lang), onClick = { sheet = 2 }) { lyricsGlyph(it) }
                StudioAction(tr("ref.spectrum", lang), onClick = { sheet = 3 }) { barsGlyph(it) }
                StudioAction(tr("ref.equalizer", lang), onClick = { sheet = 4 }) { eqGlyph(it) }
                StudioAction(sleepLabel, onClick = { sheet = 7 }, active = sleep.active) { moonGlyph(it) }
                StudioAction(tr("ref.cast", lang), onClick = { sheet = 6 }) { castGlyph(it) }
                StudioAction(tr("np.dl_album", lang), onClick = onDownloadAlbum) { dlGlyph(it) }
            }
            Spacer(Modifier.height(30.dp))
        }

        // ── панели поверх (те же, что в других плеерах) ──
        if (sheet != 0) {
            BackHandler(enabled = true) { sheet = 0 }
            Column(Modifier.fillMaxSize().background(deep)) {
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
                        6 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            net.ripster.mobile.ui.screens.cast.YandexStationBlock()
                        }
                        7 -> SleepPanel(app, c, lang)
                        else -> EqPanel(c, lang)
                    }
                }
            }
        }
    }
}

/** Круглая стеклянная кнопка транспорта. */
@Composable
private fun GlassCircle(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.88f else 1f, label = "gc")
    Box(
        Modifier.size(size).scale(s).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.42f)) { draw(Color.White.copy(alpha = 0.9f)) } }
}

/** Плоский глиф-переключатель (шафл/повтор): active подсвечен акцентом. */
@Composable
private fun ToggleGlyph(active: Boolean, onClick: () -> Unit, accent: Color, draw: DrawScope.(Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.82f else 1f, label = "tg")
    Box(
        Modifier.size(44.dp).scale(s)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(20.dp)) { draw(if (active) accent else Color.White.copy(alpha = 0.55f)) } }
}

/** Круглая акцентная Play с мягким свечением. */
@Composable
private fun AccentPlay(isPlaying: Boolean, loading: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, label = "pp")
    Box(Modifier.size(72.dp).scale(s), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.4f), Color.Transparent), radius = this.size.minDimension / 2f))
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(accent)
                .clickable(interactionSource = interaction, indication = null, enabled = !loading, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(
                Modifier.size(26.dp), color = Color(0xFF0D0F13), strokeWidth = 2.5.dp)
            else Canvas(Modifier.size(25.dp)) {
                val w = size.width; val col = Color(0xFF0D0F13)
                if (isPlaying) {
                    drawRect(col, Offset(w * 0.16f, 0f), Size(w * 0.22f, w))
                    drawRect(col, Offset(w * 0.62f, 0f), Size(w * 0.22f, w))
                } else {
                    val p = Path().apply { moveTo(w * 0.2f, 0f); lineTo(w * 0.2f, w); lineTo(w, w * 0.5f); close() }
                    drawPath(p, col)
                }
            }
        }
    }
}

/** Компактная кнопка действия (иконка + мелкая подпись). */
@Composable
private fun StudioAction(label: String, onClick: () -> Unit, active: Boolean = false, draw: DrawScope.(Color) -> Unit) {
    val accent = Color(0xFFFF6B8B)
    Column(
        Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                .background(if (active) accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.07f))
                .border(1.dp, if (active) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(13.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(19.dp)) { draw(if (active) accent else Color.White.copy(alpha = 0.82f)) } }
        BasicText(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (active) accent.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.5f), fontSize = 9.5.sp, textAlign = TextAlign.Center),
        )
    }
}

/** Глиф «скачать» — стрелка вниз в лоток. */
private fun DrawScope.dlGlyph(color: Color) {
    val w = size.width; val h = size.height; val sw = w * 0.1f
    drawLine(color, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.62f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.5f, h * 0.64f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.72f, h * 0.42f), Offset(w * 0.5f, h * 0.64f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.16f, h * 0.86f), Offset(w * 0.84f, h * 0.86f), sw, StrokeCap.Round)
}
