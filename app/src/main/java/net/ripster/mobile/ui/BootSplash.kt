package net.ripster.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Стартовая заставка — порт `#boot-splash` из ПК-версии: крутящаяся
 * виниловая пластинка, тонарм опускается один раз, 9-полосная волна пульсирует.
 */
@Composable
fun BootSplash(modifier: Modifier = Modifier) {
    val c = RipsterTheme.colors
    val inf = rememberInfiniteTransition(label = "splash")
    val spin by inf.animateFloat(
        0f, 360f, infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
        label = "vinyl-spin",
    )
    // тонарм: опускается один раз (-32° → -6°) и остаётся
    val arm by animateFloatAsState(targetValue = -6f, label = "arm", animationSpec = tween(1100))

    Box(
        modifier.fillMaxSize().background(c.surface_canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(108.dp).rotate(spin)) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val r = size.minDimension / 2f
                    // винил
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFF2A2A33), 0.26f to Color(0xFF2A2A33),
                            0.27f to Color(0xFF17171C), 1f to Color(0xFF17171C),
                            center = Offset(cx, cy), radius = r,
                        ),
                        radius = r, center = Offset(cx, cy),
                    )
                    // бороздки
                    listOf(0.78f, 0.63f, 0.48f).forEach { f ->
                        drawCircle(Color.White.copy(alpha = 0.06f), radius = r * f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
                    }
                    // этикетка
                    drawCircle(
                        Brush.linearGradient(listOf(c.accent_fill, c.accent_active), start = Offset(0f, 0f), end = Offset(size.width, size.height)),
                        radius = r * 0.32f, center = Offset(cx, cy),
                    )
                    drawCircle(Color(0xFF0E0E12), radius = 2.dp.toPx(), center = Offset(cx, cy))
                }
                // тонарм — опускается один раз, не крутится с пластинкой
                Canvas(Modifier.size(120.dp).rotate(arm)) {
                    drawLine(
                        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.15f))),
                        start = Offset(size.width * 0.92f, size.height * 0.12f),
                        end = Offset(size.width * 0.52f, size.height * 0.5f),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    )
                    drawCircle(Color.White.copy(alpha = 0.5f), radius = 3.5.dp.toPx(), center = Offset(size.width * 0.92f, size.height * 0.12f))
                }
            }

            // 9-полосная волна
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                repeat(9) { i ->
                    val h by inf.animateFloat(
                        5f, 19f,
                        infiniteRepeatable(tween(1000, delayMillis = i * 80, easing = LinearEasing), RepeatMode.Reverse),
                        label = "wave$i",
                    )
                    Box(Modifier.width(3.dp).height(h.dp).background(c.accent_text, RoundedCornerShape(2.dp)))
                }
            }

            BasicText("Ripster", style = TextStyle(color = c.text_primary, fontSize = 19.sp, fontWeight = FontWeight.W800, letterSpacing = 0.4.sp))
        }
    }
}
