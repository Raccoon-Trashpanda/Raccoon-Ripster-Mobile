package net.ripster.mobile.ui.premium

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Композиция стекла: как РИСУЕТСЯ ОДИН ОРГАН УПРАВЛЕНИЯ, когда план разрешил
 * стекло. Числа берёт из [LiquidGlass] и ничего сама не решает — здесь только
 * порядок слоёв и то, чем они рисуются.
 *
 * Стеклянный орган собирается снизу вверх:
 *  1. мягкая тень под ним ([GlassSurface.elevationDp]) — орган оторван от
 *     подложки, а не наклеен;
 *  2. то, что через него видно: настоящее размытие слоя-источника (Android 12+)
 *     либо сразу тон стекла (Android 11 и ниже);
 *  3. тон стекла — полупрозрачный, с оттенком того, что за органом;
 *  4. свет: зеркальный блик по верхней грани, светлая окантовка по периметру,
 *     внутренняя тень у нижней кромки (она и даёт ощущение толщины) и — с
 *     Android 13 — AGSL-линза, собирающая свет в нитку на кромках;
 *  5. сверху содержимое органа: глиф, подпись, полоса.
 *
 * Нигде выше нет непрозрачной серой заливки: именно она в предыдущей сборке и
 * называлась «стеклом», за что и была удалена.
 */

/** Радиус настоящего блюра. Ниже 20dp стекло мутнеет в шумы, выше — плывёт. */
private val GLASS_BLUR = 28.dp

/**
 * Доля меньшей стороны органа, которую занимает кромка линзы. Меньше — свет
 * собирается в волосяную линию и читается артефактом, больше — линза съедает
 * середину, и орган перестаёт быть плоским стеклом.
 */
private const val LENS_EDGE_SHARE = 0.36f

/** Сколько света отдаёт кромке линза: [GlassSurface.lens] — сила, а не альфа. */
private const val LENS_LIGHT_GAIN = 4f

/** Минимальная ширина кромки линзы: на крошечном чипе доля была бы уже пикселя. */
private val LENS_EDGE_MIN = 6.dp

/**
 * AGSL-линза. Координаты — пиксели органа от верхнего левого угла.
 *
 * Линза НЕ переписывает то, что за стеклом: чужой слой оттуда не достать (Haze
 * 0.7 отдаёт панели готовый блюр, а не текстуру задника, и крючка под свой
 * RenderEffect у него нет). Поэтому преломление рисуется по той стороне, которая
 * у стекла своя: по свету кромки. Он собирается в нитку там, где грань
 * скругляется, идёт волной вдоль периметра и неравномерен по высоте — верхняя
 * грань ловит свет сильнее нижней. Именно это глаз и читает как «край стекла
 * гнёт картинку за собой».
 */
internal const val LENS_AGSL = """
uniform float2 uSize;
uniform float uEdge;
uniform float uAmount;
half4 main(float2 c) {
    float dx = min(c.x, uSize.x - c.x);
    float dy = min(c.y, uSize.y - c.y);
    float d = min(dx, dy);
    float t = clamp(1.0 - d / uEdge, 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    float along = c.x / max(uSize.x, 1.0) + c.y / max(uSize.y, 1.0);
    float wick = 0.72 + 0.28 * sin(along * 12.566);
    float top = mix(0.45, 1.0, 1.0 - c.y / max(uSize.y, 1.0));
    return half4(1.0, 1.0, 1.0, t * wick * top * uAmount);
}
"""

/**
 * Орган управления, нарисованный стеклом.
 *
 * @param fx план этого плеера: уровень стекла и слой-источник размытия
 * @param shape форма органа — стекло наследует её, а не рисует свой прямоугольник
 * @param behind цвет того, что видно ЧЕРЕЗ орган: обложка, её свечение, плашка
 *   списка. Стекло подмешивает его себе в тон, поэтому на тёплой обложке кромка
 *   тёплая, а на холодной — холодная.
 * @param fallback чем рисовать орган, если стекла телефону не положено
 *   ([GlassTier.Plain]): обычно ровно та заливка, что стояла до режима.
 * @param dark тёмная ли основа под органом: базовая краска стекла в белом и
 *   тёмном холсте разная, иначе на светлом фоне не читается объёмом. По
 *   умолчанию определяется по светлоте [behind] — руки вызывающего здесь
 *   лишние, а имя темы ничего не скажет о засвеченной обложке.
 */
@Composable
fun Modifier.liquidGlass(
    fx: PremiumPlayerFx,
    shape: Shape,
    behind: Color,
    fallback: Color,
    dark: Boolean = LiquidGlass.isDarkCanvas(behind),
): Modifier {
    val surface = fx.surfaceFor(behind, dark) ?: return this.background(fallback)
    val source = if (fx.tier.hasBlur) fx.glass else null
    return this
        .shadow(surface.elevationDp.dp, shape, clip = false)
        .clip(shape)
        // Дно органа. tint отдаём Haze: он кладёт его ПОСЛЕ блюра и в границах
        // формы — второй заливкой поверх вышло бы двойное дно.
        .then(
            if (source != null) {
                Modifier.hazeChild(source, shape, HazeStyle(surface.tint, GLASS_BLUR, 0f))
            } else {
                Modifier.background(surface.tint)
            },
        )
        .liquidGlassLight(surface, shape)
}

/** Блик, кромка, внутренняя тень и линза — свет поверх стекла. */
private fun Modifier.liquidGlassLight(surface: GlassSurface, shape: Shape): Modifier =
    drawWithCache {
        // Нуль — и там, где линзы нет по планам, и там, где система её не умеет.
        val lens = if (surface.lens > 0f) {
            runCatching { lensBrush(surface, size.width, size.height) }.getOrNull()
        } else {
            null
        }
        val rimWidth = 1.dp.toPx()
        onDrawWithContent {
            drawContent()
            drawGlassLight(surface, shape, lens, rimWidth)
        }
    }

/**
 * Линза как кисть. Собирается на размер органа: uniform `uSize` шейдер узнаёт
 * только отсюда, а пересоздаётся исключительно при смене раскладки.
 */
private fun Density.lensBrush(surface: GlassSurface, w: Float, h: Float): Brush? {
    if (Build.VERSION.SDK_INT < LiquidGlass.MIN_SDK_REFRACTIVE) return null
    val edge = max(min(w, h) * LENS_EDGE_SHARE, LENS_EDGE_MIN.toPx())
    val shader = RuntimeShader(LENS_AGSL).apply {
        setFloatUniform("uSize", w, h)
        setFloatUniform("uEdge", edge)
        setFloatUniform("uAmount", surface.lens * LENS_LIGHT_GAIN)
    }
    return ShaderBrush(shader)
}

private fun DrawScope.drawGlassLight(
    surface: GlassSurface,
    shape: Shape,
    lens: Brush?,
    rimWidth: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Зеркальный блик: светлее всего у самой верхней кромки, гаснет к
    // highlightAt. Полоса, а не заливка через весь орган.
    if (surface.highlight > 0f) {
        val band = h * surface.highlightAt
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = surface.highlight),
                1f to Color.Transparent,
                startY = 0f,
                endY = band,
            ),
            topLeft = Offset.Zero,
            size = Size(w, band),
        )
    }

    // Внутренняя тень у нижней кромки — она и даёт ощущение толщины.
    if (surface.innerShadow > 0f) {
        val band = h * surface.innerShadowAt
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black.copy(alpha = surface.innerShadow),
                startY = h - band,
                endY = h,
            ),
            topLeft = Offset(0f, h - band),
            size = Size(w, band),
        )
    }

    // Светлая окантовка по периметру: тоньше пикселя не бывает, в два — уже
    // рамка, а не кромка стекла.
    if (surface.rim > 0f) {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawRim(outline, Color.White.copy(alpha = surface.rim), rimWidth)
    }

    // Линза — поверх всего света: она и есть то, что кромка гнёт.
    if (lens != null) drawRect(lens)
}

/**
 * Кромка рисуется половиной толщины внутрь: контур формы стоит ровно на границе
 * клипа, и целиком снаружи он был бы просто отрезан.
 */
private fun DrawScope.drawRim(outline: Outline, color: Color, width: Float) {
    if (outline is Outline.Rectangle) {
        drawRect(
            color,
            topLeft = Offset(width / 2f, width / 2f),
            size = Size(max(size.width - width, 0f), max(size.height - width, 0f)),
            style = Stroke(width),
        )
        return
    }
    val path = Path().apply { addOutline(outline) }
    drawPath(path, color, style = Stroke(width))
}

/**
 * Пружина нажатия на стеклянный орган.
 *
 * В режиме стекло продавливается под пальцем по вертикали и расходится по
 * горизонтали ([PressSpring]) и выпрыгивает обратно с перелётом — перелёт даёт
 * пружина [PremiumPlayerFx.pressSpec], а не мы. Без режима (телефон запретил
 * пружины, режим выключен) орган ведёт себя ровно как до режима: обычное
 * плоское сжатие до [plain] тем же дефолтным доводчиком, что стоял в экране.
 * «Дорогие» числа не имеют права тихонько поменять базовый экран.
 *
 * @param plain во что сжиматься без пружин — та величина, что была в этом
 *   органе до режима.
 */
@Composable
fun Modifier.glassSquish(
    fx: PremiumPlayerFx,
    pressed: Boolean,
    plain: Float = 0.9f,
    label: String? = null,
): Modifier {
    val spec: AnimationSpec<Float> = fx.pressSpec
    val shape = PressSpring.shapeFor(pressed, fx.plan.springMotion)
    val rest = if (pressed) plain else 1f
    val tag = label ?: "glass-squish"
    val sx by animateFloatAsState(shape?.scaleX ?: rest, spec, label = tag)
    val sy by animateFloatAsState(shape?.scaleY ?: rest, spec, label = "$tag-y")
    return this.scale(sx, sy)
}

/**
 * Цвет глифа поверх стекла: свой, если он выдерживает контраст на худшем участке
 * поверхности, иначе белый/чёрный — по светлоте самого стекла, как это делает
 * Apple, кладущая на «жидкое стекло» то белый, то чёрный значок.
 *
 * [behind] обязан быть тем же цветом, что отдан в [liquidGlass]: контраст
 * считается по реальному стеклу, а не по цвету заливки из макета.
 */
fun PremiumPlayerFx.glyphColor(
    icon: Color,
    behind: Color,
    dark: Boolean = LiquidGlass.isDarkCanvas(behind),
    light: Color = Color.White,
    deep: Color = Color.Black,
): Color {
    val surface = surfaceFor(behind, dark) ?: return icon
    return LiquidGlass.readableIcon(icon, surface, behind, light, deep)
}
