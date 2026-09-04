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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        // Обложка честно ограничена ДОСТУПНОЙ высотой экрана, а не жёстким
        // aspectRatio(1f) на всю ширину — на компактных телефонах (см. хвост
        // из HANDOFF_2026-08-29: транспорт и «Скачать альбом» уезжали за
        // нижний край) квадрат на всю ширину съедал ~45% высоты и не оставлял
        // места остальному стеку. 0.38 подобрано от реального бюджета этого
        // экрана (title/artist/format/seek/transport/secondary/button ≈
        // фиксированные ~400dp при Normal-плотности) — не наугад, но и не
        // измерено через intrinsics соседей, поэтому дополнительно оставлен
        // verticalScroll ниже как честная страховка: на очень крупном
        // системном масштабе шрифта (Accessibility XXL) фиксированная часть
        // стека сама может вырасти сверх этой оценки — тогда докручивают,
        // а не обрезает контент немым переполнением.
        val coverMax = (maxHeight * 0.38f).coerceAtMost(360.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.gutter),
        ) {
        Spacer(Modifier.height(spacing.lg))

        // Обложка — квадрат, ширина экрана минус поля. Coil грузит настоящую;
        // пока нет — честно нейтральная плашка.
        net.ripster.mobile.ui.components.Cover(
            url = state.artworkUrl,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = coverMax)
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally)
                .border(1.dp, colors.border_subtle, Radii.CardShape),
            shape = Radii.CardShape,
        )

        Spacer(Modifier.height(spacing.xl))

        BasicText(
            text = state.title,
            maxLines = 1,
            modifier = Modifier.ripsterMarquee(),
            style = TextStyle(
                color = colors.text_primary,
                fontSize = type.display,
                fontWeight = Weights.Primary,
            ),
        )
        Spacer(Modifier.height(spacing.xs))
        BasicText(
            text = "${state.artist} · ${state.album}",
            maxLines = 1,
            modifier = Modifier.ripsterMarquee(MARQUEE_SECOND_LINE_DELAY),
            style = TextStyle(color = colors.text_secondary, fontSize = type.body, fontWeight = Weights.Body),
        )

        Spacer(Modifier.height(spacing.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Техническая строка — моноширинным начертанием системы (Roboto Mono
            // как замена DM Mono из HTML-референса: подключать отдельный шрифт
            // ради одной строки на экран — не тот приоритет сейчас).
            BasicText(
                text = state.format,
                style = TextStyle(
                    color = colors.text_tertiary,
                    fontSize = type.caption,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.width(spacing.md))
            QualityBadge(state = state.quality)
        }

        Spacer(Modifier.height(spacing.lg))

        SeekStrip(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeek = onSeek,
            onScrubChange = onScrubPreview,
            state = if (state.isPlaying) SeekPlaybackState.Playing else SeekPlaybackState.Paused,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(spacing.lg))

        // Главный транспорт: prev / play-pause / next. Play — квадрат,
        // не круг (см. Transport.kt).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportIconButton(onClick = onPrevious, contentDescription = "Previous track") { c ->
                drawPrevGlyph(c)
            }
            PlayPauseButton(isPlaying = state.isPlaying, onClick = onPlayPause)
            TransportIconButton(onClick = onNext, contentDescription = "Next track") { c ->
                drawNextGlyph(c)
            }
        }

        Spacer(Modifier.height(spacing.sm))

        // Второстепенный ряд: перемешать / повтор. Состояние — насыщенностью
        // цвета (active = accent_text), а не заливкой: это переключатель
        // режима воспроизведения, а не отдельное действие с эффектом нажатия
        // такого же веса, как сам транспорт.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TransportIconButton(
                onClick = onToggleShuffle,
                active = state.shuffle,
                contentDescription = "Shuffle",
            ) { c -> drawShuffleGlyph(c) }
            Spacer(Modifier.width(spacing.xl))
            TransportIconButton(
                onClick = onToggleRepeat,
                active = state.repeat,
                contentDescription = "Repeat",
            ) { c -> drawRepeatGlyph(c) }
        }

        Spacer(Modifier.height(spacing.lg))

        RipsterButton(
            text = net.ripster.mobile.ui.i18n.tr("np.dl_album", lang),
            onClick = onDownloadAlbum,
            level = ButtonLevel.Primary,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(spacing.lg))
        } // Column(verticalScroll)
    } // BoxWithConstraints
}
