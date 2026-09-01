package net.ripster.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.Radii
import net.ripster.mobile.ui.theme.RipsterTheme

/*
 * ============================================================================
 *  ТРАНСПОРТ. Одна главная кнопка — не кружок.
 * ============================================================================
 *
 * Play/pause — квадрат со скруглением RCard (та же форма, что у обложки и
 * карточек), НЕ circle. Причина ровно та, что уже записана в дизайн-хэндоффе
 * (design/ … BRIEF_mobile_player.md, README андроид-пакета): круглая кнопка —
 * самый частый, самый нейтральный знак в приложении-конкуренте и в вебе;
 * квадрат с мягким углом здесь — узнаваемая, ничья больше форма транспорта.
 * Всё, что КРУГЛОЕ в этом продукте (см. Radii.kt, SeekStrip) — не кнопка, а
 * позиция/статус: ручка перемотки, точка очереди, радио-кружок настройки.
 * Смешивать роли нельзя — тогда форма перестаёт быть сигналом.
 *
 * Иконки нарисованы Canvas-примитивами, тем же приёмом, что CheckGlyph и
 * ArrowGlyph в Badges.kt — без внешней иконочной библиотеки. Не Lucide-SVG
 * из HTML-референса: тот набор для веба, а порт растрового/векторного ассета
 * один-в-один добавил бы зависимость там, где хватает трёх линий.
 */

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp,
) {
    val colors = RipsterTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
            .background(
                if (pressed) colors.accent_active else colors.accent_fill,
                Radii.CardShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.4f)) {
            if (isPlaying) drawPauseGlyph(colors.text_on_fill) else drawPlayGlyph(colors.text_on_fill)
        }
    }
}

/**
 * Второстепенный транспорт: prev/next/shuffle/repeat. Мишень всегда ≥48dp
 * ([MinTouchTarget]), а вот РИСУНОК меняется по роли: prev/next — часть
 * главного транспорта, их видно наравне с play (крупнее); shuffle/repeat —
 * тихие переключатели режима, они мельче. Размер иконки — параметр, а не
 * константа, чтобы один компонент закрывал обе роли без развилки.
 */
@Composable
fun TransportIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
    contentDescription: String,
    draw: DrawScope.(color: Color) -> Unit,
) {
    val colors = RipsterTheme.colors
    val color = if (active) colors.accent_text else colors.text_primary
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(iconSize)) { draw(color) }
    }
}

private fun DrawScope.drawPlayGlyph(color: Color) {
    val w = size.width
    val h = size.height
    // Треугольник со скруглёнными углами через путь недоступен без Path API
    // здесь избыточен — три линии со скруглённым концом дают тот же силуэт
    // без лишней зависимости от androidx.graphics.path.
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.16f, h * 0.06f)
        lineTo(w * 0.92f, h * 0.5f)
        lineTo(w * 0.16f, h * 0.94f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawPauseGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val barWidth = w * 0.28f
    val gap = w * 0.18f
    val strokeCap = StrokeCap.Round
    drawLine(color, Offset(w / 2f - gap / 2f - barWidth / 2f, 0f),
        Offset(w / 2f - gap / 2f - barWidth / 2f, h), barWidth, strokeCap)
    drawLine(color, Offset(w / 2f + gap / 2f + barWidth / 2f, 0f),
        Offset(w / 2f + gap / 2f + barWidth / 2f, h), barWidth, strokeCap)
}

fun DrawScope.drawPrevGlyph(color: Color) {
    val w = size.width; val h = size.height
    drawLine(color, Offset(w * 0.22f, h * 0.1f), Offset(w * 0.22f, h * 0.9f), w * 0.11f, StrokeCap.Round)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.82f, h * 0.08f); lineTo(w * 0.30f, h * 0.5f); lineTo(w * 0.82f, h * 0.92f); close()
    }
    drawPath(path, color)
}

fun DrawScope.drawNextGlyph(color: Color) {
    val w = size.width; val h = size.height
    drawLine(color, Offset(w * 0.78f, h * 0.1f), Offset(w * 0.78f, h * 0.9f), w * 0.11f, StrokeCap.Round)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.18f, h * 0.08f); lineTo(w * 0.70f, h * 0.5f); lineTo(w * 0.18f, h * 0.92f); close()
    }
    drawPath(path, color)
}

fun DrawScope.drawShuffleGlyph(color: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.06f, h * 0.22f), Offset(w * 0.34f, h * 0.22f), stroke.width, stroke.cap)
    drawLine(color, Offset(w * 0.06f, h * 0.78f), Offset(w * 0.34f, h * 0.78f), stroke.width, stroke.cap)
    drawLine(color, Offset(w * 0.30f, h * 0.22f), Offset(w * 0.94f, h * 0.78f), stroke.width, stroke.cap)
    drawLine(color, Offset(w * 0.30f, h * 0.78f), Offset(w * 0.94f, h * 0.22f), stroke.width, stroke.cap)
    // хвостики стрелок на правом конце
    drawLine(color, Offset(w * 0.94f, h * 0.78f), Offset(w * 0.76f, h * 0.78f), stroke.width, stroke.cap)
    drawLine(color, Offset(w * 0.94f, h * 0.78f), Offset(w * 0.94f, h * 0.60f), stroke.width, stroke.cap)
    drawLine(color, Offset(w * 0.94f, h * 0.22f), Offset(w * 0.76f, h * 0.22f), stroke.width, stroke.cap)
    drawLine(color, Offset(w * 0.94f, h * 0.22f), Offset(w * 0.94f, h * 0.40f), stroke.width, stroke.cap)
}

fun DrawScope.drawRepeatGlyph(color: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    drawArc(color, 90f, 180f, useCenter = false,
        topLeft = Offset(w * 0.06f, h * 0.06f), size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.5f),
        style = stroke)
    drawArc(color, 270f, 180f, useCenter = false,
        topLeft = Offset(w * 0.06f, h * 0.44f), size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.5f),
        style = stroke)
    drawLine(color, Offset(w * 0.06f, h * 0.31f), Offset(w * 0.20f, h * 0.14f), stroke.width, stroke.cap)
    drawLine(color, Offset(w * 0.06f, h * 0.31f), Offset(w * 0.22f, h * 0.36f), stroke.width, stroke.cap)
}
