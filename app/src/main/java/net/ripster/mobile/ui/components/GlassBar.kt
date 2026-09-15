package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Насколько плотна задняя заливка стекла.
 *
 * Было 0.55, и владелец 05.09.2026: «задняя заливка в блюре вообще
 * практически не видна, усиль эффект в пол раза». Полоса читалась как ничто:
 * подсветка сквозь неё просвечивала, а самой плашки видно не было. Подняли в
 * полтора раза — стекло стало стеклом, но осталось полупрозрачным.
 */
const val GLASS_ALPHA = 0.83f

/**
 * Полупрозрачная полоса — верхняя шапка и нижняя строка загрузок.
 *
 * Владелец: «эту плашку убрать вообще, либо сделать прозрачной и более узкой…
 * и кружок скачивания и плашку на которой он тоже слегка прозрачными, чтобы
 * сзади было слегка видно что там, эффект стекла типо».
 *
 * Настоящего размытия здесь нет намеренно. `RenderEffect.createBlurEffect`
 * появился только в Android 12, а нижняя граница приложения — A31 на Android 11
 * (тестовое устройство владельца): на нём размытие просто не нарисуется, и
 * «стекло» превратится в глухую плашку ровно там, где его проверяют. Поэтому
 * стекло собрано из того, что рисуется везде одинаково: слабая заливка тоном
 * холста, вертикальный градиент к прозрачному у внутреннего края и волосяная
 * линия снаружи. Подсветка (ambilight) рисуется под барами от края до края —
 * именно она и просвечивает.
 *
 * [top] — полоса вверху: градиент гаснет книзу, линия снизу. Иначе наоборот.
 */
@Composable
fun Modifier.glassBar(top: Boolean = true, alpha: Float = GLASS_ALPHA): Modifier {
    val c = RipsterTheme.colors
    val base = c.surface_canvas
    val fadeTo = base.copy(alpha = 0f)
    val brush = Brush.verticalGradient(
        if (top) listOf(base.copy(alpha = alpha), fadeTo)
        else listOf(fadeTo, base.copy(alpha = alpha)),
    )
    // Была жёсткая волосяная линия (alpha 0.75) — она резала экран пополам на
    // стыке шапки с контентом (жалоба владельца: «нет плавного перехода»). Вместо
    // одной чёткой линии — мягкая градиентная кромка: узкая полоса тона, гаснущая
    // в прозрачность у внутреннего края, поэтому шапка «перетекает» в контент.
    val edge = c.border_subtle.copy(alpha = 0.32f)
    val edgeH = 6f
    return this
        .background(brush)
        .drawWithContent {
            drawContent()
            val edgeBrush = Brush.verticalGradient(
                if (top) listOf(edge, edge.copy(alpha = 0f)) else listOf(edge.copy(alpha = 0f), edge),
                startY = if (top) size.height - edgeH else 0f,
                endY = if (top) size.height else edgeH,
            )
            val top0 = if (top) size.height - edgeH else 0f
            drawRect(edgeBrush, topLeft = Offset(0f, top0), size = Size(size.width, edgeH))
        }
}

/** Тот же тон для мелких элементов поверх стекла (кружок загрузки). */
@Composable
fun glassTint(alpha: Float = GLASS_ALPHA): Color =
    RipsterTheme.colors.surface_canvas.copy(alpha = alpha)
