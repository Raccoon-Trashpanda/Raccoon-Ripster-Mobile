package net.ripster.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
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
    contentDescription: String? = null,
) {
    val lang = LocalAppLang.current
    val seekCd = contentDescription ?: tr("a11y.seek_position", lang)
    val enabled = durationMs > 0L
    val realFraction = if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var scrub by remember { mutableFloatStateOf(-1f) }
    val headFraction = if (scrub >= 0f) scrub else realFraction

    // Появление после сканирования: столбики распускаются слева-направо из тонкой
    // линии в полную высоту + плавный fade. Не сбивает с толку — читается как
    // «волна проявилась», а не мигание.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reveal by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = LinearEasing),
        label = "wave-reveal",
    )

    fun emitSeek(f: Float) = onSeek((f.coerceIn(0f, 1f) * durationMs).roundToLong())

    Canvas(
        modifier
            .semantics { this.contentDescription = seekCd }
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
        // Сдвиг фазы по бару даёт «пробегающий» расцвет слева-направо.
        val stagger = 0.35f
        for (i in 0 until n) {
            // Локальное появление этого столбика (0..1) с учётом бегущей фазы.
            val local = (((reveal - (i.toFloat() / n) * stagger) / (1f - stagger))).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val peak = peaks[i].coerceIn(0f, 1f)
            val barH = (minH + peak * (h - minH) * local).coerceAtMost(h)
            val x = i * slot + gap
            val y = (h - barH) / 2f
            val centerX = x + barW / 2f
            val color = if (centerX <= headX) tint else idle
            drawRoundRect(color.copy(alpha = color.alpha * local), topLeft = Offset(x, y), size = Size(barW, barH), cornerRadius = cr)
        }
        // Тонкая головка на текущей позиции (появляется вместе с волной).
        drawRoundRect(
            Color.White.copy(alpha = reveal),
            topLeft = Offset((headX - 1.2.dp.toPx()).coerceIn(0f, w - 2.4.dp.toPx()), 0f),
            size = Size(2.4.dp.toPx(), h),
            cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx()),
        )
    }
}
