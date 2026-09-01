package net.ripster.mobile.ui.navigation

import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.components.RipsterHairline
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Нижняя навигация приложения — 5 разделов: Плеер, Радар, Библиотека,
 * Загрузки, Инструменты (конвертер + спектр/проверка качества). Пятый пункт
 * добавлен по прямой просьбе владельца.
 *
 * Иконки нарисованы Canvas-примитивами (drawLine/drawPath/drawArc), как и
 * остальная иконография в проекте — см. Transport.kt (drawShuffleGlyph/
 * drawRepeatGlyph): без внешней иконочной библиотеки и без иконочных шрифтов.
 * Каждый глиф строится в относительных координатах (w * fraction) внутри
 * квадратного Canvas, чтобы одинаково масштабироваться независимо от
 * плотности экрана.
 *
 * Активный таб кодируется ДВУМЯ независимыми каналами — цветом (accent_text
 * вместо text_secondary) и начертанием (W700 вместо обычного), а не одним
 * лишь цветом — тот же принцип, что и в Buttons.kt: иерархия должна читаться
 * и в чёрно-белом/для-дальтоников рендере. Заливка-подложка (pill/circle) под
 * активной иконкой намеренно НЕ используется: Radii.RPill зарезервирован в
 * этой дизайн-системе только для некликабельных статусов/бейджей, а таб —
 * кликабельный элемент управления, поэтому подложку ему в принципе нельзя
 * рисовать пилюлей; здесь решено не рисовать подложку вовсе, а не заменять
 * её на RCtl без необходимости.
 *
 * Text — BasicText, не Material (в проекте нет зависимости на
 * androidx.compose.material/material3 в этом слое).
 */
// Дизайн ripster-neon-skin_2 (Home.dc.html): нижняя навигация — Главная /
// Поиск / Библиотека / Загрузки / Радар. Плеер открывается из мини-плеера и
// карточек Главной; Инструменты — из Настроек. Player/Tools остаются в enum
// как назначения экранов, просто не в баре.
enum class RipsterDestination { Home, Search, Player, Radar, Library, Downloads, Tools }

private data class TabSpec(
    val destination: RipsterDestination,
    val labelKey: String,
    val icon: DrawScope.(color: Color) -> Unit,
)

private val tabSpecs = listOf(
    TabSpec(RipsterDestination.Home, "nav.home") { color -> drawHomeGlyph(color) },
    TabSpec(RipsterDestination.Search, "nav.search") { color -> drawSearchGlyph(color) },
    TabSpec(RipsterDestination.Library, "nav.library") { color -> drawLibraryGlyph(color) },
    TabSpec(RipsterDestination.Downloads, "nav.downloads") { color -> drawDownloadsGlyph(color) },
    TabSpec(RipsterDestination.Radar, "nav.radar") { color -> drawRadarGlyph(color) },
)

@Composable
fun BottomNav(
    current: RipsterDestination,
    onSelect: (RipsterDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = LocalAppLang.current

    Column(modifier = modifier.fillMaxWidth()) {
        RipsterHairline(modifier = Modifier.fillMaxWidth())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface_raised)
                .padding(vertical = spacing.xs),
        ) {
            tabSpecs.forEach { spec ->
                val isActive = spec.destination == current
                val tint = if (isActive) colors.accent_text else colors.text_secondary
                val weight = if (isActive) FontWeight.W700 else FontWeight.W400

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = MinTouchTarget)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(spec.destination) },
                        )
                        .semantics {
                            role = Role.Tab
                            contentDescription = tr(spec.labelKey, lang)
                        }
                        .padding(vertical = spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(modifier = Modifier.size(22.dp)) {
                        spec.icon(this, tint)
                    }

                    BasicText(
                        text = tr(spec.labelKey, lang),
                        style = TextStyle(color = tint, fontWeight = weight, fontSize = type.caption),
                        modifier = Modifier
                            .wrapContentHeight()
                            .padding(top = spacing.xs),
                    )
                }
            }
        }
    }
}

/** Главная — контур домика: скат крыши + коробка стен. */
private fun DrawScope.drawHomeGlyph(color: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.11f
    val roof = Path().apply {
        moveTo(w * 0.14f, h * 0.5f)
        lineTo(w * 0.5f, h * 0.16f)
        lineTo(w * 0.86f, h * 0.5f)
    }
    drawPath(roof, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val walls = Path().apply {
        moveTo(w * 0.24f, h * 0.44f)
        lineTo(w * 0.24f, h * 0.84f)
        lineTo(w * 0.76f, h * 0.84f)
        lineTo(w * 0.76f, h * 0.44f)
    }
    drawPath(walls, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Поиск — окружность лупы + ручка. */
private fun DrawScope.drawSearchGlyph(color: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.11f
    drawCircle(color = color, radius = w * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(width = sw))
    drawLine(color = color, start = Offset(w * 0.62f, h * 0.62f), end = Offset(w * 0.86f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
}

/** Плеер — заполненный треугольник play, тот же визуальный язык, что у кнопки воспроизведения. */
private fun DrawScope.drawPlayerGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.28f, h * 0.18f)
        lineTo(w * 0.28f, h * 0.82f)
        lineTo(w * 0.82f, h * 0.5f)
        close()
    }
    drawPath(path, color = color)
}

/** Радар — концентрические дуги, расходящиеся из точки, плюс сама точка-центр. */
private fun DrawScope.drawRadarGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val strokeWidth = w * 0.09f
    val center = Offset(w * 0.22f, h * 0.78f)

    drawCircle(color = color, radius = w * 0.06f, center = center)

    val radii = listOf(w * 0.28f, w * 0.48f, w * 0.68f)
    radii.forEach { r ->
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 50f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

/** Библиотека — три горизонтальные полки разной длины (вид на стопку пластинок с торца). */
private fun DrawScope.drawLibraryGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val strokeWidth = w * 0.11f

    val ys = listOf(h * 0.24f, h * 0.5f, h * 0.76f)
    val ends = listOf(w * 0.82f, w * 0.9f, w * 0.62f)

    ys.forEachIndexed { index, y ->
        drawLine(
            color = color,
            start = Offset(w * 0.1f, y),
            end = Offset(ends[index], y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/** Инструменты — гаечный ключ по диагонали (конвертер + спектр/проверка). */
private fun DrawScope.drawToolsGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val sw = w * 0.13f
    // рукоятка
    drawLine(
        color = color,
        start = Offset(w * 0.34f, h * 0.66f),
        end = Offset(w * 0.80f, h * 0.20f),
        strokeWidth = sw,
        cap = StrokeCap.Round,
    )
    // головка ключа — разомкнутое кольцо
    drawArc(
        color = color,
        startAngle = 20f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(w * 0.12f, h * 0.44f),
        size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.40f),
        style = Stroke(width = sw, cap = StrokeCap.Round),
    )
}

/** Загрузки — стрелка вниз на горизонтальную базовую линию (лоток). */
private fun DrawScope.drawDownloadsGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val strokeWidth = w * 0.1f

    drawLine(
        color = color,
        start = Offset(w * 0.5f, h * 0.12f),
        end = Offset(w * 0.5f, h * 0.62f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )

    val arrowHead = Path().apply {
        moveTo(w * 0.28f, h * 0.42f)
        lineTo(w * 0.5f, h * 0.68f)
        lineTo(w * 0.72f, h * 0.42f)
    }
    drawPath(
        arrowHead,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    drawLine(
        color = color,
        start = Offset(w * 0.18f, h * 0.86f),
        end = Offset(w * 0.82f, h * 0.86f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}
