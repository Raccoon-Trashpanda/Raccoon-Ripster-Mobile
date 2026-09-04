package net.ripster.mobile.ui.screens

import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.components.RipsterHairline
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.Radii
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.BorderWidth

/**
 * Экран «Библиотека» — список уже скачанных на устройство альбомов/треков
 * с строкой поиска/фильтра сверху.
 *
 * В область ответственности этого экрана НЕ входит сама загрузка или
 * управление файлами — только просмотр и поиск по уже скачанному контенту.
 *
 * Следует общей конвенции проекта: используется BasicText / BasicTextField
 * (см. NowPlayingScreen.kt, Badges.kt), а не компоненты из Material —
 * дизайн-система здесь полностью самописная (see RipsterTheme).
 */

data class LibraryItem(
    val id: String,
    val title: String,
    val artist: String,
    val format: String, // напр. "FLAC · 24-bit/96kHz" — отображается моноширинным шрифтом
    val trackCount: Int,
    val artworkUrl: String? = null,
)

@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onItemClick: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    // Заголовок отдаёт настройки БЕЗ добавления пятого пункта в BottomNav —
    // тот зафиксирован ровно на 4 назначениях (см. BottomNav.kt). Шестерёнка
    // необязательна (default = {}) — существующие вызовы этого экрана,
    // включая витрину в MainActivity, продолжают собираться без изменений.
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    // Релизами или отдельными треками. По умолчанию релизами: скачивают
    // альбом, а не россыпь, и слушать его хотят целиком.
    byAlbum: Boolean = true,
    onModeChange: (Boolean) -> Unit = {},
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = LocalAppLang.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface_canvas)
            .padding(horizontal = spacing.gutter),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.lg, bottom = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = tr("nav.library", lang),
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = colors.text_primary,
                    fontSize = type.title,
                ),
            )
            // Шестерёнку убрали — настройки открываются прямоугольной кнопкой
            // в общей шапке приложения (AppShell). Дубля больше нет.
        }

        LibrarySearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier.padding(bottom = spacing.md),
        )

        if (items.isEmpty()) {
            EmptyLibraryMessage(
                searchQuery = searchQuery,
                onOpenSearch = onOpenSearch,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Row(
            Modifier.fillMaxWidth().padding(bottom = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            LibModeChip(tr("lib.by_album", lang), byAlbum) { onModeChange(true) }
            LibModeChip(tr("lib.by_track", lang), !byAlbum) { onModeChange(false) }
        }
        LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    LibraryItemRow(
                        item = item,
                        onClick = { onItemClick(item) },
                    )
                    RipsterHairline()
                }
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = LocalAppLang.current

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor = if (isFocused) colors.focus_ring else colors.border_subtle

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Radii.RCtl))
            .background(colors.surface_sunken)
            .border(BorderWidth, borderColor, RoundedCornerShape(Radii.RCtl))
            .padding(horizontal = spacing.controlPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) {
            BasicText(
                text = tr("lib.search", lang),
                style = TextStyle(
                    color = colors.text_tertiary,
                    fontSize = type.body,
                ),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            interactionSource = interactionSource,
            textStyle = TextStyle(
                color = colors.text_primary,
                fontSize = type.body,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent_fill),
        )
    }
}

@Composable
private fun LibraryItemRow(
    item: LibraryItem,
    onClick: () -> Unit,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        net.ripster.mobile.ui.components.Cover(
            url = item.artworkUrl,
            modifier = Modifier
                .size(48.dp)
                .border(BorderWidth, colors.border_subtle, RoundedCornerShape(Radii.RCard)),
            shape = RoundedCornerShape(Radii.RCard),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = spacing.xs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = colors.text_primary,
                    fontSize = type.body,
                ),
            )
            BasicText(
                text = item.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = colors.text_secondary,
                    fontSize = type.caption,
                ),
            )
            BasicText(
                text = item.format,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = colors.text_tertiary,
                    fontSize = type.caption,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        if (item.trackCount > 1) TrackCountBadge(count = item.trackCount)
    }
}

@Composable
private fun TrackCountBadge(count: Int) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = LocalAppLang.current

    Box(
        modifier = Modifier
            .widthIn(min = 0.dp)
            .clip(RoundedCornerShape(Radii.RPill))
            .background(colors.surface_sunken)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "$count " + trackWord(count, lang),
            maxLines = 1,
            style = TextStyle(
                color = colors.text_secondary,
                fontSize = type.caption,
            ),
        )
    }
}

/**
 * Русское согласование числительного с «трек». Показывается только для
 * контейнеров (count > 1); одиночные треки бейдж не рисуют. Полная i18n
 * множественного числа — когда в библиотеку попадут альбомы.
 */
private fun trackWord(count: Int, lang: AppLang): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val key = when {
        mod100 in 11..14 -> "lib.tw_many"
        mod10 == 1 -> "lib.tw_one"
        mod10 in 2..4 -> "lib.tw_few"
        else -> "lib.tw_many"
    }
    return tr(key, lang)
}

@Composable
private fun EmptyLibraryMessage(
    searchQuery: String,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipsterTheme.colors
    val type = RipsterTheme.type
    val lang = LocalAppLang.current

    val message = if (searchQuery.isEmpty()) {
        tr("lib.empty", lang)
    } else {
        tr("lib.not_found", lang)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = message,
                style = TextStyle(color = colors.text_secondary, fontSize = type.body),
            )
            // Явный вход в поиск — тестер не понял, «где скачивать».
            if (searchQuery.isEmpty()) {
                Spacer(Modifier.size(16.dp))
                Row(
                    modifier = Modifier
                        .background(colors.accent_fill, RoundedCornerShape(999.dp))
                        .clickable { onOpenSearch() }
                        .padding(horizontal = 22.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText("🔍", style = TextStyle(fontSize = 15.sp))
                    BasicText(
                        tr("lib.find_music", lang),
                        style = TextStyle(color = colors.text_on_fill, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/**
 * Точка входа в настройки. Живёт здесь, а не пятым пунктом BottomNav —
 * см. комментарий у onOpenSettings в LibraryScreen(). Шестерёнка нарисована
 * Canvas-примитивом (обод + зубцы + центр), тем же приёмом, что и остальная
 * иконография проекта (Transport.kt, BottomNav.kt) — без иконочной библиотеки.
 */
@Composable
private fun SettingsGearButton(onClick: () -> Unit) {
    val colors = RipsterTheme.colors
    Box(
        modifier = Modifier
            .size(MinTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "settings" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            drawGearGlyph(colors.text_secondary)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGearGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    val outerR = w * 0.42f
    val innerR = w * 0.16f
    val toothLen = w * 0.14f
    val strokeW = w * 0.09f

    drawCircle(color = color, radius = outerR, center = center, style = Stroke(width = strokeW))
    drawCircle(color = color, radius = innerR, center = center)

    // Восемь зубцов — короткие радиальные штрихи наружу от обода.
    val teeth = 8
    for (i in 0 until teeth) {
        val angle = (2 * Math.PI * i / teeth)
        val cos = kotlin.math.cos(angle).toFloat()
        val sin = kotlin.math.sin(angle).toFloat()
        val start = Offset(center.x + cos * outerR, center.y + sin * outerR)
        val end = Offset(center.x + cos * (outerR + toothLen), center.y + sin * (outerR + toothLen))
        drawLine(color, start, end, strokeW, StrokeCap.Round)
    }
}


/** Переключатель «релизы / треки» — те же правила, что у чипов фильтра поиска. */
@Composable
private fun LibModeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = RipsterTheme.colors
    val t = RipsterTheme.type
    Box(
        Modifier
            .clip(RoundedCornerShape(Radii.RCtl))
            .background(if (on) c.surface_active else c.surface_sunken)
            .border(BorderWidth, if (on) c.accent_text else c.border_subtle,
                    RoundedCornerShape(Radii.RCtl))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (on) c.accent_text else c.text_secondary,
                fontSize = t.caption,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}
