package net.ripster.mobile.ui.components

import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Обложка во весь экран на чистом чёрном — «OLED-режим». Открывается тапом по
 * обложке в плеере. Просьба владельца 04.09.2026.
 *
 * Рисуется НЕ внутри экрана плеера, а самым верхним слоем приложения: иначе
 * шапка с логотипом и нижняя навигация остаются на своих местах и обрамляют
 * картинку двумя серыми полосами — ровно то, ради чего чёрный и выбирался, при
 * этом пропадает. «Чёрный везде» значит везде.
 *
 * Кроме обложки — только перемотка по трекам и пауза. Перемотка внутри трека,
 * чипы действий и вердикт качества остаются на основном экране: этот режим
 * существует ради картинки, и каждый лишний элемент отнимает у неё место.
 *
 * Цвет фона задан литералом, а не взят из темы, намеренно: режим обязан быть
 * чёрным при любой выбранной теме, включая светлую. На OLED-матрице такой
 * пиксель не светится, и обложка висит в пустоте, а не на сером прямоугольнике.
 */
@Composable
fun CoverStage(
    artworkUrl: String?,
    fallbackModel: Any?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Системные панели тоже прячем: после того как слой перекрыл шапку и
    // навигацию приложения, кнопки Android остались единственной светлой
    // полосой на чёрном. Скрываем только на время режима и возвращаем как было
    // — состояние берём у окна, а не задаём своё, чтобы не переопределить
    // настройку системы. Панели возвращаются свайпом от края, жест остаётся.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (prevBehavior != null) controller?.systemBarsBehavior = prevBehavior
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Тап мимо обложки закрывает. Без ripple: вспышка светлого пятна на
            // чёрном фоне — единственное, что здесь может испортить картинку.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            // Под кнопки резервируем полосу, всё остальное отдаём обложке —
            // квадрат по меньшей из оставшихся сторон.
            val side = minOf(maxWidth, maxHeight - CONTROLS_BAND)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Cover(
                    url = artworkUrl,
                    modifier = Modifier.size(side),
                    shape = RoundedCornerShape(14.dp),
                    fallbackModel = fallbackModel,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportIconButton(
                        onClick = onPrevious,
                        contentDescription = "Предыдущий",
                        iconSize = 24.dp,
                    ) { drawPrevGlyph(Color.White) }
                    PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause)
                    TransportIconButton(
                        onClick = onNext,
                        contentDescription = "Следующий",
                        iconSize = 24.dp,
                    ) { drawNextGlyph(Color.White) }
                }
            }
        }
    }
}

/** Высота, зарезервированная под ряд кнопок под обложкой. */
private val CONTROLS_BAND = 112.dp
