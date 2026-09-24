package net.ripster.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.premium.MiniCoverRect
import net.ripster.mobile.ui.premium.PremiumPlayerFx
import net.ripster.mobile.ui.premium.liquidGlass
import net.ripster.mobile.ui.premium.premiumCoverBounds
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr

/**
 * Мини-плеер — компактная плашка "сейчас играет", закреплённая над нижней
 * навигацией на всех экранах, кроме полноэкранного плеера.
 *
 * Органы управления как в ПК-версии, но только основные: назад / пауза /
 * вперёд / закрыть (×). Тап по обложке+тексту раскрывает полный плеер.
 * Название и исполнитель, если не влезают, плавно проматываются
 * (`basicMarquee`) — с паузой в начале, чтобы успеть прочитать.
 */
data class MiniPlayerState(
    val title: String,
    val artist: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    /** Идёт подготовка/буферизация — кнопка показывает спиннер и не реагирует. */
    val loading: Boolean = false,
    val artworkUrl: String? = null,
    /** Строка формата из плеера (кодек/битрейт); пусто — не показываем. */
    val format: String = "",
)

@Composable
fun MiniPlayer(
    state: MiniPlayerState,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    fx: PremiumPlayerFx,
    /**
     * Куда записать прямоугольник обложки — чтобы полный плеер мог выехать из
     * него (`null` → не записываем и ничего не меняем).
     */
    coverRect: net.ripster.mobile.ui.premium.MiniCoverRect? = null,
    modifier: Modifier = Modifier,
    /**
     * Однострочный режим для ландшафта (см. ui/layout/ChromeBudget): в горизонтальном
     * окне каждая вертикальная строка стоит строк выдачи. Обложка 40dp, «название ·
     * исполнитель» одной строкой, управление то же — минус вторая строка и минус
     * половина отступов. Портрет вызывает плеер без этого флага и не меняется.
     */
    compact: Boolean = false,
) {
    // Подписи для скринридера тоже на языке приложения.
    val lang = LocalAppLang.current
    val colors = RipsterTheme.colors
    val type = RipsterTheme.type
    val glassShape = RoundedCornerShape(16.dp)

    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp)
            .clip(glassShape)
            // Плашка — тот же орган-стекло, что и кнопки плеера: тон из-под
            // ambilight, размытие, блик и кромка. Без режима — глухая
            // surface_raised с рамкой темы, ровно какой была до «дорогих» окон.
            .liquidGlass(fx, glassShape, colors.surface_canvas, fallback = colors.surface_raised)
            .then(if (fx.hasGlass) Modifier else Modifier.border(1.dp, colors.border_subtle, glassShape)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp)
                .height(if (compact) 48.dp else 58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onExpand,
                    )
                    .semantics { contentDescription = tr("a11y.open_player", lang, state.title, state.artist) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(
                    url = state.artworkUrl,
                    modifier = Modifier.size(if (compact) 40.dp else 38.dp).premiumCoverBounds(coverRect),
                    shape = RoundedCornerShape(9.dp),
                )
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (compact) {
                        // Одна строка вместо двух: «название · исполнитель»,
                        // переполнение уводит бегущей строкой, как и выше.
                        BasicText(
                            text = if (state.artist.isBlank()) state.title else "${state.title} · ${state.artist}",
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1400,
                                repeatDelayMillis = 1600,
                            ),
                            style = TextStyle(color = colors.text_primary, fontSize = type.body),
                        )
                    } else {
                    BasicText(
                        text = state.title,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1400,
                            repeatDelayMillis = 1600,
                        ),
                        style = TextStyle(color = colors.text_primary, fontSize = type.body),
                    )
                    // Формат донесён до строки «кто играет», но тише исполнителя:
                    // это прибор, а не крик — как и договорились с ресонадой.
                    val artistLine = if (state.format.isBlank()) {
                        androidx.compose.ui.text.AnnotatedString(state.artist)
                    } else {
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = colors.text_secondary)) {
                                append(state.artist)
                            }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = colors.text_tertiary)) {
                                append("  ·  ")
                                append(state.format)
                            }
                        }
                    }
                    BasicText(
                        text = artistLine,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1800,
                            repeatDelayMillis = 1600,
                        ),
                        style = TextStyle(color = colors.text_secondary, fontSize = type.caption),
                    )
                    }
                }
            }

            Spacer(Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MiniGlyph(onClick = onPrev, cd = tr("a11y.prev", lang)) { drawPrevGlyph(colors.text_secondary) }
                PlayPauseButton(isPlaying = state.isPlaying, onClick = onPlayPause, size = 34.dp,
                    loading = state.loading)
                MiniGlyph(onClick = onNext, cd = tr("a11y.next", lang)) { drawNextGlyph(colors.text_secondary) }
                MiniGlyph(onClick = onClose, cd = tr("a11y.close_player", lang), icon = 12.dp) {
                    drawLine(colors.text_tertiary, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.15f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.85f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(colors.text_tertiary, androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.15f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.85f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.surface_sunken),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction = progress).height(2.dp).background(colors.accent_fill),
            )
        }
    }
}

@Composable
private fun MiniGlyph(
    onClick: () -> Unit,
    cd: String,
    icon: androidx.compose.ui.unit.Dp = 16.dp,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(icon)) { draw() }
    }
}
