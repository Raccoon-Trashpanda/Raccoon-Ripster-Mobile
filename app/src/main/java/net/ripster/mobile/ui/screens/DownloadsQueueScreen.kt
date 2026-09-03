package net.ripster.mobile.ui.screens


import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.components.RipsterHairline
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Weights

/**
 * Экран очереди загрузок.
 *
 * Честность важнее красоты: в проекте уже есть язык пунктира/сплошной
 * обводки/заливки, определённый в Badges.kt для QualityBadge — этот экран
 * его не переизобретает, а переиспользует тот же контракт, применённый к
 * статусу задачи закачки, а не к оценке качества трека:
 *
 * - [DownloadTaskStatus.Queued] — пунктирная обводка, третичный цвет.
 *   Ровно как QualityBadgeState.NotMeasured: пунктир значит «данных ещё
 *   нет, ничего не происходит».
 * - [DownloadTaskStatus.Downloading] — сплошная обводка + дуга. Известный
 *   progress рисует честный sweep angle; неизвестный — дуга крутится
 *   бесконечно. Выдуманный процент здесь был бы ровно тем враньём, против
 *   которого QualityBadge и был сделан — не подставляем его и тут.
 * - [DownloadTaskStatus.Done] — БЕЗ праздничного цвета, БЕЗ заливки:
 *   обычная галочка, обычный вес текста. Прямое отражение принципа
 *   QualityBadgeState.Match — «совпадение не получает награды»: успешная
 *   штатная загрузка — это норма, а не достижение, и раскрашивать норму
 *   значит неизбежно раздувать её планку.
 * - [DownloadTaskStatus.Failed] — единственное состояние со сплошной
 *   заливкой (danger_fill), ровно как QualityBadgeState.Fake — единственный
 *   бейдж с полной заливкой фона, зарезервированной для самого серьёзного
 *   случая. Рядом всегда причина (errorReason) и кнопка повтора: красная
 *   строка без объяснения была бы так же нечестна, как пустой бейдж.
 */

enum class DownloadTaskStatus { Queued, Downloading, Done, Failed }

data class DownloadTask(
    val id: String,
    val title: String,
    val artist: String,
    val status: DownloadTaskStatus,
    /** 0f..1f; null = доля неизвестна во время Downloading. Игнорируется для прочих статусов. */
    val progress: Float? = null,
    /** Имеет смысл только при status == Failed — короткая реальная причина. */
    val errorReason: String? = null,
)

@Composable
fun DownloadsQueueScreen(
    tasks: List<DownloadTask>,
    onRetry: (DownloadTask) -> Unit,
    onCancel: (DownloadTask) -> Unit,
    modifier: Modifier = Modifier,
    onClearFinished: () -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = LocalAppLang.current

    val hasFinished = tasks.any { it.status == DownloadTaskStatus.Done || it.status == DownloadTaskStatus.Failed }
    val hasActive = tasks.any { it.status == DownloadTaskStatus.Queued || it.status == DownloadTaskStatus.Downloading }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface_canvas)
            .padding(horizontal = spacing.gutter),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = spacing.lg, bottom = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = tr("nav.downloads", lang),
                style = TextStyle(color = colors.text_primary, fontSize = type.title, fontWeight = Weights.Primary),
            )
            Spacer(Modifier.weight(1f))
            if (hasFinished) {
                BasicText(
                    tr("dl.clear_done", lang),
                    Modifier.clickable(onClick = onClearFinished).padding(horizontal = 6.dp, vertical = 4.dp),
                    style = TextStyle(color = colors.text_tertiary, fontSize = type.label, fontWeight = FontWeight.W600),
                )
            }
            if (hasActive || hasFinished) {
                Spacer(Modifier.width(spacing.sm))
                BasicText(
                    tr("dl.clear_all", lang),
                    Modifier.clickable(onClick = onClearAll).padding(horizontal = 6.dp, vertical = 4.dp),
                    style = TextStyle(color = colors.danger_text, fontSize = type.label, fontWeight = FontWeight.W600),
                )
            }
        }

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = tr("dl.empty", lang),
                    style = TextStyle(color = colors.text_secondary, fontSize = type.body),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(tasks, key = { it.id }) { task ->
                    DownloadTaskRow(task = task, onRetry = { onRetry(task) }, onCancel = { onCancel(task) })
                    RipsterHairline()
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = LocalAppLang.current

    // Действие (Retry / Cancel) вынесено на СВОЮ строку под заголовком, а не в хвост
    // общей строки. Причина — жалоба 03.09.2026 (видео, экран 370dp): «Cancel»/«Retry»
    // не помещались справа от статус-пилюли и уезжали за край экрана. В Compose Row
    // невзвешенный хвост (пилюля + текст кнопки) при нехватке ширины НЕ ужимается —
    // взвешенная колонка слева схлопывается в 0, а хвост всё равно переполняет строку
    // и клипается по краю. Отдельная нижняя строка выравнивается вправо и физически
    // не может обрезаться.
    val hasAction = task.status == DownloadTaskStatus.Failed ||
        task.status == DownloadTaskStatus.Queued ||
        task.status == DownloadTaskStatus.Downloading

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = task.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text_primary, fontSize = type.body),
                )
                BasicText(
                    text = task.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text_secondary, fontSize = type.caption),
                )
                if (task.status == DownloadTaskStatus.Failed && task.errorReason != null) {
                    BasicText(
                        text = task.errorReason,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = colors.danger_text, fontSize = type.caption),
                    )
                }
                if (task.status == DownloadTaskStatus.Downloading && task.progress != null) {
                    BasicText(
                        text = "${(task.progress * 100).toInt()}%",
                        style = TextStyle(color = colors.text_tertiary, fontSize = type.caption, fontFamily = FontFamily.Monospace),
                    )
                }
            }

            Spacer(Modifier.width(spacing.sm))

            DownloadStatusIndicator(status = task.status, progress = task.progress)
        }

        if (hasAction) {
            Spacer(Modifier.height(spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (task.status == DownloadTaskStatus.Failed) {
                    BasicText(
                        text = tr("dl.retry", lang),
                        modifier = Modifier
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        style = TextStyle(color = colors.accent_text, fontSize = type.label, fontWeight = FontWeight.W600),
                    )
                }
                // Отменить доступно, пока задача ещё не завершена (Queued/Downloading) —
                // отменять готовую или уже упавшую загрузку нечего.
                if (task.status == DownloadTaskStatus.Queued || task.status == DownloadTaskStatus.Downloading) {
                    BasicText(
                        text = tr("dl.cancel", lang),
                        modifier = Modifier
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        style = TextStyle(color = colors.text_tertiary, fontSize = type.label, fontWeight = FontWeight.W500),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadStatusIndicator(status: DownloadTaskStatus, progress: Float?) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type
    val lang = LocalAppLang.current
    val textStyle = TextStyle(fontSize = type.badge, fontWeight = FontWeight.W500)
    val pad = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)

    when (status) {
        DownloadTaskStatus.Queued -> {
            val gray = colors.text_tertiary
            Row(
                modifier = Modifier.dashedOutline(gray).then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                BasicText(tr("dl.queued", lang), style = textStyle.copy(color = gray))
            }
        }

        DownloadTaskStatus.Downloading -> {
            val gray = colors.text_tertiary
            Row(
                modifier = Modifier.solidOutline(gray).then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                DownloadingArc(color = gray, progress = progress)
                BasicText(tr("dl.downloading", lang), style = textStyle.copy(color = gray))
            }
        }

        DownloadTaskStatus.Done -> {
            Row(
                modifier = Modifier.then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                DoneCheckGlyph(color = colors.text_primary)
                BasicText(tr("dl.done", lang), style = textStyle.copy(color = colors.text_primary, fontWeight = FontWeight.W400))
            }
        }

        DownloadTaskStatus.Failed -> {
            Row(
                modifier = Modifier
                    .background(colors.danger_fill, androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .then(pad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(tr("dl.error", lang), style = textStyle.copy(color = colors.text_on_fill, fontWeight = FontWeight.W700))
            }
        }
    }
}

/** Пунктир — та же плотность узора, что у NotMeasured в Badges.kt: «данных ещё нет». */
private fun Modifier.dashedOutline(color: Color): Modifier = drawBehind {
    val w = 1.dp.toPx()
    val r = size.height / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()), 0f)),
    )
}

private fun Modifier.solidOutline(color: Color): Modifier = drawBehind {
    val w = 1.dp.toPx()
    val r = size.height / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = w),
    )
}

@Composable
private fun DownloadingArc(color: Color, progress: Float?) {
    val transition = rememberInfiniteTransition(label = "download-arc")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "download-arc-angle",
    )
    Canvas(modifier = Modifier.size(12.dp)) {
        val w = 1.5.dp.toPx()
        val inset = w / 2f
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(
            color = color.copy(alpha = 0.35f),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
        val sweep = if (progress != null) 360f * progress.coerceIn(0f, 1f) else 90f
        val start = if (progress != null) -90f else spin
        drawArc(
            color = color,
            startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun DoneCheckGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val w = 1.5.dp.toPx()
        val s = size.width
        drawLine(color, Offset(s * 0.14f, s * 0.55f), Offset(s * 0.40f, s * 0.82f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.40f, s * 0.82f), Offset(s * 0.88f, s * 0.20f), w, StrokeCap.Round)
    }
}
