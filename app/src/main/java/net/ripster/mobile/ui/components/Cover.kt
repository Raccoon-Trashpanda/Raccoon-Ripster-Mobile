package net.ripster.mobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import net.ripster.mobile.ui.theme.Motion
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Единый рендер обложки: сеть через Coil, честная нейтральная плашка, пока
 * нет картинки или пока грузится. Одно место — один вид во всех списках и
 * в плеере (правило десктопа про размеры обложек здесь тоже держим:
 * мелкое в сетках, крупный источник в плеере — задаётся размером Modifier).
 *
 * Пока картинка ГРУЗИТСЯ — по плашке идёт мягкий блик (skeleton). Мёртвый
 * серый квадрат читается как «сломалось»; блик говорит «сейчас будет». Если
 * грузить нечего (нет ни url, ни fallback) — плашка статична, блик не врёт
 * про загрузку, которой нет.
 */
@Composable
fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    /** Чем грузить, если [url] пуст — напр. ByteArray встроенной в файл обложки. */
    fallbackModel: Any? = null,
) {
    val c = RipsterTheme.colors
    val model: Any? = if (!url.isNullOrBlank()) url else fallbackModel
    Box(modifier.clip(shape).background(c.surface_raised)) {
        if (model != null) {
            var loading by remember(model) { mutableStateOf(true) }
            if (loading) {
                ShimmerFill(base = c.surface_raised, sweep = c.surface_active)
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { st ->
                    loading = st is AsyncImagePainter.State.Loading ||
                        st is AsyncImagePainter.State.Empty
                },
            )
        }
    }
}

/** Диагональный блик, бесконечно бегущий по плашке. Один проход ≈ [Motion.shimmerPeriodMs]. */
@Composable
private fun ShimmerFill(base: Color, sweep: Color) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(Motion.shimmerPeriodMs, delayMillis = 240),
            RepeatMode.Restart,
        ),
        label = "shimmer-pos",
    )
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val span = size.width.coerceAtLeast(1f)
            val head = -span + p * (span * 2f)
            val brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to base,
                    0.5f to sweep.copy(alpha = 0.55f),
                    1f to base,
                ),
                start = Offset(head, 0f),
                end = Offset(head + span, size.height),
            )
            onDrawBehind { drawRect(brush) }
        },
    )
}
