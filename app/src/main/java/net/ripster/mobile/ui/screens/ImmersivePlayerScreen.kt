package net.ripster.mobile.ui.screens

import net.ripster.mobile.ui.components.MARQUEE_SECOND_LINE_DELAY
import net.ripster.mobile.ui.components.ripsterMarquee
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.components.Cover
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
    var dragAcc by remember { mutableStateOf(0f) }

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
        // обложка — на весь экран
        Cover(
            url = state.artworkUrl,
            modifier = Modifier.fillMaxSize(),
        )
        // затемнение краёв + низа под текст
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.34f),
                    0.42f to Color.Transparent,
                    0.66f to Color.Black.copy(alpha = 0.30f),
                    1f to Color.Black.copy(alpha = 0.90f),
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
                BasicText(
                    state.format,
                    style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // тонкая полоса позиции, тап по ней = перемотка
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
                    Canvas(Modifier.size(24.dp)) {
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
                    // прозрачная кнопка поверх
                    Box(Modifier.fillMaxSize().clip(CircleShape).pointerInput(Unit) {
                        detectTapGestures(onTap = { onPlayPause() })
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
        }
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
