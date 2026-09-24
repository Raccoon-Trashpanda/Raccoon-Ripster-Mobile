package net.ripster.mobile.ui.premium

import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Доезд звучащей строки текста до центра по пружине.
 *
 * [androidx.compose.foundation.lazy.animateScrollToItem] возит список своей
 * длительностью и спецификации не спрашивает: у него tween, а у текста, который
 * идёт слово за словом, tween читается как «догоняет», потому что каждая
 * следующая строка стартует с нуля по таймеру. Пружина такого дефекта не имеет:
 * она всегда продолжает текущую скорость, и строка плавно перетекает из одного
 * положения в другое.
 *
 * Возвращает `false`, если пружинить нечем — строка не видна (трек сменился,
 * человек уехал прокруткой далеко, был seek): тогда вызывающий едет как ехал,
 * `animateScrollToItem`. `springy == false` — тот же тихий отказ, движение
 * этому телефону не разрешено.
 */
suspend fun LazyListState.springScrollToLine(
    index: Int,
    spec: SpringSpec<Float>,
    springy: Boolean,
): Boolean {
    if (!springy) return false
    val info = layoutInfo
    val line = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    // Центр вьюпорта минус половина строки — то же место, куда кладёт строку
    // animateScrollToItem с offset = -(vp/2 - h/2), так что два пути сходятся
    // в одну точку и не спорят на соседних строках.
    val rest = (info.viewportSize.height - line.size) / 2
    val total = (line.offset - rest).toFloat()
    if (abs(total) < 1f) return true
    // Везёт сама система, кадр за кадром; спецификацию берёт нашу. Положительная
    // дельта — это «контент едет вверх», то есть offset строки уменьшается, ровно
    // как мы посчитали.
    animateScrollBy(total, spec)
    return true
}

/**
 * Размытие строки текста, уехавшей из фокуса. `0f` — модификатор пустой: и когда
 * глубина запрещена планом ([PremiumPlan.lyricsDepth]), и для звучащей строки,
 * которая резкой обязана остаться.
 *
 * Вызов с ненулевым радиусом возможен только на Android 12+: ниже нет
 * RenderEffect, и там [blur] — молчаливое ничего, а не серый квадрат.
 */
fun Modifier.premiumLineBlur(radiusDp: Float): Modifier =
    if (radiusDp <= 0f) this else blur(radiusDp.dp)
