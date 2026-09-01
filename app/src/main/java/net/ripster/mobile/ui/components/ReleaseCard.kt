package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme
import kotlin.math.abs

/**
 * Единая карточка релиза — одна анатомия для «Новых релизов» на Главной,
 * сетки «Релизы» и Релиз-радара. Взято с ПК-версии (`sc_tab.js` rel-card,
 * `neon-releases-desktop.png`), ужато под мобильный экран:
 *   обложка 1:1 · бейдж сервиса (сл.-верх) · тип ALBUM/SINGLE/EP (спр.-верх) ·
 *   play по центру · NEW (сл.-низ) → тайтл → «артист · N трек.» → лейбл/дата.
 * Тап по телу — открыть альбом; play — слушать; ↓ — скачать.
 */
data class ReleaseCardData(
    val title: String,
    val artist: String,
    val service: String,
    val url: String,
    val type: String = "",          // album | single | ep | mix | compilation
    val coverUrl: String? = null,
    val trackCount: Int? = null,
    val label: String? = null,
    val dateText: String? = null,
    val isNew: Boolean = false,
    val hires: Boolean = false,
)

private fun typeLabelKey(t: String) = when (t.lowercase()) {
    "single" -> "rc.type_single"
    "ep" -> "rc.type_ep"
    "mix" -> "rc.type_mix"
    "compilation" -> "rc.type_comp"
    else -> "rc.type_album"
}

@Composable
fun ReleaseCard(
    data: ReleaseCardData,
    modifier: Modifier = Modifier,
    queued: Boolean = false,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onArtist: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    val fallback = remember(data.title) {
        // радиальный неон из хэша — как заглушки-обложки на рендере
        val h = abs((data.title + data.artist).hashCode())
        val hues = listOf(
            Color(0xFFFF4D8F), Color(0xFFA238FF), Color(0xFF3A5FD9),
            Color(0xFF1ECBE1), Color(0xFFFF5C3C), Color(0xFF38E0A0),
        )
        val hue = hues[h % hues.size]
        Brush.linearGradient(listOf(hue.copy(alpha = 0.70f), Color(0xFF17121C)))
    }

    Column(modifier.clickable { onOpen() }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(fallback),
        ) {
            Cover(url = data.coverUrl, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp))

            // сервис
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(c.accent_fill))
                BasicText(data.service, style = TextStyle(color = Color.White, fontSize = 10.sp))
            }
            // тип
            if (data.type.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BasicText(
                        tr(typeLabelKey(data.type), lang).uppercase(),
                        style = TextStyle(color = Color.White.copy(alpha = 0.86f), fontSize = 9.sp, letterSpacing = 0.6.sp),
                    )
                }
            }
            // Центр обложки — ▶ слушать потоком (как на ПК-карточке). Если
            // воспроизведение не подключено (onPlay == null) — только «открыть».
            if (onPlay != null) {
                Box(
                    Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.44f))
                        .pressable { onPlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("▶", style = TextStyle(color = Color.White, fontSize = 16.sp))
                }
            }
            // NEW / скачать
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (data.isNew) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.success_text).padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        BasicText(tr("rl.new_badge", lang), style = TextStyle(color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                    }
                }
                if (data.hires) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFFFD60A).copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        BasicText("HI-RES", style = TextStyle(color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).clip(CircleShape)
                    .background(if (queued) c.surface_active else c.accent_fill)
                    .pressable(enabled = !queued) { onDownload() },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(if (queued) "✓" else "↓", style = TextStyle(color = if (queued) c.success_text else c.text_on_fill, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicText(data.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_primary, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        BasicText(
            buildString {
                append(data.artist)
                data.trackCount?.let { append("  ·  ").append(it).append(" ").append(tr("search.tracks_short", lang)) }
            },
            modifier = if (onArtist != null) Modifier.clickable { onArtist() } else Modifier,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (onArtist != null) c.accent_text else c.text_tertiary, fontSize = 11.sp),
        )
        val sub = listOfNotNull(data.label?.takeIf { it.isNotBlank() }, data.dateText?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
        if (sub.isNotBlank()) {
            BasicText(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = c.text_disabled, fontSize = 10.sp))
        }
    }
}
