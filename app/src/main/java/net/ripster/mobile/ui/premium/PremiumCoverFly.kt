package net.ripster.mobile.ui.premium

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import net.ripster.mobile.ui.theme.Motion

/**
 * Перелёт обложки из мини-плашки в полный плеер — «анимированные границы».
 *
 * Настоящие shared elements этот Compose (animation 1.7.3) умеет, но требует
 * [androidx.compose.animation.SharedTransitionLayout] ОБЩИМ родителем и для
 * плашки, и для плеера — то есть для всего экрана Ripster. Это LookaheadScope:
 * двойной обмер макета наступит у всех, включая тех, кто режим «дорогие
 * визуалы» никогда не включал, а молча проверять это на приборе нельзя. Здесь
 * же — один graphicsLayer на обложке, и вне режима он пустой.
 *
 * Плашка отдаёт свой прямоугольник ([MiniCoverRect]) один раз при раскладке,
 * обложка стартует из него и доезжает до своего места по [Motion.gentle] —
 * без отскока, потому что площади большие.
 */

/** Замеренный прямоугольник обложки мини-плеера в координатах корня экрана. */
class MiniCoverRect {
    /**
     * Обычная переменная, не состояние: плеер читает её ОДИН раз при входе, а
     * плашка в этот момент уже уезжает вниз и двигается. Реакция на каждое её
     * изменение заставила бы летящую обложку гнаться за исчезающей плашкой.
     */
    var rect: Rect? = null
}

/** Записать прямоугольник обложки в [holder]. `null` — ничего не замеряем. */
fun Modifier.premiumCoverBounds(holder: MiniCoverRect?): Modifier =
    if (holder == null) this else onGloballyPositioned { holder.rect = it.boundsInRoot() }

/**
 * Доехать обложке до своего места из прямоугольника [from]. `from == null`
 * (режим выключен, пружины запрещены или плашка ещё ни разу не раскладывалась)
 * — модификатор пустой, обложка появляется ровно так же, как появлялась всегда.
 */
@Composable
fun Modifier.premiumCoverEnter(from: Rect?): Modifier {
    if (from == null) return this
    val arrive = remember { Animatable(0f) }
    var own by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(from) { arrive.animateTo(1f, Motion.gentle) }
    return this
        .onGloballyPositioned { own = it.boundsInRoot() }
        .graphicsLayer {
            val p = arrive.value
            if (p >= 1f) return@graphicsLayer
            val o = own
            if (o == null) {
                // Пока не разложились, обложку не показываем: иначе первый кадр
                // она мелькнёт полным размером не на своём месте, и «полёт»
                // превратится в прыжок.
                alpha = 0f
                return@graphicsLayer
            }
            val w = o.width
            if (w <= 0f || from.width <= 0f) return@graphicsLayer
            // Стартуем в размере плашки: масштаб считаем по ширине, обложки
            // квадратные.
            val from0 = (from.width / w).coerceIn(0.05f, 1f)
            val s = from0 + (1f - from0) * p
            scaleX = s
            scaleY = s
            translationX = (from.center.x - o.center.x) * (1f - p)
            translationY = (from.center.y - o.center.y) * (1f - p)
            // Полупрозрачность в начале нужна, чтобы летящая обложка не спорила с
            // той, что ещё видна в плашке; к концу поездки она полная.
            alpha = 0.55f + 0.45f * p
        }
}
