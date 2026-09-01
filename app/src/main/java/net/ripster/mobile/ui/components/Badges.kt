package net.ripster.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ripster.mobile.ui.theme.Radii
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Weights

/**
 * Бейдж качества. Контракт честности целиком, включая то, чего бейдж НЕ делает.
 *
 * Разбор конкурента 23.08.2026: золотой Hi-Res над треком, который их же
 * анализатор оценил в 14/100 с пометкой «Upscaled»; «Supported: 48k» рядом с
 * «Using: 96 kHz»; зелёное bit-perfect над цепочкой с активным ресемплером.
 * Каждое утверждение формально верно в своей области и лживо в чтении.
 */
sealed interface QualityBadgeState {

    /** Файл не измеряли. Пунктирный контур 2/5, серый: пунктир и есть «данных нет». */
    data object NotMeasured : QualityBadgeState

    /**
     * Идёт измерение. Сплошной контур + дуга.
     * progress == null означает «идёт, доля неизвестна» — тогда дуга крутится.
     * Выдуманный процент здесь был бы ровно тем враньём, против которого этот
     * бейдж и сделан, поэтому неизвестность показывается как неизвестность.
     */
    data class Measuring(val progress: Float? = null) : QualityBadgeState

    /** Измерено, обещанное совпало с фактом. Обычный текст с галочкой, БЕЗ ЦВЕТА. */
    data object Match : QualityBadgeState

    /**
     * Расхождение: обещано одно, отдано другое. Роль внимания.
     * Уровень утверждения — «источник»: только тот, кто добывает, вправе
     * сказать, что именно было обещано.
     */
    data class Mismatch(val promised: String, val actual: String) : QualityBadgeState

    /** Подделка: контейнер без сигнала. Роль опасности. */
    data class Fake(val label: String) : QualityBadgeState
}

/**
 * Уверенность обязательна. Слабое основание не даёт сильного знака.
 *
 * Practical следствие ниже в коде: при Possible роль опасности понижается до
 * внимания. Иначе догадка выглядит как приговор, а один ложный «поддельный
 * FLAC» стоит доверия ко всем остальным бейджам сразу.
 */
enum class Confidence {
    Confirmed,
    Probable,
    Possible,
}

@Composable
fun QualityBadge(
    state: QualityBadgeState,
    modifier: Modifier = Modifier,
    confidence: Confidence = Confidence.Confirmed,
    /** Подписи — параметры, а не литералы: локализация живёт в ресурсах, не здесь. */
    notMeasuredLabel: String = "not measured",
    measuringLabel: String = "measuring",
    matchLabel: String = "as promised",
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    // Радиус-капсула законна здесь и только здесь: бейдж неинтерактивен.
    // Если бейджу когда-нибудь добавят onClick — он обязан перейти на RCtl,
    // иначе статус станет неотличим от действия.
    val shape = Radii.PillShape

    val textStyle = TextStyle(
        fontSize = type.badge,
        fontWeight = Weights.Standard,
    )

    val pad = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)

    when (state) {
        QualityBadgeState.NotMeasured -> {
            val gray = colors.text_tertiary
            Row(
                modifier = modifier
                    .dashedOutline(gray)
                    .then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                BasicText(notMeasuredLabel, style = textStyle.copy(color = gray, fontWeight = Weights.Quiet))
            }
        }

        is QualityBadgeState.Measuring -> {
            val gray = colors.text_tertiary
            Row(
                modifier = modifier
                    .solidOutline(gray)
                    .then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MeasuringArc(color = gray, progress = state.progress)
                BasicText(measuringLabel, style = textStyle.copy(color = gray, fontWeight = Weights.Quiet))
            }
        }

        QualityBadgeState.Match -> {
            /*
             * СОВПАДЕНИЕ НЕ ПОЛУЧАЕТ НАГРАДЫ. Это принципиально, а не экономия.
             *
             * Золотой знак за норму превращает норму в достижение. Дальше
             * работает не эстетика, а стимул: если знак — награда, его выгодно
             * выдавать пошире, порог выдачи ползёт вниз, и ровно так появляется
             * Hi-Res над апскейлом. Награда за норму НЕИЗБЕЖНО приводит к
             * инфляции нормы.
             *
             * Поэтому здесь: цвет обычного текста, обычный вес, никакой заливки,
             * никакой рамки, никакого золота. Окрашивается только отклонение —
             * то есть цвет в этом бейдже означает ровно «что-то не так».
             * Галочка нужна лишь чтобы отличить «совпало» от «не измеряли»:
             * это разные утверждения, и молчание не должно читаться как успех.
             */
            Row(
                modifier = modifier.then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                CheckGlyph(color = colors.text_primary)
                BasicText(matchLabel, style = textStyle.copy(color = colors.text_primary, fontWeight = Weights.Body))
            }
        }

        is QualityBadgeState.Mismatch -> {
            val warn = colors.warning_text
            Row(
                modifier = modifier
                    .solidOutline(warn)
                    .then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                BasicText(state.promised, style = textStyle.copy(color = warn))
                // Стрелка нарисована, а не набрана символом: это признак,
                // который обязан читаться в чёрно-белом и не зависеть от того,
                // есть ли глиф в системном шрифте.
                ArrowGlyph(color = warn)
                BasicText(state.actual, style = textStyle.copy(color = warn, fontWeight = FontWeight.W700))
            }
        }

        is QualityBadgeState.Fake -> {
            // Понижение по уверенности: при «возможно» максимум внимание.
            val strong = confidence != Confidence.Possible
            val fill = if (strong) colors.danger_fill else Color.Transparent
            val ink = if (strong) colors.text_on_fill else colors.warning_text
            Row(
                modifier = modifier
                    .then(
                        if (strong) Modifier.background(fill, shape)
                        // Сплошная заливка — единственная у бейджей, и это
                        // намеренно: в градациях серого подделка обязана быть
                        // самым тёмным пятном строки, потому что цвета может не
                        // быть вовсе.
                        else Modifier.solidOutline(colors.warning_text)
                    )
                    .then(pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                BasicText(state.label, style = textStyle.copy(color = ink, fontWeight = FontWeight.W700))
            }
        }
    }
}

/** Пунктир 2/5 — тот же узор, что у очереди в кружках задач: «данных ещё нет». */
private fun Modifier.dashedOutline(color: Color): Modifier = drawBehind {
    val w = 1.dp.toPx()
    val r = size.height / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(
            width = w,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()), 0f),
        ),
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
private fun MeasuringArc(color: Color, progress: Float?) {
    val transition = rememberInfiniteTransition(label = "badge-measuring")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "badge-measuring-angle",
    )
    Canvas(modifier = Modifier.size(12.dp)) {
        val w = 1.5.dp.toPx()
        val inset = w / 2f
        val arcSize = Size(size.width - w, size.height - w)
        // Дорожка сплошная — она и означает «измерение идёт», в отличие от
        // пунктира «не измеряли».
        drawArc(
            color = color.copy(alpha = 0.35f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
        val sweep = if (progress != null) 360f * progress.coerceIn(0f, 1f) else 90f
        val start = if (progress != null) -90f else spin
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun CheckGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val w = 1.5.dp.toPx()
        val s = size.width
        drawLine(color, Offset(s * 0.14f, s * 0.55f), Offset(s * 0.40f, s * 0.82f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.40f, s * 0.82f), Offset(s * 0.88f, s * 0.20f), w, StrokeCap.Round)
    }
}

@Composable
private fun ArrowGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val w = 1.5.dp.toPx()
        val s = size.width
        val y = s * 0.5f
        drawLine(color, Offset(s * 0.08f, y), Offset(s * 0.86f, y), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.56f, s * 0.22f), Offset(s * 0.90f, y), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.56f, s * 0.78f), Offset(s * 0.90f, y), w, StrokeCap.Round)
    }
}
