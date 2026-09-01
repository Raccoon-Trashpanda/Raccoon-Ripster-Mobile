package net.ripster.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Неоновая палитра-заглушка, если обложки нет / не разобралась. */
private val NEON = listOf(
    Color(0xFFFF4D8F), Color(0xFFA238FF), Color(0xFF3ECFAA),
    Color(0xFF4DA3FF), Color(0xFFFF4D8F),
)

/**
 * 5 цветов по периметру обложки (TL → TR → BR → BL → TL) — чтобы
 * sweep-градиент кольца в каждой точке совпадал с цветом обложки под ней.
 * Тёмные обложки чуть «поджигаем», иначе ореол не виден.
 */
@Composable
fun rememberCoverEdgePalette(url: String?): List<Color> {
    val ctx = LocalContext.current
    val pal by produceState(initialValue = NEON, url) {
        if (url.isNullOrBlank()) { value = NEON; return@produceState }
        value = runCatching {
            withContext(Dispatchers.IO) {
                val req = ImageRequest.Builder(ctx).data(url).size(24).allowHardware(false).build()
                val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap(24, 24)
                    ?: return@withContext NEON
                fun p(x: Int, y: Int): Color {
                    val v = bmp.getPixel(x, y)
                    var col = Color((v shr 16 and 0xFF) / 255f, (v shr 8 and 0xFF) / 255f, (v and 0xFF) / 255f)
                    // поджечь: к белому, если совсем тёмный
                    val lum = 0.299f * col.red + 0.587f * col.green + 0.114f * col.blue
                    if (lum < 0.28f) col = lerp(col, Color.White, 0.45f)
                    return col
                }
                listOf(p(3, 3), p(20, 3), p(20, 20), p(3, 20), p(3, 3))
            }
        }.getOrDefault(NEON)
    }
    return pal
}

private fun paletteAt(pal: List<Color>, frac: Float): Color {
    if (pal.size < 2) return pal.firstOrNull() ?: Color.White
    val f = ((frac % 1f) + 1f) % 1f
    val seg = 1f / (pal.size - 1)
    val i = (f / seg).toInt().coerceIn(0, pal.size - 2)
    return lerp(pal[i], pal[i + 1], (f - i * seg) / seg)
}

/**
 * Тонкий световой ореол, бегущий по контуру карточки ПО ЧАСОВОЙ и
 * повторяющий палитру обложки в точке трассы — индикация буферизации.
 *
 * Слои: тусклый sweep-контур целиком + короткая яркая «комета» (широкий
 * аддитивный ореол + тонкая белая сердцевина) + затухающий хвост.
 */
fun Modifier.bufferingRing(
    active: Boolean,
    palette: List<Color>,
    corner: Dp = 16.dp,
    width: Dp = 3.dp,
): Modifier = composed {
    if (!active) return@composed this
    val tr = rememberInfiniteTransition(label = "buffering-ring")
    val phase by tr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase",
    )
    drawWithContent {
        drawContent()
        val sw = width.toPx()
        val cr = corner.toPx()
        val rr = RoundRect(
            sw * 0.5f, sw * 0.5f, size.width - sw * 0.5f, size.height - sw * 0.5f,
            CornerRadius(cr, cr),
        )
        val outline = Path().apply { addRoundRect(rr) }

        // 0) тёмная подложка-контур — чтобы ореол читался и на светлой обложке
        drawPath(outline, color = Color.Black.copy(alpha = 0.30f), style = Stroke(sw * 2.4f, cap = StrokeCap.Round))
        // 1) весь контур — тусклый sweep палитры (амбиент)
        drawPath(
            outline,
            brush = Brush.sweepGradient(palette, center = center),
            style = Stroke(sw, cap = StrokeCap.Round),
            alpha = 0.34f,
        )

        val pm = PathMeasure().apply { setPath(outline, false) }
        val len = pm.length
        if (len <= 0f) return@drawWithContent

        fun seg(fromFrac: Float, toFrac: Float): Path {
            val a = (((fromFrac % 1f) + 1f) % 1f) * len
            val b = (((toFrac % 1f) + 1f) % 1f) * len
            val out = Path()
            if (b >= a) pm.getSegment(a, b, out, true)
            else { pm.getSegment(a, len, out, true); pm.getSegment(0f, b, out, true) }
            return out
        }

        val headLen = 0.16f          // доля периметра
        val tailLen = 0.34f
        val headCol = paletteAt(palette, phase + headLen * 0.6f)

        // 2) хвост — затухающий, позади головы
        drawPath(
            seg(phase - tailLen, phase),
            brush = Brush.sweepGradient(palette, center = center),
            style = Stroke(sw * 2.0f, cap = StrokeCap.Round),
            alpha = 0.5f,
        )
        // 3) широкий мягкий ореол головы (аддитивно — свечение)
        drawPath(
            seg(phase, phase + headLen),
            color = headCol.copy(alpha = 0.85f),
            style = Stroke(sw * 7f, cap = StrokeCap.Round),
            blendMode = BlendMode.Plus,
        )
        // 4) насыщенное ядро головы — цветом палитры, поверх (видно на любой обложке)
        drawPath(
            seg(phase, phase + headLen),
            color = headCol,
            style = Stroke(sw * 3f, cap = StrokeCap.Round),
        )
        // 5) раскалённая сердцевина
        drawPath(
            seg(phase + headLen * 0.45f, phase + headLen),
            color = Color.White,
            style = Stroke(sw * 1.5f, cap = StrokeCap.Round),
        )
    }
}
