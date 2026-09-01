package net.ripster.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import net.ripster.mobile.core.model.DownloadItem
import net.ripster.mobile.core.model.DownloadState
import net.ripster.mobile.ui.theme.RipsterTheme
import kotlin.math.abs

/**
 * Порт «орба» загрузок из ПК-версии (`static/js/dlorb.js`, вариант **neon**).
 * Круг ТЕКУЩЕЙ загрузки: переливающийся неоновый скин в СРЕДНЕМ цвете обложки
 * (медленное вращение + «дыхание» яркости + цветное свечение), поверх —
 * кольцо прогресса и процент. За ним — СЕРЫЕ фантомные силуэты очереди
 * (без цвета и чисел), дальше «+N». Текущий круг выкатывается сбоку и
 * укатывается вправо при смене/завершении. Пустая очередь — нулевая высота.
 */
private const val ORB = 46
private const val GHOST_GAP = 15
private const val MAX_GHOSTS = 3

@Composable
fun DownloadOrb(items: List<DownloadItem>, modifier: Modifier = Modifier) {
    val active = items.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
    if (active.isEmpty()) {
        Box(modifier.fillMaxWidth().height(0.dp))
        return
    }

    val ordered = active.sortedByDescending { it.state == DownloadState.RUNNING }
    val visible = ordered.take(1 + MAX_GHOSTS)
    val overflow = (ordered.size - visible.size).coerceAtLeast(0)
    val current = visible.first()

    Box(
        modifier
            .fillMaxWidth()
            .height((ORB + 16).dp)
            .padding(end = 18.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // фантомные силуэты — от дальнего к ближнему
        visible.drop(1).reversed().forEachIndexed { revIdx, _ ->
            val slot = visible.size - 1 - revIdx
            GhostOrb(slot = slot)
        }
        if (overflow > 0) OverflowLabel(slot = visible.size, count = overflow)

        // текущий — с выездом сбоку и укатом вправо при смене id
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn(tween(340))) togetherWith
                    (slideOutHorizontally { it } + fadeOut(tween(300)))
            },
            contentKey = { it.id },
            label = "orb-roll",
        ) { item -> CurrentOrb(item = item) }
    }
}

@Composable
private fun CurrentOrb(item: DownloadItem) {
    val c = RipsterTheme.colors
    val avg = rememberAverageColor(item.track.artworkUrl)
    val tone = avg?.let { deriveTone(it) } ?: hueTone(item.id)

    val frac = item.fraction
    val infinite = rememberInfiniteTransition(label = "orb")
    val spin by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(7500, easing = LinearEasing), RepeatMode.Restart),
        label = "orb-spin",
    )
    // «дыхание» — как @keyframes dlorb-breathe: пульс яркости/насыщенности
    val breathe by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Reverse),
        label = "orb-breathe",
    )
    val ringSpin by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "orb-ring",
    )
    val animFrac by animateFloatAsState(targetValue = frac ?: 0f, label = "orb-frac")

    // Канва больше самого орба, чтобы свечение помещалось; а вращающийся скин
    // клипаем в круг — иначе углы канвы дают «мерцающий квадрат» при вращении.
    val boxDp = (ORB + 16).dp
    Box(Modifier.size(boxDp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(boxDp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = ORB.dp.toPx() / 2f
            val skinR = r - 2.dp.toPx()
            val bright = 0.10f + 0.10f * breathe

            // цветное свечение вокруг (box-shadow: 0 0 12px var(--orb-c)) —
            // радиус подобран так, чтобы уместиться в канву без обрезки углами
            val glowR = (cx - 1f).coerceAtMost(r * 1.6f)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to tone.mid.copy(alpha = 0.55f),
                    0.55f to tone.mid.copy(alpha = 0.22f),
                    1f to Color.Transparent,
                    center = Offset(cx, cy), radius = glowR,
                ),
                radius = glowR, center = Offset(cx, cy),
            )

            // Неоновый скин рисуем ВНУТРИ круглого клипа — тогда вращающиеся
            // sweep/radial-градиенты не могут вылезти углами за пределы круга.
            clipPath(Path().apply { addOval(Rect(cx - skinR, cy - skinR, cx + skinR, cy + skinR)) }) {
                rotate(spin, pivot = Offset(cx, cy)) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(tone.mid, tone.light, tone.dark, tone.mid),
                            center = Offset(cx, cy),
                        ),
                        radius = skinR, center = Offset(cx, cy),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to tone.light, 0.56f to Color.Transparent,
                            center = Offset(cx - r * 0.36f, cy - r * 0.48f), radius = r * 1.1f,
                        ),
                        radius = skinR, center = Offset(cx, cy),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to tone.dark, 0.6f to Color.Transparent,
                            center = Offset(cx + r * 0.48f, cy + r * 0.52f), radius = r * 1.2f,
                        ),
                        radius = skinR, center = Offset(cx, cy),
                    )
                }
                // «дыхание» — лёгкая белёсая вуаль поверх
                drawCircle(Color.White.copy(alpha = bright), radius = skinR, center = Offset(cx, cy))
            }

            // кольцо прогресса — по РАДИУСУ ОРБА, не по размеру канвы
            val stroke = 3.dp.toPx()
            val ringR = r + 1.dp.toPx()
            val d = Size(ringR * 2, ringR * 2)
            val topLeft = Offset(cx - ringR, cy - ringR)
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, topLeft, d, style = Stroke(stroke))
            if (frac != null) {
                drawArc(
                    tone.ring, -90f, 360f * animFrac.coerceIn(0f, 1f), false,
                    topLeft, d, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            } else {
                drawArc(
                    tone.ring, ringSpin, 90f, false,
                    topLeft, d, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        frac?.let {
            BasicText(
                "${(it * 100).toInt()}%",
                style = TextStyle(
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

@Composable
private fun GhostOrb(slot: Int) {
    val c = RipsterTheme.colors
    val scale = 1f - slot * 0.09f
    val d = (ORB * scale).dp
    Box(
        Modifier.offset(x = -slotOffset(slot)).size(ORB.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(d)) {
            drawCircle(c.surface_raised, radius = size.minDimension / 2f)
            drawArc(
                c.border_subtle, 0f, 360f, false,
                Offset(1.5f, 1.5f), Size(size.width - 3f, size.height - 3f),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun OverflowLabel(slot: Int, count: Int) {
    val c = RipsterTheme.colors
    Box(Modifier.offset(x = -slotOffset(slot) - 6.dp), contentAlignment = Alignment.Center) {
        BasicText("+$count", style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    }
}

private fun slotOffset(slot: Int): Dp = (slot * GHOST_GAP).dp

// ── цвет ────────────────────────────────────────────────────────────────

private data class OrbTone(
    val mid: Color, val light: Color, val dark: Color, val ring: Color, val text: Color,
)

/** Средний цвет обложки (Coil → bitmap → усреднение RGB) → набор тонов. */
@Composable
private fun rememberAverageColor(url: String?): Color? {
    val ctx = LocalContext.current
    return produceState<Color?>(initialValue = null, url) {
        value = null
        val u = url?.takeIf { it.startsWith("http") } ?: return@produceState
        value = runCatching {
            val req = ImageRequest.Builder(ctx).data(u).size(24).allowHardware(false).build()
            val res = ctx.imageLoader.execute(req)
            val bmp = (res as? SuccessResult)?.drawable?.toBitmap(24, 24) ?: return@runCatching null
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24 and 0xFF) < 128) continue
                r += (p ushr 16 and 0xFF); g += (p ushr 8 and 0xFF); b += (p and 0xFF); n++
            }
            if (n == 0L) null else Color(r.toInt() / n.toInt(), g.toInt() / n.toInt(), b.toInt() / n.toInt())
        }.getOrNull()
    }.value
}

/** Один цвет → набор производных (калька `mkCol` из dlorb.js). */
private fun deriveTone(base: Color): OrbTone {
    val (h, s0, l0) = rgbToHsl(base)
    val s = s0.coerceIn(0.34f, 0.74f)
    val l = l0.coerceIn(0.44f, 0.66f)
    return OrbTone(
        mid = hsl(h, s, l),
        light = hsl(h, (s + 0.12f).coerceAtMost(0.9f), (l + 0.16f).coerceAtMost(0.82f)),
        dark = hsl(h, s, (l - 0.20f).coerceAtLeast(0.22f)),
        ring = hsl(h, (s + 0.16f).coerceAtMost(0.95f), (l + 0.10f).coerceAtMost(0.78f)),
        text = Color.White,
    )
}

private fun hueTone(seed: String): OrbTone {
    val h = (abs(seed.hashCode()) % 360).toFloat()
    return OrbTone(
        mid = hsl(h, 0.5f, 0.52f),
        light = hsl(h, 0.62f, 0.68f),
        dark = hsl(h, 0.5f, 0.32f),
        ring = hsl(h, 0.62f, 0.62f),
        text = Color.White,
    )
}

private fun rgbToHsl(col: Color): Triple<Float, Float, Float> {
    val r = col.red; val g = col.green; val b = col.blue
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
    val l = (mx + mn) / 2f
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    var h = when (mx) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    h /= 6f
    return Triple(h * 360f, s, l)
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val cc = (1f - abs(2f * l - 1f)) * s
    val x = cc * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - cc / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(cc, x, 0f)
        h < 120f -> Triple(x, cc, 0f)
        h < 180f -> Triple(0f, cc, x)
        h < 240f -> Triple(0f, x, cc)
        h < 300f -> Triple(x, 0f, cc)
        else -> Triple(cc, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}
