package net.ripster.mobile.ui.components

import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
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
/** Сколько полоса держится на экране минимум — чтобы её успели заметить. */
private const val MIN_VISIBLE_MS = 900L

@Composable
fun LoadingBar(active: Boolean, modifier: Modifier = Modifier) {
    // Полосу надо УСПЕТЬ УВИДЕТЬ.
    //
    // Владелец 06.09.2026 дважды сказал «не вижу полосу», хотя пиксельный замер
    // показывал, что она есть. Разгадка во времени: подгрузка радара занимает
    // меньше секунды, полоса вспыхивает и гаснет прежде, чем взгляд дойдёт до
    // верха экрана. Мелькание короче четверти секунды не сообщает ничего —
    // это шум, а не признак.
    //
    // Поэтому включённое состояние удерживается не меньше MIN_VISIBLE_MS. Да,
    // последние доли секунды полоса горит уже после конца работы — это
    // сознательная условность показа, а не утверждение о состоянии: она и
    // означает «только что шла работа», а не «прямо сейчас идёт байт».
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            held = true
        } else {
            kotlinx.coroutines.delay(MIN_VISIBLE_MS)
            held = false
        }
    }
    @Suppress("NAME_SHADOWING") val active = active || held
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
                //
                // Кривая — ТА ЖЕ, что у ПК-полосы, буквально.
                //
                // Я дважды промахнулся здесь. Сначала поставил линейное
                // движение — владелец сказал «не доезжает». Потом взял
                // андроидную FastOutSlowIn «как ease-in-out на ПК» — и он сразу
                // увидел, что это НЕ она: «дёргается с проседанием по скорости
                // до нуля где-то посередине».
                //
                // Так и есть. У CSS `ease-in-out` кривая СИММЕТРИЧНАЯ
                // (0.42, 0, 0.58, 1): разгон и торможение одинаковые, середина
                // быстрая. У FastOutSlowIn (0.4, 0, 0.2, 1) торможение занимает
                // почти весь путь — оно и читается как остановка.
                //
                // Здесь именно CSS-кривая и та же длительность, что в
                // static/css/main.css у #net-progress: 1.1 с.
                animation = tween(1100, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
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

        // Обрезка по краям — ОДНИМ способом на все слои.
        //
        // Раньше каждый слой прижимал своё левое ребро через coerceAtLeast(0),
        // НЕ убавляя ширину. Пока отрезок ещё за левой кромкой, свечение и след
        // рисовались как широкий блок у самого края — и владелец 06.09.2026
        // увидел ровно это: «в самом начале хода она уже стартует с середины,
        // там уже есть что-то… криво получается». Так и было: полоса начинала
        // ход не с пустоты, а с пятна.
        //
        // Здесь левое ребро и ширина считаются вместе: то, что вышло за кромку,
        // из ширины вычитается.
        fun band(left: Float, width: Float, alpha: Float) {
            val l = left.coerceAtLeast(0f)
            val r = (left + width).coerceAtMost(w)
            if (r <= l) return
            drawRect(
                color = PURPLE.copy(alpha = alpha * visible),
                topLeft = Offset(l, 0f), size = Size(r - l, h),
            )
        }

        // СВЕЧЕНИЕ вокруг отрезка — то, чем ПК-полоса и берёт (там это
        // box-shadow). Рисуем несколькими всё более широкими и всё более
        // прозрачными полосами: на семи точках высоты это дешевле и
        // предсказуемее настоящего размытия, которого на Android 11 нет.
        // Сначала широкие и тусклые, поверх — сам отрезок.
        for (k in 3 downTo 1) {
            val grow = segment * 0.18f * k
            band(x - grow, segment + grow * 2f, 0.14f / k)
        }
        // Неоновый след — тот же отрезок, шире и полупрозрачнее.
        band(x - segment * 0.35f, segment * 1.7f, 0.30f)
        // Сам отрезок — сверху, самый яркий.
        band(x, segment, 0.95f)
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
