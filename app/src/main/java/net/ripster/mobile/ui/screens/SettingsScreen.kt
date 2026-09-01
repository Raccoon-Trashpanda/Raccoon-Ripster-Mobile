package net.ripster.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.components.ButtonLevel
import net.ripster.mobile.ui.components.RipsterButton
import net.ripster.mobile.ui.components.RipsterCard
import net.ripster.mobile.ui.components.RipsterHairline
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Weights
import kotlin.math.roundToInt

/**
 * Экран настроек: два реальных раздела — «Качество» (формат/битность
 * загрузок) и «Хранилище» (куда физически пишутся файлы и сколько места
 * занято).
 *
 * Про радио-кнопки качества: в Radii.kt прямо зафиксировано, что круглая
 * форма — не общий токен радиуса, а именованное исключение из трёх
 * скруглений во всём продукте, и настройки — один из легитимных случаев:
 * «ручка перемотки, точки очереди, radio в настройках». Круглый индикатор
 * здесь — осознанное, задокументированное решение, а не отступление от
 * системы.
 *
 * Сам индикатор нарисован вручную через Canvas/drawCircle, а не через
 * Material RadioButton: в этом кодбейзе нет зависимости на material/
 * material3, все подобные примитивы (бейджи, иконки транспорта) уже
 * рисуются руками — radio-точка сделана по тому же принципу.
 */

enum class DownloadQuality(val label: String, val detail: String) {
    Lossy("MP3 320", "Компромисс по размеру, минимум места"),
    Standard("FLAC 16-bit/44.1kHz", "CD-качество, стандарт по умолчанию"),
    HiRes("FLAC 24-bit/до 192kHz", "Максимум, где сервис его реально отдаёт"),
}

data class StorageInfo(
    val downloadsPath: String,
    val usedBytes: Long,
    val availableBytes: Long,
)

@Composable
fun SettingsScreen(
    selectedQuality: DownloadQuality,
    onQualityChange: (DownloadQuality) -> Unit,
    storage: StorageInfo,
    onOpenDownloadsFolder: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface_canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.gutter),
    ) {
        BasicText(
            text = "Настройки",
            modifier = Modifier.padding(top = spacing.lg, bottom = spacing.lg),
            style = TextStyle(color = colors.text_primary, fontSize = type.title, fontWeight = Weights.Primary),
        )

        SectionHeader("Качество")
        Spacer(Modifier.height(spacing.sm))
        RipsterCard {
            DownloadQuality.entries.forEachIndexed { index, quality ->
                QualityRow(
                    quality = quality,
                    selected = quality == selectedQuality,
                    onClick = { onQualityChange(quality) },
                )
                if (index != DownloadQuality.entries.lastIndex) {
                    RipsterHairline(inset = 0.dp)
                }
            }
        }

        Spacer(Modifier.height(spacing.xl))

        SectionHeader("Хранилище")
        Spacer(Modifier.height(spacing.sm))
        RipsterCard {
            BasicText(
                text = storage.downloadsPath,
                style = TextStyle(color = colors.text_tertiary, fontSize = type.caption, fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(spacing.md))

            val total = storage.usedBytes + storage.availableBytes
            val usedFraction = if (total > 0) (storage.usedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(colors.surface_sunken, net.ripster.mobile.ui.theme.Radii.CtlShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = usedFraction)
                        .height(6.dp)
                        .background(colors.accent_fill, net.ripster.mobile.ui.theme.Radii.CtlShape),
                )
            }
            Spacer(Modifier.height(spacing.sm))
            BasicText(
                text = "Занято ${formatBytes(storage.usedBytes)} · свободно ${formatBytes(storage.availableBytes)}",
                style = TextStyle(color = colors.text_secondary, fontSize = type.caption),
            )

            Spacer(Modifier.height(spacing.lg))

            RipsterButton(
                text = "Открыть папку загрузок",
                onClick = onOpenDownloadsFolder,
                level = ButtonLevel.Standard,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            RipsterButton(
                text = "Очистить кэш",
                onClick = onClearCache,
                level = ButtonLevel.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(spacing.xl))
    }
}

@Composable
private fun SectionHeader(text: String) {
    val colors = RipsterTheme.colors
    val type = RipsterTheme.type
    BasicText(
        text = text,
        style = TextStyle(color = colors.text_secondary, fontSize = type.label, fontWeight = FontWeight.W700),
    )
}

@Composable
private fun QualityRow(
    quality: DownloadQuality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = "${quality.label}, ${quality.detail}" }
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected = selected)
        Spacer(Modifier.width(spacing.md))
        Column {
            BasicText(text = quality.label, style = TextStyle(color = colors.text_primary, fontSize = type.body))
            BasicText(text = quality.detail, style = TextStyle(color = colors.text_secondary, fontSize = type.caption))
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val colors = RipsterTheme.colors
    val ringColor = if (selected) colors.accent_text else colors.border_default
    val fillColor = colors.accent_text
    Canvas(modifier = Modifier.size(18.dp)) {
        val strokeW = 1.5.dp.toPx()
        drawCircle(
            color = ringColor,
            radius = (size.minDimension - strokeW) / 2f,
            style = Stroke(width = strokeW),
        )
        if (selected) {
            drawCircle(color = fillColor, radius = size.minDimension * 0.28f)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000.0) {
        "${roundTo1(mb / 1000.0)} ГБ"
    } else {
        "${roundTo1(mb)} МБ"
    }
}

private fun roundTo1(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return rounded.toString()
}
