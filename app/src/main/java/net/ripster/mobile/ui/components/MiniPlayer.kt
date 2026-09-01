package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Мини-плеер — компактная плашка "сейчас играет", закреплённая над нижней
 * навигацией на всех экранах, кроме самого полноэкранного Now Playing.
 * Показывает обложку, название/исполнителя, кнопку play/pause и тонкую
 * полосу прогресса. Тап по плашке (кроме самой кнопки play/pause) раскрывает
 * NowPlayingScreen.
 *
 * Как и остальной управляющий слой — BasicText, не Material Text (в проекте
 * нет зависимости на androidx.compose.material вообще, только foundation/ui);
 * indication = null на кликабельной строке — тот же приём, что у
 * TransportIconButton в Transport.kt, не внешний ripple.
 *
 * Прогресс-бар размещён у самого нижнего края компонента (а не над hairline-
 * разделителем): так он читается как прогресс воспроизведения текущего трека,
 * а не как «хвост» контента, который скроллится позади плеера.
 */
data class MiniPlayerState(
    val title: String,
    val artist: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val artworkUrl: String? = null,
)

@Composable
fun MiniPlayer(
    state: MiniPlayerState,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    // skin_2: плавающая скруглённая плашка, а не полоса на всю ширину с hairline.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface_raised)
            .border(1.dp, colors.border_subtle, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onExpand,
                    )
                    .semantics { contentDescription = "${state.title} — ${state.artist}, открыть плеер" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(
                    url = state.artworkUrl,
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(9.dp),
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = colors.text_primary, fontSize = type.body),
                    )
                    BasicText(
                        text = state.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = colors.text_secondary, fontSize = type.caption),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            PlayPauseButton(
                isPlaying = state.isPlaying,
                onClick = onPlayPause,
                size = 36.dp,
            )
        }

        // Тонкая полоса прогресса у нижнего края плашки (внутри скругления).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.surface_sunken),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = progress)
                    .height(2.dp)
                    .background(colors.accent_fill),
            )
        }
    }
}
