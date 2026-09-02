package net.ripster.mobile.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * Движение — ОДИН набор рецептов на весь продукт, ровно как радиусы в [Radii].
 *
 * Дорогое приложение отличается от дешёвого не тенями, а тем, КАК всё
 * останавливается: не по таймеру `tween`, а по физике пружины — с массой и
 * трением. Поэтому здесь пружины, а не длительности. Три жёсткости:
 *
 *  · [pressScale] — палец нажал/отпустил. Почти без отскока, очень быстро.
 *  · [standard]   — почти все переходы: смена вкладки, появление плашки.
 *    Едва заметный доводчик в конце.
 *  · [gentle]     — большие поверхности (плеер, лист снизу). Критическое
 *    затухание: такой площади отскок не идёт, он читается как желе.
 *
 * Прозрачность (fade) НЕ пружинят — на альфе пружина видна как рывок. Для
 * кроссфейдов остаются короткие линейные длительности [fadeFast]/[fadeMed].
 */
object Motion {

    val pressScale: SpringSpec<Float> =
        spring(dampingRatio = 0.72f, stiffness = 1400f)

    val standard: SpringSpec<Float> =
        spring(dampingRatio = 0.86f, stiffness = 420f)

    val gentle: SpringSpec<Float> =
        spring(dampingRatio = 1f, stiffness = 240f)

    /** Те же три рецепта для слайдов (переходы возят [IntOffset]). Порог
     *  видимости — в 1 пиксель, дефолт для [IntOffset] в Compose. */
    val standardOffset: SpringSpec<IntOffset> =
        spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = IntOffset(1, 1))

    val gentleOffset: SpringSpec<IntOffset> =
        spring(dampingRatio = 1f, stiffness = 240f, visibilityThreshold = IntOffset(1, 1))

    /** Кроссфейды: коротко и линейно, без физики. */
    const val fadeFast: Int = 130
    const val fadeMed: Int = 190

    /** Период пробега блика по скелету-заглушке обложки. */
    const val shimmerPeriodMs: Int = 1150
}
