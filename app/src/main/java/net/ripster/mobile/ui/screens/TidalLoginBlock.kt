package net.ripster.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.settings.CredentialStore
import net.ripster.mobile.service.tidal.TidalAuth
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

private sealed interface TState {
    data object Idle : TState
    data class Pending(val userCode: String, val uri: String, val deviceCode: String, val interval: Int, val deadline: Long) : TState
    data object Done : TState
    data class Error(val msg: String) : TState
}

/**
 * Вход в Tidal по OAuth device-flow: показать код, открыть страницу
 * активации, поллить до подтверждения, сохранить refresh_token в стор.
 */
@Composable
fun TidalLoginBlock() {
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val scope = rememberCoroutineScope()

    var state by remember {
        mutableStateOf<TState>(
            if (app.credentials.get(CredentialStore.Key.TIDAL_OAUTH) != null) TState.Done else TState.Idle,
        )
    }

    val pending = state as? TState.Pending
    LaunchedEffect(pending?.deviceCode) {
        val p = pending ?: return@LaunchedEffect
        while (System.currentTimeMillis() < p.deadline) {
            delay(p.interval.coerceAtLeast(2) * 1000L)
            val tokens = runCatching { TidalAuth.pollDevice(p.deviceCode) }.getOrElse {
                state = TState.Error(it.message ?: "auth error"); return@LaunchedEffect
            }
            if (tokens != null) {
                app.credentials.set(CredentialStore.Key.TIDAL_OAUTH, TidalAuth.encodeStored(tokens), CredentialStore.Source.MANUAL)
                app.registerClients()
                state = TState.Done
                return@LaunchedEffect
            }
        }
        state = TState.Error(tr("acc.tidal_expired", lang))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface_raised, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        BasicText("Tidal", style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.height(6.dp))

        when (val s = state) {
            TState.Idle, is TState.Error -> {
                if (s is TState.Error) {
                    BasicText(s.msg, style = TextStyle(color = c.danger_text, fontSize = 12.sp))
                    Box(Modifier.height(6.dp))
                }
                Btn(tr("acc.tidal_login", lang), c) {
                    state = TState.Idle
                    scope.launch {
                        val d = runCatching { TidalAuth.startDevice() }.getOrElse {
                            state = TState.Error(it.message ?: "start error"); return@launch
                        }
                        val uri = d.verificationUriComplete.ifBlank { "https://" + d.verificationUri }
                        state = TState.Pending(
                            userCode = d.userCode,
                            uri = if (uri.startsWith("http")) uri else "https://$uri",
                            deviceCode = d.deviceCode,
                            interval = d.interval,
                            deadline = System.currentTimeMillis() + d.expiresIn * 1000L,
                        )
                    }
                }
            }

            is TState.Pending -> {
                BasicText(tr("acc.tidal_code", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
                Box(Modifier.height(4.dp))
                BasicText(s.userCode, style = TextStyle(color = c.text_primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                Box(Modifier.height(8.dp))
                Btn(tr("acc.tidal_open", lang), c) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Box(Modifier.height(6.dp))
                BasicText(tr("acc.tidal_waiting", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
            }

            TState.Done -> BasicText(tr("acc.tidal_ok", lang), style = TextStyle(color = c.text_primary, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }

        // Ручная вставка токена. Раньше сырое поле `tidal.oauth` ждало JSON-блоб
        // {refreshToken,countryCode,accessToken}; человек вставлял туда обычный
        // access-токен, тот не парсился — и приложение всё равно гнало в браузер
        // («why do I have to open a browser even after pasting a tidal token»).
        // Здесь принимаем И сырой токен, И блоб: сырой заворачиваем сами.
        Box(Modifier.height(12.dp))
        BasicText(
            tr("acc.tidal_manual", lang),
            style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
        )
        Box(Modifier.height(6.dp))
        var manual by remember { mutableStateOf("") }
        var manualMsg by remember { mutableStateOf<String?>(null) }
        Box(
            Modifier.fillMaxWidth()
                .background(c.surface_canvas, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (manual.isEmpty()) {
                BasicText(
                    tr("acc.tidal_manual_ph", lang),
                    style = TextStyle(color = c.text_tertiary, fontSize = 12.sp),
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = manual,
                onValueChange = { manual = it },
                singleLine = true,
                textStyle = TextStyle(color = c.text_primary, fontSize = 12.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent_text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.height(8.dp))
        Btn(tr("acc.tidal_manual_save", lang), c) {
            val v = manual.trim()
            if (v.length < 20) {
                manualMsg = tr("acc.tidal_manual_short", lang)
            } else {
                // Уже блоб — кладём как есть; иначе это access-токен.
                val payload = if (TidalAuth.decodeStored(v)?.let {
                        it.accessToken.isNotBlank() || it.refreshToken.isNotBlank()
                    } == true
                ) v else TidalAuth.encodeAccessToken(v)
                app.credentials.set(CredentialStore.Key.TIDAL_OAUTH, payload, CredentialStore.Source.MANUAL)
                app.registerClients()
                manual = ""
                manualMsg = null
                state = TState.Done
            }
        }
        manualMsg?.let {
            Box(Modifier.height(6.dp))
            BasicText(it, style = TextStyle(color = c.danger_text, fontSize = 11.sp))
        }
    }
}

@Composable
private fun Btn(label: String, c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier.background(c.accent_fill, RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        BasicText(label, style = TextStyle(color = c.text_on_fill, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}
