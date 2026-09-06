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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr

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
    // Подписи для скринридера тоже на языке приложения.
    val lang = LocalAppLang.current
    // Системные панели тоже прячем: после того как слой перекрыл шапку и
    // навигацию приложения, кнопки Android остались единственной светлой
    // полосой на чёрном. Скрываем только на время режима и возвращаем как было
    // — состояние берём у окна, а не задаём своё, чтобы не переопределить
    // настройку системы. Панели возвращаются свайпом от края, жест остаётся.
    // Через QUIET_MS без касаний кнопки гаснут до CONTROLS_DIM. Обложка
    // яркости НЕ меняет: ради неё этот режим и открывают, а управление здесь
    // гость — нужное ровно в тот момент, когда до него тянутся.
    //
    // Гаснут, а не исчезают. Пропавшая кнопка заставляет гадать, куда жать, и
    // это уже не «не мешает», а «сломалось»; приглушённую видно, и нажатие она
    // принимает как обычно.
    var lastTouchMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var quiet by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouchMs) {
        quiet = false
        kotlinx.coroutines.delay(QUIET_MS)
        quiet = true
    }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (quiet) CONTROLS_DIM else 1f,
        // Оба движения плавные, но разной длины и с разным характером.
        //
        // Угасание — длинное и с мягким началом: если кнопки просто «выключить»
        // за долю секунды, глаз ловит момент, и вместо «перестало мешать»
        // получается «что-то мигнуло». Возврат короче, но тоже со сглаживанием:
        // резкая вспышка на чёрном экране бьёт по глазам сильнее, чем помогает.
        animationSpec = tween(
            durationMillis = if (quiet) FADE_OUT_MS else FADE_IN_MS,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "coverstage-controls",
    )

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
            // Initial-проход: узнаём о касании РАНЬШЕ детей и ничего не
            // потребляем. Иначе пробуждение экрана съедало бы само нажатие, и
            // по кнопке пришлось бы попадать дважды.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouchMs = System.currentTimeMillis()
                    }
                }
            }
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
                    Modifier.alpha(controlsAlpha),
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportIconButton(
                        onClick = onPrevious,
                        contentDescription = tr("a11y.prev", lang),
                        iconSize = 24.dp,
                    ) { drawPrevGlyph(Color.White) }
                    PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause)
                    TransportIconButton(
                        onClick = onNext,
                        contentDescription = tr("a11y.next", lang),
                        iconSize = 24.dp,
                    ) { drawNextGlyph(Color.White) }
                }
            }
        }
    }
}

/** Сколько экран ждёт без касаний, прежде чем приглушить кнопки. */
private const val QUIET_MS = 5_000L

/** Длительность угасания и возврата. Подобрано на глаз на живом экране:
 *  короче — заметен сам момент переключения, длиннее — кажется, что экран
 *  тормозит. */
private const val FADE_OUT_MS = 2200
private const val FADE_IN_MS = 420

/** До какой доли яркости они гаснут: на 70% менее заметны, но видны. */
private const val CONTROLS_DIM = 0.30f

/** Высота, зарезервированная под ряд кнопок под обложкой. */
private val CONTROLS_BAND = 112.dp
