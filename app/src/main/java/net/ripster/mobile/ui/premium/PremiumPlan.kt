package net.ripster.mobile.ui.premium

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.ripster.mobile.RipsterApp

/**
 * План этого телефона: что из «дорогих визуалов» ему положено.
 *
 * Здесь сходятся три входа, которые меняются НЕ тогда, когда перерисовывается
 * экран: настройка, системная шкала анимаций и режим экономии заряда. Последние
 * два телефон меняет сам — посреди игры, — поэтому на них вешаются подписки, а
 * не однократное чтение в `remember`: иначе «включил экономичный режим, а
 * стекло всё ещё размывает кадр» висит до перезапуска процесса.
 */
@Composable
fun rememberPremiumPlan(): PremiumPlan {
    val context = LocalContext.current
    val app = RipsterApp.from(context)
    val settings by app.settings.state.collectAsState()

    var animationsOff by remember { mutableStateOf(PremiumVisuals.animationsOff(context)) }
    var saver by remember { mutableStateOf(PremiumVisuals.batterySaver(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                saver = PremiumVisuals.batterySaver(context)
                animationsOff = PremiumVisuals.animationsOff(context)
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    // Возврат из фона: шкалу анимаций человек мог поменять в «Для разработчиков».
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                animationsOff = PremiumVisuals.animationsOff(context)
                saver = PremiumVisuals.batterySaver(context)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    return remember(settings.premiumVisuals, settings.adaptiveColors, animationsOff, saver) {
        PremiumVisuals.resolve(
            enabled = settings.premiumVisuals,
            sdkInt = PremiumVisuals.deviceSdk(),
            animationsOff = animationsOff,
            batterySaver = saver,
            adaptiveColors = settings.adaptiveColors,
        )
    }
}
