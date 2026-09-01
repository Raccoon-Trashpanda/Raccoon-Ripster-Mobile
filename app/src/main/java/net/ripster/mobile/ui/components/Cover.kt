package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Единый рендер обложки: сеть через Coil, честная нейтральная плашка, пока
 * нет картинки или пока грузится. Одно место — один вид во всех списках и
 * в плеере (правило десктопа про размеры обложек здесь тоже держим:
 * мелкое в сетках, крупный источник в плеере — задаётся размером Modifier).
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
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
