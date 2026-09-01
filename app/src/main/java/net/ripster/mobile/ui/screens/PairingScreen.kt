package net.ripster.mobile.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.ui.components.ButtonLevel
import net.ripster.mobile.ui.components.RipsterButton
import net.ripster.mobile.ui.theme.MinTouchTarget
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.Weights

/**
 * Экран сопряжения телефона с ПК Рипстером.
 *
 * Реализует ТОЛЬКО ту часть протокола из ARCH_2026-08-29_pc_phone_pairing.md,
 * где решение уже принято: код короткий и вводится вручную, а не QR — камеры
 * в приложении сейчас нет вообще (ни зависимости, ни разрешения), и заводить
 * её только ради этого экрана — решение того же веса, что и сам протокол
 * сопряжения, поэтому не принято молча здесь. Шесть цифр — баланс между
 * «легко ввести руками, глядя на экран ПК» и «не слишком тривиально
 * подобрать за то короткое окно, пока код действителен» (сам TTL кода —
 * решение сервера, не этого экрана).
 *
 * Статус попытки подключения переиспользует ТОТ ЖЕ честностный язык, что
 * DownloadsQueueScreen унаследовал от QualityBadge (Badges.kt): Connecting —
 * сплошная обводка + дуга (крутится, потому что честного процента для
 * рукопожатия по сети не существует — это не измеримая величина, а просто
 * "ждём ответ"); Success — БЕЗ праздничного цвета, обычная галочка и текст
 * (успешное сопряжение — ожидаемый исход правильно введённого кода, а не
 * повод для салюта); Error — единственное состояние с сплошной заливкой,
 * вместе с ПРИЧИНОЙ и кнопкой повтора, а не голой красной строкой.
 */
enum class PairingAttemptState { Idle, Connecting, Success, Error }

@Composable
fun PairingScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    attemptState: PairingAttemptState,
    errorMessage: String? = null,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface_canvas)
            .padding(horizontal = spacing.gutter),
    ) {
        BasicText(
            text = "Сопряжение с ПК",
            modifier = Modifier.padding(top = spacing.lg, bottom = spacing.sm),
            style = TextStyle(color = colors.text_primary, fontSize = type.title, fontWeight = Weights.Primary),
        )
        BasicText(
            text = "Введите код, который показан в Рипстере на компьютере",
            modifier = Modifier.padding(bottom = spacing.xl),
            style = TextStyle(color = colors.text_secondary, fontSize = type.body),
        )

        CodeField(
            code = code,
            onCodeChange = onCodeChange,
            enabled = attemptState != PairingAttemptState.Connecting,
            onSubmit = onSubmit,
        )

        Spacer(Modifier.height(spacing.lg))

        AttemptStatus(state = attemptState, errorMessage = errorMessage)

        Spacer(Modifier.height(spacing.xl))

        RipsterButton(
            text = if (attemptState == PairingAttemptState.Error) "Повторить" else "Подключить",
            onClick = onSubmit,
            level = ButtonLevel.Primary,
            enabled = code.length == 6 && attemptState != PairingAttemptState.Connecting,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(net.ripster.mobile.ui.theme.Radii.RCtl))
            .background(colors.surface_sunken)
            .border(1.dp, colors.border_subtle, RoundedCornerShape(net.ripster.mobile.ui.theme.Radii.RCtl))
            .padding(horizontal = spacing.controlPadding, vertical = spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        if (code.isEmpty()) {
            BasicText(
                text = "— — — — — —",
                style = TextStyle(color = colors.text_tertiary, fontSize = type.display, textAlign = TextAlign.Center),
            )
        }
        BasicTextField(
            value = code,
            onValueChange = { raw ->
                val digitsOnly = raw.filter { it.isDigit() }.take(6)
                onCodeChange(digitsOnly)
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = colors.text_primary,
                fontSize = type.display,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                letterSpacing = 8.sp,
            ),
            cursorBrush = SolidColor(colors.accent_fill),
        )
    }
}

@Composable
private fun AttemptStatus(state: PairingAttemptState, errorMessage: String?) {
    val colors = RipsterTheme.colors
    val spacing = RipsterTheme.spacing
    val type = RipsterTheme.type

    when (state) {
        PairingAttemptState.Idle -> Unit // ничего не показываем — попытки ещё не было, показывать нечего
        PairingAttemptState.Connecting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ConnectingArc(color = colors.text_tertiary)
            BasicText("Подключение…", style = TextStyle(color = colors.text_tertiary, fontSize = type.body))
        }
        PairingAttemptState.Success -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SuccessCheckGlyph(color = colors.text_primary)
            BasicText("Устройства сопряжены", style = TextStyle(color = colors.text_primary, fontSize = type.body))
        }
        PairingAttemptState.Error -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.danger_fill, RoundedCornerShape(net.ripster.mobile.ui.theme.Radii.RCtl))
                .padding(spacing.md),
        ) {
            BasicText(
                text = "Не удалось подключиться",
                style = TextStyle(color = colors.text_on_fill, fontSize = type.body, fontWeight = FontWeight.W700),
            )
            if (errorMessage != null) {
                Spacer(Modifier.height(spacing.xs))
                BasicText(
                    text = errorMessage,
                    style = TextStyle(color = colors.text_on_fill, fontSize = type.caption),
                )
            }
        }
    }
}

@Composable
private fun ConnectingArc(color: Color) {
    val transition = rememberInfiniteTransition(label = "pairing-arc")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "pairing-arc-angle",
    )
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = 1.5.dp.toPx()
        val inset = w / 2f
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(
            color = color.copy(alpha = 0.35f),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = spin, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun SuccessCheckGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = 1.6.dp.toPx()
        val s = size.width
        drawLine(color, Offset(s * 0.14f, s * 0.55f), Offset(s * 0.40f, s * 0.82f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.40f, s * 0.82f), Offset(s * 0.88f, s * 0.20f), w, StrokeCap.Round)
    }
}
