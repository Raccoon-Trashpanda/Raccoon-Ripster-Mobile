package net.ripster.mobile.ui.screens.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.cast.GlagolConnection
import net.ripster.mobile.cast.GlagolDiscovery
import net.ripster.mobile.cast.YandexQuasar
import net.ripster.mobile.core.settings.CredentialStore
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Управление колонками Яндекса из настроек. Обнаружение по mDNS +
 * conversation-токен из облака Яндекса → локальный WebSocket. Пока —
 * транспорт и громкость; запуск НАШИХ файлов на колонке требует
 * интеграции Яндекс.Музыки (следующий шаг).
 */
@Composable
fun YandexStationBlock() {
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val scope = rememberCoroutineScope()

    val token = app.credentials.get(CredentialStore.Key.YANDEX_OAUTH)

    Column(
        Modifier.fillMaxWidth().background(c.surface_raised, RoundedCornerShape(10.dp)).padding(14.dp),
    ) {
        BasicText(tr("cast.title", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.height(6.dp))

        if (token.isNullOrBlank()) {
            BasicText(tr("cast.need_token", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
            return@Column
        }

        val found = remember { mutableStateMapOf<String, GlagolDiscovery.Found>() } // host -> found
        val names = remember { mutableStateMapOf<String, String>() } // deviceId -> name
        var scanning by remember { mutableStateOf(true) }
        var conn by remember { mutableStateOf<GlagolConnection?>(null) }
        var connectedHost by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf(GlagolConnection.Status.CLOSED) }
        LaunchedEffect(conn) {
            conn?.status?.collect { status = it }
        }

        LaunchedEffect(token) {
            scope.launch {
                runCatching { YandexQuasar(token).deviceList() }.getOrDefault(emptyList())
                    .forEach { names[it.id] = it.name }
            }
        }
        DisposableEffect(token) {
            val job = scope.launch {
                GlagolDiscovery(ctx).discover().collectLatest { f ->
                    found[f.host] = f
                    scanning = false
                }
            }
            onDispose { job.cancel(); conn?.close() }
        }

        if (found.isEmpty()) {
            BasicText(
                tr(if (scanning) "cast.scanning" else "cast.none", lang),
                style = TextStyle(color = c.text_tertiary, fontSize = 12.sp),
            )
        }

        found.values.forEach { f ->
            val label = names[f.deviceId]?.takeIf { it.isNotBlank() } ?: f.serviceName.ifBlank { f.host }
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        conn?.close()
                        val tk = runCatching {
                            YandexQuasar(token).deviceToken(f.deviceId, /* platform */ "yandexstation")
                        }.getOrNull() ?: return@launch
                        conn = GlagolConnection(f.host, f.port, tk).also { it.connect() }
                        connectedHost = f.host
                    }
                }.padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                BasicText(label, Modifier.weight(1f), style = TextStyle(color = c.text_primary, fontSize = 13.sp))
                if (connectedHost == f.host) {
                    BasicText(
                        tr("cast.connected", lang),
                        style = TextStyle(color = c.text_secondary, fontSize = 11.sp),
                    )
                }
            }
        }

        val cnn = conn
        if (cnn != null && status == GlagolConnection.Status.CONNECTED) {
            Box(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransportBtn(tr("cast.play", lang), c) { cnn.play() }
                TransportBtn(tr("cast.pause", lang), c) { cnn.pause() }
                TransportBtn("⏮", c) { cnn.previous() }
                TransportBtn("⏭", c) { cnn.next() }
            }

            val playback by app.player.state.collectAsState()
            var castMsg by remember { mutableStateOf<String?>(null) }
            if (playback.hasItem) {
                Box(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TransportBtn(tr("cast.play_here", lang), c) {
                        castMsg = tr("cast.casting", lang)
                        scope.launch {
                            val t = net.ripster.mobile.core.model.Track(
                                id = "cur",
                                title = playback.title,
                                artist = playback.artist,
                                service = net.ripster.mobile.core.model.Service.SOUNDCLOUD, // не YANDEX → путь поиска
                                durationMs = playback.durationMs.takeIf { it > 0 },
                            )
                            val ymId = net.ripster.mobile.cast.YandexCast.resolveYmId(t)
                            if (ymId != null) { cnn.playMusic(ymId); castMsg = null }
                            else castMsg = tr("cast.no_match", lang)
                        }
                    }
                    castMsg?.let {
                        BasicText("  $it", style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportBtn(label: String, c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier.background(c.surface_active, RoundedCornerShape(7.dp)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicText(label, style = TextStyle(color = c.text_primary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
}
