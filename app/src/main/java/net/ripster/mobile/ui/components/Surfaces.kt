package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.theme.BorderWidth
import net.ripster.mobile.ui.theme.Radii
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Карточка.
 *
 * БЕЗ ТЕНИ. Карточка отделяется от полотна двумя вещами: своей поверхностью
 * (surface_raised поверх surface_canvas) и волосяной границей. Тень здесь была бы
 * третьим, лишним кодированием одного и того же — а в вебе именно так и вышло:
 * 81 рецепт тени, поднятыми оказались все элементы сразу, то есть никакой.
 *
 * border_subtle нужен даже там, где поверхности различимы: в Midnight карточка
 * (0C0C0F) на полотне (000000) различается на грани видимости, и граница —
 * единственное, что удерживает край.
 */
@Composable
fun RipsterCard(
    modifier: Modifier = Modifier,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    Column(
        modifier = modifier
            .clip(Radii.CardShape)
            .background(colors.surface_raised, Radii.CardShape)
            .border(BorderWidth, colors.border_subtle, Radii.CardShape)
            .then(if (padded) Modifier.padding(spacing.cardPadding) else Modifier),
        content = content,
    )
}

/**
 * Разделитель-волосок.
 *
 * Ровно один физический пиксель, а не 1.dp: на экране с плотностью 3 «1.dp»
 * превращается в три пикселя, и линия перестаёт быть разделителем — становится
 * полосой, которая спорит с содержимым за внимание. Толщину здесь диктует
 * экран, а не макет.
 */
@Composable
fun RipsterHairline(
    modifier: Modifier = Modifier,
    color: Color = RipsterTheme.colors.border_subtle,
    inset: Dp = 0.dp,
) {
    val onePixel = with(LocalDensity.current) { 1f.toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(onePixel)
            .background(color),
    )
}

/**
 * Лист — ЕДИНСТВЕННОЕ место, где тень законна.
 *
 * Здесь она не украшение, а утверждение: «этот слой физически выше остальных,
 * и пока он открыт, нижние недоступны». Ровно поэтому тень запрещена на кнопках
 * и карточках: они лежат в плоскости содержимого и ничего не перекрывают.
 *
 * Один рецепт тени на весь продукт, и он здесь. Второй рецепт означает, что
 * появился второй смысл у одного признака.
 */
@Composable
fun RipsterSheet(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    // Скругление только сверху: лист приходит снизу и уходит за нижний край
    // экрана, скруглённый низ читался бы как «карточка, зачем-то прилипшая ко дну».
    val shape = RoundedCornerShape(topStart = Radii.RCard, topEnd = Radii.RCard)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 16.dp, shape = shape, clip = false)
            .background(colors.surface_overlay, shape)
            .padding(horizontal = spacing.gutter, vertical = spacing.lg),
        content = content,
    )
}

/**
 * Ручка листа. Неинтерактивна: перетаскивание вешает на сам лист, а ручка —
 * только знак «это можно тянуть». Кликабельная ручка на 4 dp высоты нарушила бы
 * порог касания 48 dp, и вылечить это можно лишь расширением зоны, то есть
 * невидимой мишенью, о которой человек не знает.
 */
@Composable
fun RipsterSheetHandle(modifier: Modifier = Modifier) {
    val colors = RipsterTheme.colors
    Box(
        modifier = modifier
            .height(4.dp)
            .background(colors.border_strong, Radii.PillShape),
    )
}
