package net.ripster.mobile.ui.screens

import net.ripster.mobile.ui.components.MARQUEE_SECOND_LINE_DELAY
import net.ripster.mobile.ui.components.ripsterMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.components.PlayPauseButton
import net.ripster.mobile.ui.components.QualityBadge
import net.ripster.mobile.ui.components.QualityBadgeState
import net.ripster.mobile.ui.components.RipsterButton
import net.ripster.mobile.ui.components.ButtonLevel
import net.ripster.mobile.ui.components.SeekPlaybackState
import net.ripster.mobile.ui.components.SeekStrip
import net.ripster.mobile.ui.components.TransportIconButton
import net.ripster.mobile.ui.components.drawNextGlyph
import net.ripster.mobile.ui.components.drawPrevGlyph
import net.ripster.mobile.ui.components.drawRepeatGlyph
import net.ripster.mobile.ui.components.drawShuffleGlyph
import net.ripster.mobile.ui.theme.Radii
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Weights

/**
 * Now Playing — первый экран приложения, собранный целиком, не витриной
 * отдельных компонентов. Данные ниже — фиксированный препросмотр (см.
 * [NowPlayingState]): сетевого слоя ещё нет (см. HANDOFF_2026-08-23), поэтому
 * экран принимает состояние параметром, а не тянет его сам — когда появится
 * реальный источник (локальный плеер / режим сопряжения с ПК), он подставит
 * то же состояние без переписывания разметки.
 *
 * Раскладка — design/…/design_handoff_android_player (README, раздел 1), но
 * значения токенов — СВОИ, из net.ripster.mobile.ui.theme: тот пакет уже
 * реализует тот же контракт честности отдельно и точнее (SeekStrip, Badges),
 * поэтому здесь используется он, а не хардкод хекс-цветов из HTML-референса.
 * Вертикальная перемотка под палец — вне объёма этой сборки (см. SeekStrip.kt).
 */
data class NowPlayingState(
    val title: String,
    val artist: String,
    val album: String,
    val positionMs: Long,
    val durationMs: Long,
    /** Загружено в буфер, мс — для «полоски кэша» на шкале. */
    val bufferedMs: Long = 0,
    val isPlaying: Boolean,
    /** Идёт подготовка/буферизация трека — кнопка Play показывает спиннер и
     *  гасится, чтобы повторные тычки не перезапускали поток. */
    val loading: Boolean = false,
    val shuffle: Boolean,
    val repeat: Boolean,
    val quality: QualityBadgeState,
    val format: String, // "FLAC · 24-bit/96kHz" — моноширинная техническая строка
    val artworkUrl: String? = null,
)

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
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = net.ripster.mobile.ui.i18n.LocalAppLang.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface_canvas),
    ) {
        // ── СТЕКЛО (редизайн 14.09.2026, владелец: «третья тема — параша, переделай
        //    в стекло») ─────────────────────────────────────────────────────────
        // Слои: размытая обложка во весь экран как амбиент → тёмная вуаль для
        // читаемости → плавающая матовая стеклянная карта с контролами.
        // blur() работает с API 31; ниже — просто увеличенная обложка под вуалью,
        // тоже смотрится (не пропадает).
        // ВАЖНО: без живого Modifier.blur() — полноэкранный per-frame blur
        // рушит производительность (эмулятор: кадры по 1.2 c → ANR; слабые
        // устройства — джанк). Амбиент = увеличенная обложка под ПЛОТНОЙ тёмной
        // вуалью; «стекло» дают полупрозрачные панели поверх, а не размытие фона.
        net.ripster.mobile.ui.components.Cover(
            url = state.artworkUrl,
            modifier = Modifier.fillMaxSize().scale(1.15f),
            shape = androidx.compose.ui.graphics.RectangleShape,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xE607070A),
                    0.5f to Color(0xD90A0A0F),
                    1f to Color(0xF507070A),
                ),
            ),
        )

        val coverSide = (maxHeight * 0.34f).coerceAtMost(300.dp)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            // Обложка — чёткая, приподнятая, скруглённая.
            net.ripster.mobile.ui.components.Cover(
                url = state.artworkUrl,
                modifier = Modifier.size(coverSide)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
            )
            Spacer(Modifier.height(26.dp))

            // ── Матовая стеклянная карта со всем управлением ──
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    text = state.title,
                    maxLines = 1,
                    modifier = Modifier.ripsterMarquee(),
                    style = TextStyle(color = Color.White, fontSize = 23.sp,
                        fontWeight = Weights.Primary, letterSpacing = (-0.4).sp, textAlign = TextAlign.Center),
                )
                Spacer(Modifier.height(5.dp))
                BasicText(
                    text = if (state.album.isNotBlank() && !state.album.equals(state.title, true))
                        "${state.artist}  ·  ${state.album}" else state.artist,
                    maxLines = 1,
                    modifier = Modifier.ripsterMarquee(MARQUEE_SECOND_LINE_DELAY),
                    style = TextStyle(color = Color.White.copy(alpha = 0.66f), fontSize = 14.sp, textAlign = TextAlign.Center),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.format.isNotBlank()) BasicText(
                        text = state.format,
                        style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    )
                    QualityBadge(state = state.quality)
                }

                Spacer(Modifier.height(20.dp))
                SeekStrip(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeek = onSeek,
                    onScrubChange = onScrubPreview,
                    state = if (state.isPlaying) SeekPlaybackState.Playing else SeekPlaybackState.Paused,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                // Транспорт: стеклянные боковые + круглая акцентная Play.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassCircle(52.dp, onPrevious) { drawPrevGlyph(it) }
                    GlassPlay(isPlaying = state.isPlaying, loading = state.loading,
                        accent = colors.accent_fill, onClick = onPlayPause)
                    GlassCircle(52.dp, onNext) { drawNextGlyph(it) }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassChip(active = state.shuffle, onClick = onToggleShuffle,
                        accent = colors.accent_text) { drawShuffleGlyph(it) }
                    GlassChip(active = state.repeat, onClick = onToggleRepeat,
                        accent = colors.accent_text) { drawRepeatGlyph(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            // Скачать альбом — стеклянная кнопка во всю ширину.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .clickable { onDownloadAlbum() }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(net.ripster.mobile.ui.i18n.tr("np.dl_album", lang),
                    style = TextStyle(color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, fontWeight = Weights.Primary))
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Круглая стеклянная кнопка транспорта (prev/next). */
@Composable
private fun GlassCircle(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.88f else 1f, label = "glass-press")
    Box(
        Modifier.size(size).scale(s).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.42f)) { draw(Color.White.copy(alpha = 0.9f)) } }
}

/** Круглая акцентная Play со стеклянным свечением. */
@Composable
private fun GlassPlay(isPlaying: Boolean, loading: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, label = "play-press")
    Box(Modifier.size(74.dp).scale(s), contentAlignment = Alignment.Center) {
        // мягкое свечение
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Brush.radialGradient(
                listOf(accent.copy(alpha = 0.45f), Color.Transparent), radius = this.size.minDimension / 2f))
        }
        Box(
            Modifier.size(66.dp).clip(CircleShape).background(accent)
                .clickable(interactionSource = interaction, indication = null, enabled = !loading, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    Modifier.size(28.dp), color = Color(0xFF0D0F13), strokeWidth = 2.5.dp)
            } else Canvas(Modifier.size(26.dp)) {
                val w = size.width; val col = Color(0xFF0D0F13)
                if (isPlaying) {
                    drawRect(col, Offset(w * 0.16f, 0f), androidx.compose.ui.geometry.Size(w * 0.22f, w))
                    drawRect(col, Offset(w * 0.62f, 0f), androidx.compose.ui.geometry.Size(w * 0.22f, w))
                } else {
                    val p = Path().apply { moveTo(w * 0.2f, 0f); lineTo(w * 0.2f, w); lineTo(w, w * 0.5f); close() }
                    drawPath(p, col)
                }
            }
        }
    }
}

/** Стеклянный чип-переключатель (шафл/повтор): active подсвечен акцентом. */
@Composable
private fun GlassChip(active: Boolean, onClick: () -> Unit, accent: Color, draw: DrawScope.(Color) -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (active) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(19.dp)) { draw(if (active) accent else Color.White.copy(alpha = 0.7f)) } }
}
