package net.ripster.mobile.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * Живая обложка — НАШ моушн, а не чужое видео (решение владельца 13.09.2026:
 * «видео прямо из эпла спиздить — не наш метод»). Движение синтезируем сами из
 * статичного кавера + палитры краёв: получается «что-то среднее» между статикой
 * и видео — картинка дышит, по ней плывёт магический свет цветами обложки.
 *
 * Три независимых слоя, каждый со своим ДЛИННЫМ и НЕкратным периодом — чтобы
 * ничто не совпадало в такт и не читалось как пульс (та же логика, что у ореола
 * буферизации: разные циклы → «магия», а не мигание):
 *   1) Ken Burns — очень медленный зум+дрейф самой обложки (graphicsLayer).
 *   2) Аура — два больших мягких радиальных пятна цветами палитры, аддитивно
 *      поверх (BlendMode.Plus), кружат по разным орбитам и переливаются.
 *   3) Дыхание яркости ауры — едва заметное, чтобы свет «жил».
 *
 * reduced-motion (ANIMATOR_DURATION_SCALE == 0) → всё замирает: рендерим
 * статичный кавер без анимаций (уважение к системной настройке и батарее).
 */
@Composable
fun LivingCover(
    url: String?,
    modifier: Modifier = Modifier,
    fallbackModel: Any? = null,
) {
    val ctx = LocalContext.current
    val animationsOn = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
    val palette = rememberCoverEdgePalette(url)

    if (!animationsOn) {
        Box(modifier.clipToBounds()) {
            Cover(url = url, modifier = Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape,
                fallbackModel = fallbackModel)
            AuraLayer(palette, kb = 0f, orbit1 = 0.12f, orbit2 = 0.6f, breath = 0.5f, modifier = Modifier.fillMaxSize())
        }
        return
    }

    val tr = rememberInfiniteTransition(label = "living-cover")
    // Ken Burns: один медленный проход туда-обратно, ~26 c. Reverse, а не Restart —
    // иначе на стыке дёрнется зум.
    val kb by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "kb",
    )
    // Орбиты ауры — разной длины, обе Restart по полному кругу (0..1 = 2π, стык
    // без разрыва).
    val orbit1 by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Restart), label = "o1",
    )
    val orbit2 by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(31_000, easing = LinearEasing), RepeatMode.Restart), label = "o2",
    )
    val breath by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Reverse), label = "breath",
    )

    // clipToBounds ОБЯЗАТЕЛЕН: graphicsLayer-зум увеличивает картинку за пределы
    // её бокса, а без клипа увеличенная обложка вылезает поверх соседней хром-
    // панели (лого проекта, кнопка «Настройки») — жалоба владельца 13.09.2026.
    Box(modifier.clipToBounds()) {
        // 1) Обложка с Ken Burns. Зум 1.0→1.09, лёгкий диагональный дрейф.
        val scale = 1f + 0.09f * kb
        val dx = (kb - 0.5f) * 0.06f
        val dy = (0.5f - kb) * 0.05f
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = size.width * dx
                translationY = size.height * dy
            },
        ) {
            Cover(url = url, modifier = Modifier.fillMaxSize(),
                shape = androidx.compose.ui.graphics.RectangleShape, fallbackModel = fallbackModel)
        }
        // 2+3) Аура поверх.
        AuraLayer(palette, kb, orbit1, orbit2, breath, Modifier.fillMaxSize())
    }
}

/**
 * Слой магической ауры: два больших радиальных пятна цветами палитры, кружащие
 * по своим орбитам и медленно меняющие оттенок (перелив по палитре). Рисуем
 * аддитивно (Plus) с малой альфой — свет добавляется к обложке, не затирая её.
 */
@Composable
private fun AuraLayer(
    palette: List<Color>,
    kb: Float,
    orbit1: Float,
    orbit2: Float,
    breath: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.drawWithContent {
            drawContent()
            val w = size.width; val h = size.height
            val tau = (Math.PI * 2).toFloat()
            // цвета плывут по палитре со сдвигом фаз, чтобы пятна отличались
            val cA = paletteFlow(palette, orbit1)
            val cB = paletteFlow(palette, orbit2 + 0.5f)
            val amp = 0.30f + 0.14f * breath   // «дыхание» яркости

            // пятно A — крупнее, ходит по широкой орбите
            val ax = w * (0.5f + 0.30f * cos(orbit1 * tau))
            val ay = h * (0.40f + 0.26f * sin(orbit1 * tau))
            val ar = maxOf(w, h) * (0.85f + 0.10f * breath)
            drawRect(
                brush = Brush.radialGradient(
                    0f to cA.copy(alpha = amp),
                    0.55f to cA.copy(alpha = amp * 0.35f),
                    1f to Color.Transparent,
                    center = Offset(ax, ay), radius = ar,
                ),
                blendMode = BlendMode.Plus,
            )
            // пятно B — противоход, другой цвет
            val bx = w * (0.5f - 0.32f * cos(orbit2 * tau + 1.7f))
            val by = h * (0.55f - 0.24f * sin(orbit2 * tau + 1.7f))
            val br = maxOf(w, h) * (0.9f + 0.10f * (1f - breath))
            drawRect(
                brush = Brush.radialGradient(
                    0f to cB.copy(alpha = amp * 0.85f),
                    0.55f to cB.copy(alpha = amp * 0.30f),
                    1f to Color.Transparent,
                    center = Offset(bx, by), radius = br,
                ),
                blendMode = BlendMode.Plus,
            )
        },
    )
}

/** Плавная выборка цвета из палитры по фазе (кольцевая интерполяция). */
private fun paletteFlow(pal: List<Color>, phase: Float): Color {
    if (pal.size < 2) return pal.firstOrNull() ?: Color.White
    val n = pal.size
    val f = (((phase % 1f) + 1f) % 1f) * n
    val i = f.toInt() % n
    val j = (i + 1) % n
    return lerp(pal[i], pal[j], f - f.toInt())
}
