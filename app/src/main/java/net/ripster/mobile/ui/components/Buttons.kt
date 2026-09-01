package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.theme.BorderWidth
import net.ripster.mobile.ui.theme.ControlHeight
import net.ripster.mobile.ui.theme.DangerLetterSpacing
import net.ripster.mobile.ui.theme.FocusRingWidth
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.Radii
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Weights

/**
 * Четыре уровня. Больше не бывает: если для двух кнопок разницу приходится
 * объяснять абзацем, разделение неверное.
 *
 * ГЛАВНОЕ ПРАВИЛО ЭТОГО ФАЙЛА: иерархия закодирована ТРЕМЯ независимыми
 * признаками сразу — заливка, вес шрифта, высота. Каждый работает в одиночку.
 * Обесцветьте макет: главная останется самым тёмным (или самым светлым) пятном,
 * самым жирным текстом и самым высоким прямоугольником. Сейчас в вебе уровни
 * различаются только оттенком, то есть в градациях серого сливаются полностью, а
 * у части людей цвета нет вовсе — и у остальных он разный в шести темах.
 */
enum class ButtonLevel {
    /** Главная: сплошная заливка, вес 700, высота 44. Одна на экран. */
    Primary,

    /** Обычная: поверхность + граница 1dp, вес 600, высота 40. */
    Standard,

    /** Тихая: ни заливки, ни границы, вес 500, высота 40. */
    Quiet,

    /**
     * Опасная: заливка ПОЯВЛЯЕТСЯ на hover/press, граница цветом опасности,
     * вес 600 + разрядка.
     *
     * Красная плашка в покое кричит на человека, который просто смотрит на экран,
     * и от этого перестаёт восприниматься. Заливка приходит ровно в тот момент,
     * когда палец уже на кнопке — то есть когда предупреждение ещё можно
     * применить.
     */
    Danger,
}

/**
 * ЗАПРЕЩЕНО в этом файле и во всём управляющем слое:
 *  - тени (Modifier.shadow / elevation). Тень означает «слой выше по оси Z».
 *    Кнопка не является слоем; тень на ней — это 81 рецепт тени из веба, где
 *    поднятыми оказались все элементы сразу, то есть никакой.
 *  - градиенты. Градиент задаёт два значения цвета там, где роль одна, и
 *    ломает проверку контраста: неясно, к какому из концов её считать.
 *  - цветное свечение (box-shadow: 0 0 Npx цвет). Свечение читается как
 *    состояние («активно», «ошибка»), которое ничем не подтверждено.
 *  - фирменные цвета сервисов. Сервис обозначает иконка, которая уже есть.
 */
@Composable
fun RipsterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    level: ButtonLevel = ButtonLevel.Standard,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()

    // hovered существует и на телефоне: стилус и подключённая мышь дают его
    // штатно, поэтому «опасная» кнопка ведёт себя одинаково на всех вводах.
    val active = pressed || hovered

    val height: Dp = when (level) {
        ButtonLevel.Primary -> ControlHeight.Primary
        else -> ControlHeight.Secondary
    }

    val weight: FontWeight = when (level) {
        ButtonLevel.Primary -> Weights.Primary
        ButtonLevel.Standard -> Weights.Standard
        ButtonLevel.Quiet -> Weights.Quiet
        ButtonLevel.Danger -> Weights.Standard
    }

    // Разрядка только у опасной. Это четвёртый, слабый признак: он не несёт
    // иерархию сам по себе, но чуть замедляет чтение подписи — а перед
    // разрушающим действием замедление уместно.
    val letterSpacing: TextUnit = if (level == ButtonLevel.Danger) DangerLetterSpacing else 0.sp

    val background: Color = when {
        !enabled -> Color.Transparent
        level == ButtonLevel.Primary && pressed -> colors.accent_active
        level == ButtonLevel.Primary && hovered -> colors.accent_hover
        level == ButtonLevel.Primary -> colors.accent_fill
        level == ButtonLevel.Standard && pressed -> colors.surface_active
        level == ButtonLevel.Standard && hovered -> colors.surface_hover
        level == ButtonLevel.Standard -> colors.surface_raised
        level == ButtonLevel.Quiet && pressed -> colors.surface_active
        level == ButtonLevel.Quiet && hovered -> colors.surface_hover
        level == ButtonLevel.Quiet -> Color.Transparent
        level == ButtonLevel.Danger && active -> colors.danger_fill
        else -> Color.Transparent
    }

    val borderColor: Color = when {
        !enabled && level == ButtonLevel.Standard -> colors.border_subtle
        !enabled -> Color.Transparent
        level == ButtonLevel.Standard -> colors.border_default
        level == ButtonLevel.Danger -> colors.danger_text
        else -> Color.Transparent
    }

    val contentColor: Color = when {
        !enabled -> colors.text_disabled
        level == ButtonLevel.Primary -> colors.text_on_fill
        level == ButtonLevel.Standard -> colors.text_primary
        level == ButtonLevel.Quiet -> colors.text_secondary
        // Как только заливка пришла, подпись обязана уйти на text_on_fill:
        // danger_text на danger_fill — это тот самый случай, когда роль
        // обслуживает и краску, и надпись, и надпись пропадает.
        level == ButtonLevel.Danger && active -> colors.text_on_fill
        else -> colors.danger_text
    }

    // Внешняя коробка держит порог касания 48 dp, внутренняя — видимую высоту
    // 44/40. Разделение обязательно: если растянуть видимую высоту до 48,
    // исчезнет канал «высота», а если оставить кликабельной только видимую
    // область — исчезнет порог. Здесь целы оба.
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .clickable(
                interactionSource = interaction,
                indication = null, // всё состояние рисуем сами: ripple в шести темах даёт шесть разных пятен
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(height)
                .widthIn(min = MinTouchTarget)
                // Кольцо фокуса рисуется ПОВЕРХ границы и тем же радиусом:
                // это единственный отклик для физической клавиатуры и D-pad,
                // и он не должен зависеть от того, есть ли у уровня граница.
                .then(
                    if (focused) Modifier.border(FocusRingWidth, colors.focus_ring, Radii.CtlShape)
                    else Modifier
                )
                .background(background, Radii.CtlShape)
                .then(
                    if (borderColor != Color.Transparent && !focused)
                        Modifier.border(BorderWidth, borderColor, Radii.CtlShape)
                    else Modifier
                )
                .padding(horizontal = spacing.controlPadding),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (leading != null) leading()
                BasicText(
                    text = text,
                    style = TextStyle(
                        color = contentColor,
                        fontSize = type.label,
                        fontWeight = weight,
                        letterSpacing = letterSpacing,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
