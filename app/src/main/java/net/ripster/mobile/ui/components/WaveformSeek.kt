package net.ripster.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

/**
 * Волновой сик-бар: столбики реальной амплитуды трека (пики из [Waveform]).
 * Спетая часть — цветом [tint], будущая — тускло; тап/протяжка перематывает.
 *
 * Отдельный компонент, а НЕ правка [SeekStrip]: волна и линейная полоса — разные
 * визуальные языки, и полированную полосу с её анимацией/пунктиром/доступностью
 * ломать ради этого не нужно. Здесь своя, простая и корректная механика.
 */
@androidx.compose.runtime.Composable
fun WaveformSeek(
    peaks: FloatArray,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrubChange: (Long) -> Unit = {},
    tint: Color = Color(0xFFB980FF),
    idle: Color = Color.White.copy(alpha = 0.22f),
    contentDescription: String = "Seek position",
) {
    val enabled = durationMs > 0L
    val realFraction = if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var scrub by remember { mutableFloatStateOf(-1f) }
    val headFraction = if (scrub >= 0f) scrub else realFraction

    fun emitSeek(f: Float) = onSeek((f.coerceIn(0f, 1f) * durationMs).roundToLong())

    Canvas(
        modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(durationMs) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> emitSeek(o.x / size.width) }
            }
            .pointerInput(durationMs) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f); onScrubChange((scrub * durationMs).roundToLong()) },
                    onDragEnd = { if (scrub >= 0f) { emitSeek(scrub); scrub = -1f } },
                    onDragCancel = { scrub = -1f },
                ) { change, _ ->
                    scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                    onScrubChange((scrub * durationMs).roundToLong())
                }
            },
    ) {
        val n = peaks.size
        if (n == 0) return@Canvas
        val w = size.width; val h = size.height
        val slot = w / n
        val barW = (slot * 0.62f).coerceAtLeast(1f)
        val gap = (slot - barW) / 2f
        val minH = h * 0.06f
        val cr = CornerRadius(barW / 2f, barW / 2f)
        val headX = headFraction * w
        for (i in 0 until n) {
            val peak = peaks[i].coerceIn(0f, 1f)
            val barH = (minH + peak * (h - minH)).coerceAtMost(h)
            val x = i * slot + gap
            val y = (h - barH) / 2f
            val centerX = x + barW / 2f
            val color = if (centerX <= headX) tint else idle
            drawRoundRect(color, topLeft = Offset(x, y), size = Size(barW, barH), cornerRadius = cr)
        }
        // Тонкая головка на текущей позиции.
        drawRoundRect(
            Color.White,
            topLeft = Offset((headX - 1.2.dp.toPx()).coerceIn(0f, w - 2.4.dp.toPx()), 0f),
            size = Size(2.4.dp.toPx(), h),
            cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx()),
        )
    }
}
