package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.theme.RipsterTheme

/** Одна ступень пути. [onClick] == null — текущая (последняя), она не кликается. */
data class Crumb(val label: String, val onClick: (() -> Unit)? = null)

/**
 * Полоска «уровня погружения» над модальными экранами (артист, релиз).
 *
 * Зачем: навигация здесь плоская — вкладка плюс полноэкранные оверлеи, и
 * попав на третий уровень (Поиск → артист → релиз) человек видел только
 * стрелку «назад», без понимания, где он и сколько шагов до вкладки. Полоска
 * показывает и путь, и глубину: сегментов ровно столько, сколько уровней,
 * последний подсвечен. По любому предыдущему сегменту — прыжок сразу на него.
 *
 * Показывать имеет смысл только при [crumbs].size >= 2: на самой вкладке
 * «погружения» ещё нет, и лишняя полоска только съедала бы высоту.
 */
@Composable
fun DepthStrip(crumbs: List<Crumb>, modifier: Modifier = Modifier) {
    if (crumbs.size < 2) return
    val c = RipsterTheme.colors
    Column(modifier.fillMaxWidth()) {
        // Индикатор глубины: сегмент на уровень, активный — акцентом.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            crumbs.forEachIndexed { i, _ ->
                Box(
                    Modifier
                        .width(if (i == crumbs.lastIndex) 22.dp else 12.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (i == crumbs.lastIndex) c.accent_text else c.border_subtle),
                )
            }
        }
        // Сам путь. Длинные названия релизов легко перерастают ширину экрана —
        // ряд прокручивается, а не режется краем.
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { i, crumb ->
                if (i > 0) {
                    BasicText(
                        "›",
                        Modifier.padding(horizontal = 7.dp),
                        style = TextStyle(color = c.text_disabled, fontSize = 13.sp),
                    )
                }
                val last = i == crumbs.lastIndex
                BasicText(
                    crumb.label,
                    modifier = if (!last && crumb.onClick != null) {
                        Modifier.pressable { crumb.onClick.invoke() }.padding(vertical = 2.dp)
                    } else {
                        Modifier.padding(vertical = 2.dp)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = if (last) c.text_primary else c.accent_text,
                        fontSize = 12.sp,
                        fontWeight = if (last) FontWeight.W700 else FontWeight.W500,
                    ),
                )
            }
        }
    }
}
