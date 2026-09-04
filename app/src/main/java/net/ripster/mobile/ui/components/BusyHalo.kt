package net.ripster.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Тонкий световой ореол, бегущий по контуру плитки по часовой стрелке.
 *
 * Зачем. Жалоба владельца 04.09.2026 (e79) про ▶ на карточке поиска: «нет звука,
 * не выезжает мини-плеер, непонятно что происходит». Отклик на самом деле был —
 * строка «начинаю…», — но она рисуется НАД списком выдачи, а ▶ жмут на карточке
 * в середине: сообщение появлялось за пределами экрана. Обратная связь должна
 * быть там, где палец, иначе её всё равно что нет.
 *
 * Почему бегущий контур, а не спиннер поверх обложки: подбор потока — ожидание
 * неизвестной длины (сначала свой сервис, потом поиск ТОЙ ЖЕ записи у других), и
 * бегущая линия честно говорит «идёт», ничего не обещая про долю выполненного.
 * Обложку она при этом не закрывает.
 *
 * Реализация — отрезок пути, а не градиент по кругу. Первая версия рисовала
 * `sweepGradient` со сдвигом стопов: чтобы стопы шли по возрастанию, их
 * приходилось сортировать, отчего яркая дуга на стыке разрывалась и
 * размазывалась в общее свечение вместо точки. `PathMeasure.getSegment` даёт
 * ровно то, что просили: короткий кусок контура, едущий по периметру.
 *
 * Уважает системное «убрать анимацию»: при выключенных анимациях контур просто
 * ровно светится — сигнал «идёт» остаётся, движение уходит.
 */
@Composable
fun Modifier.busyHalo(
    active: Boolean,
    color: Color,
    corner: Dp = 8.dp,
    width: Dp = 2.dp,
    periodMs: Int = 1100,
    /** Доля периметра, занятая светящимся отрезком. */
    arcFraction: Float = 0.18f,
): Modifier {
    if (!active) return this

    val animated = animationsEnabled()
    val progress = if (!animated) 0f else {
        val t = rememberInfiniteTransition(label = "halo")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "halo-progress",
        ).value
    }
    // Путь и измеритель переживают кадры: пересоздавать их шестьдесят раз в
    // секунду незачем, содержимое всё равно перезаписывается.
    val outline = remember { Path() }
    val segment = remember { Path() }
    val measure = remember { PathMeasure() }

    return this.drawWithContent {
        drawContent()
        val w = width.toPx()
        val inset = w / 2f
        outline.reset()
        outline.addRoundRect(
            RoundRect(
                left = inset, top = inset,
                right = size.width - inset, bottom = size.height - inset,
                cornerRadius = CornerRadius(corner.toPx()),
            ),
        )

        if (!animated) {
            drawPath(outline, color = color.copy(alpha = 0.5f), style = Stroke(width = w))
            return@drawWithContent
        }

        // Тусклый контур целиком — чтобы плитка читалась как «занятая» даже в
        // тот миг, когда светлый отрезок на другой её стороне.
        drawPath(outline, color = color.copy(alpha = 0.16f), style = Stroke(width = w))

        measure.setPath(outline, forceClosed = true)
        val len = measure.length
        if (len <= 0f) return@drawWithContent
        val arc = len * arcFraction
        val start = (progress % 1f) * len

        // Хвост рисуем несколькими кусочками с нарастающей яркостью: получается
        // затухание позади светящейся головы, без градиентных кистей.
        val steps = 6
        for (i in 0 until steps) {
            val a0 = start + arc * i / steps
            val a1 = start + arc * (i + 1) / steps
            val alpha = 0.12f + 0.78f * ((i + 1f) / steps)
            segment.reset()
            // getSegment не умеет через «конец пути» — режем на две части.
            if (a1 <= len) {
                measure.getSegment(a0, a1, segment, startWithMoveTo = true)
            } else if (a0 >= len) {
                measure.getSegment(a0 - len, a1 - len, segment, startWithMoveTo = true)
            } else {
                measure.getSegment(a0, len, segment, startWithMoveTo = true)
                measure.getSegment(0f, a1 - len, segment, startWithMoveTo = true)
            }
            drawPath(
                segment,
                color = color.copy(alpha = alpha),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Включены ли анимации у системы.
 *
 * `ANIMATOR_DURATION_SCALE` = 0 значит «пользователь выключил анимации» — часто
 * это не вкус, а укачивание или слабое устройство.
 */
@Composable
private fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) != 0f
        }.getOrDefault(true)
    }
}
