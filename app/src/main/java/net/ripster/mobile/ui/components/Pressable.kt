package net.ripster.mobile.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import net.ripster.mobile.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

/**
 * Тактильная кнопка без Material: палец нажал → элемент чуть «утапливается»
 * (scale 0.94 + лёгкое затемнение), отпустил → возвращается. Плюс сам клик.
 *
 * Ставится вместо голого `Modifier.clickable { }` на всё, что должно
 * ощущаться нажимаемым — иначе в foundation-only приложении кнопки «плоские
 * и не жмутся».
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    /** Заливка на время нажатия — для строк списка (трек-лист альбома/EP/сингла/
     *  компила). На широкой строке scale 0.94 почти незаметен, и «нажал/не нажал»
     *  не читается; подсветка фона читается сразу. null — прежнее поведение. */
    pressedBg: Color? = null,
    /** Короткий тактильный «тик» при клике (KEYBOARD_TAP). По умолчанию — да:
     *  в foundation-only приложении иначе тап вообще не ощущается. */
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    // Утапливание — по пружине (Motion.pressScale): палец отпустил, элемент
    // возвращается с массой, а не по таймеру. Затемнение оставляем на коротком
    // линейном твине — на альфе пружина видна как рывок.
    val s by animateFloatAsState(
        if (pressed && enabled) pressedScale else 1f,
        Motion.pressScale, label = "press-scale",
    )
    val a by animateFloatAsState(
        if (pressed && enabled) 0.80f else 1f,
        tween(if (pressed) 60 else 140), label = "press-alpha",
    )
    this
        .then(if (pressedBg != null && pressed && enabled) Modifier.background(pressedBg) else Modifier)
        .scale(s)
        .alpha(a)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = {
                if (haptic) runCatching {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                onClick()
            },
        )
}

/** Только «утапливание» без клика — для строк, где клик вешается отдельно. */
@Composable
fun rememberPressState(): MutableInteractionSource = remember { MutableInteractionSource() }
