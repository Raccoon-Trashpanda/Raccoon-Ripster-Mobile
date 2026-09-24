package net.ripster.mobile.ui.premium

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * «Стекло» в дорогих визуалах: настоящая глубина резкости под плашками.
 *
 * Работает пара: слой-источник ([hazeBackdrop]) рисует то, что лежит ПОД
 * панелью (в AppShell это живая ambilight-заливка фона), а стеклянная панель
 * ([premiumGlass]) размывает этот источник в своих границах. Источник обязан
 * быть СОСЕДОМ панели, а не её родителем: иначе панель попадёт в собственный
 * размываемый слой и блюр закрутится в обратную связь.
 *
 * Всё выключено по умолчанию: вызывающий отдаёт `null` в [premiumGlass], когда
 * режим выкл или телефон старше Android 12 (нет RenderEffect), и тогда
 * [premiumGlass] — это ровно та же полупрозрачная плашка, что рисовалась
 * всегда. Молчаливый отказ, а не глухая серая панель.
 */

/** Радиус настоящего блюра. Ниже 20dp стекло мутнеет в шумы, выше — плывёт. */
private val GLASS_BLUR = 28.dp

/** Слой-источник: то, что стеклянные панели размоют за собой. */
@Composable
fun Modifier.hazeBackdrop(state: HazeState, background: Color): Modifier =
    this.haze(state, background, background.copy(alpha = 0f), GLASS_BLUR, 0f)

/**
 * Стекло поверх [state]: размывает источник в форме [shape] и кладёт сверху
 * полупрозрачный [tint]. `state == null` — модификатор пустой, панель остаётся
 * такой, какой была до режима.
 */
@Composable
fun Modifier.premiumGlass(state: HazeState?, shape: Shape, tint: Color): Modifier =
    if (state == null) this else this.hazeChild(state, shape, HazeStyle(tint, GLASS_BLUR, 0f))
