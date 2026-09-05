package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Мягкость и объём — одним слоем на всё приложение.
 *
 * Владелец 05.09.2026: «внедряешь мягкость, объёмность интерфейса». Соблазн —
 * пройтись по экранам и добавить теней где придётся; так получается разнобой,
 * который потом никто не сведёт. Поэтому объём здесь ОДИН и описан в одном
 * месте, а экраны его применяют.
 *
 * Из чего он собран и почему именно так:
 *
 *  · Тень рисуется САМИ, а не через `Modifier.shadow`. Системная тень на
 *    Android — чёрная и жёсткая, на тёмном фоне она превращает карточку в
 *    дыру. Здесь это мягкое пятно под нижним краем, набранное несколькими
 *    полупрозрачными слоями: на тёмной теме такое читается как приподнятость,
 *    а не как провал.
 *
 *  · Верхняя кромка светлее нижней. Это и есть весь объём: свет падает
 *    сверху, поэтому верхний край ловит его, а нижний уходит в тень. Один
 *    градиент, без рамок и обводок — рамка спорит с тенью и делает вид
 *    плоским.
 *
 *  · Скругление крупное и одинаковое. Мягкость — это в первую очередь радиус,
 *    и разные радиусы на соседних карточках читаются как небрежность.
 *
 * Чего здесь НЕТ намеренно: размытия. `RenderEffect` появился в Android 12, а
 * нижняя граница приложения — A31 на Android 11, и на нём эффект просто не
 * нарисуется. Объём, который есть не на всех устройствах, хуже честной
 * плоскости.
 */
object Depth {
    /** Крупное скругление — основной носитель «мягкости». */
    val Radius: Dp = 18.dp

    /** Насколько заметен подъём. Больше — выше «висит» карточка. */
    val Lift: Dp = 8.dp
}

/**
 * Приподнятая мягкая поверхность.
 *
 * @param radius скругление; по умолчанию общее для приложения.
 * @param lift насколько высоко карточка «висит» — влияет только на тень.
 * @param tint своя заливка; по умолчанию цвет приподнятой поверхности темы.
 */
@Composable
fun Modifier.softSurface(
    radius: Dp = Depth.Radius,
    lift: Dp = Depth.Lift,
    tint: Color? = null,
): Modifier {
    val c = RipsterTheme.colors
    val base = tint ?: c.surface_raised
    val shape = RoundedCornerShape(radius)
    return this
        .drawBehind {
            // Тень под нижним краем: три слоя с растущим смещением и падающей
            // непрозрачностью. Дёшево, рисуется везде одинаково и не требует
            // ни фильтров, ни отдельного слоя композиции.
            val r = radius.toPx()
            val steps = 3
            for (i in 1..steps) {
                val k = i / steps.toFloat()
                val off = lift.toPx() * k
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.16f * (1f - k) + 0.05f),
                    topLeft = Offset(off * 0.35f, off),
                    size = Size(size.width - off * 0.7f, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                )
            }
        }
        .clip(shape)
        .background(base)
        // Свет сверху. Разница между кромками маленькая нарочно: заметный
        // градиент на карточке читается как кнопка, а не как поверхность.
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
            ),
        )
}
