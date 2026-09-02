package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Мини-плеер — компактная плашка "сейчас играет", закреплённая над нижней
 * навигацией на всех экранах, кроме полноэкранного плеера.
 *
 * Органы управления как в ПК-версии, но только основные: назад / пауза /
 * вперёд / закрыть (×). Тап по обложке+тексту раскрывает полный плеер.
 * Название и исполнитель, если не влезают, плавно проматываются
 * (`basicMarquee`) — с паузой в начале, чтобы успеть прочитать.
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
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipsterTheme.colors
    val type = RipsterTheme.type

    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

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
                .padding(start = 10.dp, end = 4.dp)
                .height(58.dp),
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
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = state.title,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1400,
                            repeatDelayMillis = 1600,
                        ),
                        style = TextStyle(color = colors.text_primary, fontSize = type.body),
                    )
                    BasicText(
                        text = state.artist,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1800,
                            repeatDelayMillis = 1600,
                        ),
                        style = TextStyle(color = colors.text_secondary, fontSize = type.caption),
                    )
                }
            }

            Spacer(Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MiniGlyph(onClick = onPrev, cd = "Предыдущий") { drawPrevGlyph(colors.text_secondary) }
                PlayPauseButton(isPlaying = state.isPlaying, onClick = onPlayPause, size = 34.dp)
                MiniGlyph(onClick = onNext, cd = "Следующий") { drawNextGlyph(colors.text_secondary) }
                MiniGlyph(onClick = onClose, cd = "Закрыть плеер", icon = 12.dp) {
                    drawLine(colors.text_tertiary, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.15f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.85f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(colors.text_tertiary, androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.15f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.85f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.surface_sunken),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction = progress).height(2.dp).background(colors.accent_fill),
            )
        }
    }
}

@Composable
private fun MiniGlyph(
    onClick: () -> Unit,
    cd: String,
    icon: androidx.compose.ui.unit.Dp = 16.dp,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(icon)) { draw() }
    }
}
