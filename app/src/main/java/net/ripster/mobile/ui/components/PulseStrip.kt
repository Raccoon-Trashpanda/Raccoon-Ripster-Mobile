package net.ripster.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Полоса загрузки в шапке: фиолетовый отрезок, быстро бегущий слева направо.
 *
 * Владелец 05.09.2026, дословно: «фиолетовая полоска которая быстро бегает
 * слева направо, в моменты когда идёт загрузка или подгрузка, а эту жёлтую
 * убирай». Первая версия была кардиограммой с тремя состояниями и жёлтым
 * «ожиданием» — не то, что просили, и жёлтый убран целиком.
 *
 * Смысл ровно один: ИДЁТ РАБОТА. Не «связь есть», не «всё хорошо» — только
 * загрузка или подгрузка прямо сейчас. Поэтому состояний два, а не три:
 * бежит либо нет. Промежуточных значений и процентов здесь нет намеренно —
 * заранее известной доли у большинства наших загрузок не бывает, а
 * нарисованный процент, которого никто не считал, это враньё.
 *
 * Когда работы нет, полоса ГАСНЕТ, а не замирает: остановившийся отрезок
 * читался бы как «зависло».
 */
@Composable
fun LoadingBar(active: Boolean, modifier: Modifier = Modifier) {
    // Плавное появление и угасание: мгновенный скачок читается как сбой
    // отрисовки, а не как смена состояния.
    val visible by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(280), label = "loading-visible",
    )
    val shift = if (!active) 0f else {
        val t = rememberInfiniteTransition(label = "loading-run")
        val p by t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // Быстро — как и просили. Полный проход меньше секунды.
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "loading-shift",
        )
        p
    }

    // Высота. Было четыре точки — владелец 06.09.2026: «не вижу полосу».
    // На плотном экране четыре точки под системной строкой состояния глазом не
    // ловятся, тем более через удалённый рабочий стол. Семь — уже видно, и это
    // всё ещё полоска, а не панель.
    Canvas(modifier.fillMaxWidth().height(7.dp)) {
        if (visible <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height
        // Тусклая дорожка во всю ширину — чтобы полоса читалась как полоса, а
        // не как случайное пятно, и чтобы было видно, где отрезок пробежит.
        drawRect(
            color = PURPLE.copy(alpha = 0.16f * visible),
            topLeft = Offset(0f, 0f), size = Size(w, h),
        )
        // Бегущий отрезок. Сплошной, а не градиентный: градиент на четырёх
        // точках по высоте съедает сам себя и полосы попросту не видно —
        // проверено на эмуляторе, первая версия не рисовалась вовсе.
        val segment = w * 0.30f
        val x = -segment + (w + segment) * shift
        drawRect(
            color = PURPLE.copy(alpha = 0.95f * visible),
            topLeft = Offset(x.coerceAtLeast(0f), 0f),
            size = Size(
                (segment + minOf(0f, x)).coerceIn(0f, w - x.coerceAtLeast(0f)),
                h,
            ),
        )
        // СВЕЧЕНИЕ вокруг отрезка — то, чем ПК-полоса и берёт (там это
        // box-shadow вокруг бегущего отрезка). Без него полоса на тёмной шапке
        // сливается с фоном: сама она есть, а глаз её не находит.
        //
        // Рисуем несколькими всё более широкими и всё более прозрачными
        // прямоугольниками: на семи точках высоты это дешевле и предсказуемее
        // настоящего размытия, которого на Android 11 (A31) всё равно нет.
        for (i in 3 downTo 1) {
            val grow = segment * 0.18f * i
            val gx = (x - grow).coerceAtLeast(0f)
            drawRect(
                color = PURPLE.copy(alpha = (0.14f / i) * visible),
                topLeft = Offset(gx, 0f),
                size = Size((segment + grow * 2f).coerceAtMost(w - gx), h),
            )
        }
        // Неоновый след: тот же отрезок, шире и полупрозрачнее.
        drawRect(
            color = PURPLE.copy(alpha = 0.30f * visible),
            topLeft = Offset((x - segment * 0.35f).coerceAtLeast(0f), 0f),
            size = Size(
                (segment * 1.7f).coerceAtMost(w - (x - segment * 0.35f).coerceAtLeast(0f)),
                h,
            ),
        )
    }
}

/**
 * Фиолетовый именно этой полосы.
 *
 * Не берётся из палитры темы: акцент приложения — розовый, и полоса на нём
 * сливалась бы с кнопкой воспроизведения. Владелец просил фиолетовую, и это
 * отдельный смысл — «идёт работа», а не «это можно нажать».
 */
private val PURPLE = Color(0xFF8B5CF6)
