package net.ripster.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Значки разделов настроек — рисованные, а не шрифтовые.
 *
 * Почему Canvas, а не эмодзи и не Material Icons: эмодзи на разных прошивках
 * выглядят по-разному (на A31 половина набора — цветной Noto, рядом с
 * монохромным интерфейсом это чужеродно), а тянуть material-icons-extended
 * ради двенадцати глифов — плюс мегабайт в APK и целый шрифт в память. Здесь
 * двенадцать штрихов, они одинаковы везде и красятся текущей темой.
 *
 * Все глифы вписаны в один квадрат и рисуются одной толщиной, поэтому в
 * колонке они читаются как набор, а не как случайные картинки.
 */
enum class SettingsGlyph {
    ACCOUNTS, QUALITY, STORAGE, NETWORK, APP, PAIRING,
    PLAYER, EQUALIZER, RADAR, DIGS, TOOLS, DIAGNOSTICS, ABOUT,
}

@Composable
fun SettingsIcon(kind: SettingsGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.minDimension
        val s = w * 0.085f                    // одна толщина штриха на весь набор
        val st = Stroke(width = s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), s,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
            drawCircle(tint, r * w, Offset(cx * w, cy * w),
                style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)

        when (kind) {
            // Голова и плечи — самый узнаваемый знак «это про учётку».
            SettingsGlyph.ACCOUNTS -> {
                circle(0.5f, 0.34f, 0.16f)
                arc(tint, s, Rect(Offset(0.20f * w, 0.56f * w), Size(0.60f * w, 0.56f * w)), 180f, 180f)
            }
            // Три столбика разной высоты — «уровень качества», не «звук».
            SettingsGlyph.QUALITY -> {
                line(0.26f, 0.74f, 0.26f, 0.52f)
                line(0.50f, 0.74f, 0.50f, 0.34f)
                line(0.74f, 0.74f, 0.74f, 0.44f)
            }
            SettingsGlyph.STORAGE -> {
                val p = Path().apply {
                    moveTo(0.16f * w, 0.72f * w); lineTo(0.16f * w, 0.30f * w)
                    lineTo(0.42f * w, 0.30f * w); lineTo(0.50f * w, 0.40f * w)
                    lineTo(0.84f * w, 0.40f * w); lineTo(0.84f * w, 0.72f * w); close()
                }
                drawPath(p, tint, style = st)
            }
            // Три дуги и точка — сеть, а не «wi-fi конкретного вендора».
            SettingsGlyph.NETWORK -> {
                arc(tint, s, Rect(Offset(0.14f * w, 0.26f * w), Size(0.72f * w, 0.72f * w)), 200f, 140f)
                arc(tint, s, Rect(Offset(0.28f * w, 0.40f * w), Size(0.44f * w, 0.44f * w)), 200f, 140f)
                circle(0.5f, 0.72f, 0.055f, fill = true)
            }
            // Ползунки — «настройки самой программы».
            SettingsGlyph.APP -> {
                line(0.18f, 0.34f, 0.82f, 0.34f); circle(0.62f, 0.34f, 0.10f)
                line(0.18f, 0.66f, 0.82f, 0.66f); circle(0.38f, 0.66f, 0.10f)
            }
            // Два звена — сопряжение: связаны, но остаются двумя устройствами.
            SettingsGlyph.PAIRING -> {
                arc(tint, s, Rect(Offset(0.10f * w, 0.34f * w), Size(0.46f * w, 0.32f * w)), 90f, 180f)
                arc(tint, s, Rect(Offset(0.44f * w, 0.34f * w), Size(0.46f * w, 0.32f * w)), 270f, 180f)
                line(0.42f, 0.50f, 0.58f, 0.50f)
            }
            SettingsGlyph.PLAYER -> {
                circle(0.5f, 0.5f, 0.34f)
                val p = Path().apply {
                    moveTo(0.43f * w, 0.36f * w); lineTo(0.66f * w, 0.50f * w)
                    lineTo(0.43f * w, 0.64f * w); close()
                }
                drawPath(p, tint)
            }
            // Каналы с бегунками на РАЗНОЙ высоте — иначе не отличить от QUALITY.
            SettingsGlyph.EQUALIZER -> {
                line(0.28f, 0.20f, 0.28f, 0.80f); circle(0.28f, 0.62f, 0.09f, fill = true)
                line(0.50f, 0.20f, 0.50f, 0.80f); circle(0.50f, 0.36f, 0.09f, fill = true)
                line(0.72f, 0.20f, 0.72f, 0.80f); circle(0.72f, 0.55f, 0.09f, fill = true)
            }
            // Развёртка радара: два кольца, ОДИН луч и засветка.
            // Первая версия рисовала луч вертикально и второй по диагонали —
            // на экране это читалось круговой диаграммой, а не радаром.
            SettingsGlyph.RADAR -> {
                circle(0.5f, 0.5f, 0.34f)
                circle(0.5f, 0.5f, 0.17f)
                line(0.5f, 0.5f, 0.76f, 0.28f)
                circle(0.70f, 0.32f, 0.075f, fill = true)
            }
            // Ящик с пластинками: раскопки — это перебор чужих коробок.
            // Лопата (треугольник на палке) в колонке читалась стрелкой вниз,
            // то есть «скачать», — прямо рядом с кружком загрузки.
            SettingsGlyph.DIGS -> {
                drawPath(Path().apply {
                    moveTo(0.14f * w, 0.36f * w); lineTo(0.86f * w, 0.36f * w)
                    lineTo(0.78f * w, 0.80f * w); lineTo(0.22f * w, 0.80f * w); close()
                }, tint, style = st)
                line(0.40f, 0.36f, 0.36f, 0.80f)
                line(0.60f, 0.36f, 0.64f, 0.80f)
            }
            // Гаечный ключ: разомкнутый зев + ручка. Замкнутый круг с ручкой,
            // как было, — это лупа, а лупа в приложении уже значит поиск.
            SettingsGlyph.TOOLS -> {
                arc(tint, s, Rect(Offset(0.14f * w, 0.14f * w), Size(0.40f * w, 0.40f * w)), 120f, 260f)
                line(0.46f, 0.46f, 0.82f, 0.82f)
            }
            // Лист с загнутым углом и строками — «отчёт», а не «жучок»:
            // человек отправляет ТЕКСТ, и значок обещает ровно это.
            SettingsGlyph.DIAGNOSTICS -> {
                line(0.24f, 0.14f, 0.62f, 0.14f)
                line(0.24f, 0.14f, 0.24f, 0.86f)
                line(0.24f, 0.86f, 0.78f, 0.86f)
                line(0.78f, 0.86f, 0.78f, 0.32f)
                line(0.62f, 0.14f, 0.78f, 0.32f)
                line(0.38f, 0.46f, 0.66f, 0.46f)
                line(0.38f, 0.62f, 0.66f, 0.62f)
            }
            SettingsGlyph.ABOUT -> {
                circle(0.5f, 0.5f, 0.34f)
                circle(0.5f, 0.33f, 0.055f, fill = true)
                line(0.5f, 0.46f, 0.5f, 0.70f)
            }
        }
    }
}

/** Дуга без заливки — в DrawScope нет короткой формы под «обвести сектор». */
private fun DrawScope.arc(color: Color, stroke: Float, r: Rect, start: Float, sweep: Float) {
    drawArc(
        color = color, startAngle = start, sweepAngle = sweep, useCenter = false,
        topLeft = r.topLeft, size = r.size,
        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            pathEffect = PathEffect.cornerPathEffect(0f)),
    )
}
