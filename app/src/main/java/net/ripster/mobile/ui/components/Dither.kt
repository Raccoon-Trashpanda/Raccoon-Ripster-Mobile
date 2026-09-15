package net.ripster.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Тонкая шумовая плёнка, которой накрывают тёмные градиенты, чтобы 8-битные
 * «кольца» (бандинг) рассыпались в незаметное зерно. Рисовать поверх фона с
 * очень низкой альфой (~0.03). Бросовый 64×64 бит тайлится шейдером — дёшево,
 * без RenderEffect (он есть только с Android 12).
 */
@Composable
fun rememberDitherBrush(): ShaderBrush {
    val bmp = remember {
        val n = 64
        val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(0x5EED)
        val px = IntArray(n * n) {
            val v = rnd.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        b.setPixels(px, 0, n, 0, 0, n, n)
        b.asImageBitmap()
    }
    return remember(bmp) { ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated)) }
}
