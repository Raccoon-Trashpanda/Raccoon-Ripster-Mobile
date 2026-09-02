package net.ripster.mobile.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.core.settings.CredentialStore
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.screens.TidalLoginBlock
import net.ripster.mobile.ui.screens.cast.YandexStationBlock
import net.ripster.mobile.ui.components.pressable
import net.ripster.mobile.ui.theme.RipsterColors
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Настройки как навигационный стек: назад по системному жесту/кнопке
 * ([BackHandler]), вперёд — тапом по разделу. Каждый сервис — на своём
 * экране с точками входа (device-flow, логин/пароль, вставка токена),
 * без «каши» из плоского списка полей.
 */
private sealed interface Route {
    data object Root : Route
    data object Quality : Route
    data object Storage : Route
    data object Network : Route
    data object App : Route
    data object Accounts : Route
    data class Account(val service: Service) : Route
    data object Pairing : Route
    data object Player : Route
    data object Equalizer : Route
    data object Radar : Route
    data object Digs : Route
    data object Tools : Route
    data object About : Route
}

@Composable
fun SettingsHost(onExit: () -> Unit, openAccounts: Boolean = false) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current
    var stack by remember {
        mutableStateOf(
            if (openAccounts) listOf<Route>(Route.Root, Route.Accounts) else listOf<Route>(Route.Root),
        )
    }
    fun push(r: Route) { stack = stack + r }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }

    BackHandler(enabled = true) { pop() }

    val titleKey = when (val r = stack.last()) {
        Route.Root -> "nav.settings"
        Route.Quality -> "set.quality"
        Route.Storage -> "set.storage"
        Route.Network -> "set.network"
        Route.App -> "set.app"
        Route.Accounts -> "set.accounts"
        is Route.Account -> null
        Route.Pairing -> "set.pairing"
        Route.Player -> "set.player"
        Route.Equalizer -> "set.equalizer"
        Route.Radar -> "set.radar"
        Route.Digs -> "set.digs"
        Route.Tools -> "nav.tools"
        Route.About -> "set.about"
    }
    val accountService = (stack.last() as? Route.Account)?.service

    Column(Modifier.fillMaxSize().background(c.surface_canvas)) {
        Header(
            title = accountService?.label ?: tr(titleKey ?: "nav.settings", lang),
            c = c,
            onBack = { pop() },
        )
        Box(Modifier.fillMaxSize()) {
            when (val r = stack.last()) {
                Route.Root -> RootList(lang, c) { push(it) }
                Route.Quality -> QualitySection(lang, c)
                Route.Storage -> StorageSection(lang, c)
                Route.Network -> NetworkSection(lang, c)
                Route.App -> AppSection(lang, c)
                Route.Accounts -> AccountsList(lang, c) { push(Route.Account(it)) }
                is Route.Account -> AccountScreen(r.service, lang, c)
                Route.Pairing -> PairingSection(lang, c)
                Route.Player -> PlayerSection(lang, c)
                Route.Equalizer -> EqualizerSection(lang, c)
                Route.Radar -> RadarSettingsSection(lang, c)
                Route.About -> AboutSection(lang, c)
                Route.Digs -> Soon(lang, c)
                Route.Tools -> net.ripster.mobile.ui.screens.ToolsScreen()
            }
        }
    }
}

@Composable
private fun Header(title: String, c: RipsterColors, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onBack() }.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("‹", style = TextStyle(color = c.text_secondary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.padding(start = 10.dp))
        BasicText(title, style = TextStyle(color = c.text_primary, fontSize = 17.sp, fontWeight = FontWeight.Bold))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))
}

@Composable
private fun RootList(lang: AppLang, c: RipsterColors, go: (Route) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavRow(tr("set.accounts", lang), c) { go(Route.Accounts) }
        NavRow(tr("set.quality", lang), c) { go(Route.Quality) }
        NavRow(tr("set.storage", lang), c) { go(Route.Storage) }
        NavRow(tr("set.network", lang), c) { go(Route.Network) }
        NavRow(tr("set.app", lang), c) { go(Route.App) }
        NavRow(tr("set.pairing", lang), c) { go(Route.Pairing) }
        NavRow(tr("set.player", lang), c) { go(Route.Player) }
        NavRow(tr("set.equalizer", lang), c) { go(Route.Equalizer) }
        NavRow(tr("set.radar", lang), c) { go(Route.Radar) }
        NavRow(tr("set.digs", lang), c) { go(Route.Digs) }
        NavRow(tr("nav.tools", lang), c) { go(Route.Tools) }
        NavRow(tr("set.about", lang), c) { go(Route.About) }
    }
}

@Composable
private fun NavRow(label: String, c: RipsterColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable { onClick() }.padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = TextStyle(color = c.text_primary, fontSize = 15.sp))
        BasicText("›", style = TextStyle(color = c.text_tertiary, fontSize = 16.sp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))
}

// ── Аккаунты ──────────────────────────────────────────────────────────────

private val ACCOUNT_SERVICES = listOf(
    Service.SOUNDCLOUD, Service.DEEZER, Service.QOBUZ, Service.TIDAL,
    Service.SPOTIFY, Service.YANDEX, Service.BEATPORT, Service.BBC,
)

@Composable
private fun AccountsList(lang: AppLang, c: RipsterColors, open: (Service) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ACCOUNT_SERVICES.forEach { svc ->
            var status by remember(svc) { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(svc) {
                status = when (svc) {
                    Service.SOUNDCLOUD, Service.BBC -> null // публичный
                    else -> runCatching { ServiceRegistry.get(svc)?.isConfigured() == true }.getOrDefault(false)
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { open(svc) }.padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(svc.label, Modifier.weight(1f), style = TextStyle(color = c.text_primary, fontSize = 15.sp))
                val (txt, col) = when (status) {
                    null -> tr("svc.status_public", lang) to c.text_tertiary
                    true -> tr("svc.status_connected", lang) to c.accent_text
                    false -> tr("svc.status_off", lang) to c.text_tertiary
                }
                BasicText(txt, style = TextStyle(color = col, fontSize = 11.sp))
                Box(Modifier.padding(start = 8.dp))
                BasicText("›", style = TextStyle(color = c.text_tertiary, fontSize = 16.sp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))
        }
    }
}

@Composable
private fun AccountScreen(svc: Service, lang: AppLang, c: RipsterColors) {
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val scope = rememberCoroutineScope()

    fun cred(k: CredentialStore.Key) = app.credentials.get(k) ?: ""
    fun save(k: CredentialStore.Key, v: String) {
        app.credentials.set(k, v.ifBlank { null }, CredentialStore.Source.MANUAL)
        app.registerClients()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val descKey = when (svc) {
            Service.SOUNDCLOUD -> "svc.d_soundcloud"
            Service.DEEZER -> "svc.d_deezer"
            Service.QOBUZ -> "svc.d_qobuz"
            Service.TIDAL -> "svc.d_tidal"
            Service.SPOTIFY -> "svc.d_spotify"
            Service.YANDEX -> "svc.d_yandex"
            Service.BBC -> "svc.d_bbc"
            Service.APPLE -> "svc.d_apple"
            Service.BEATPORT -> "svc.d_beatport"
        }
        BasicText(tr(descKey, lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
        Box(Modifier.height(16.dp))

        when (svc) {
            Service.SOUNDCLOUD -> {
                var v by remember { mutableStateOf(cred(CredentialStore.Key.SOUNDCLOUD_OAUTH)) }
                WebLoginButton("soundcloud", c, lang) { v = it; save(CredentialStore.Key.SOUNDCLOUD_OAUTH, it) }
                Box(Modifier.height(10.dp))
                BasicText(tr("svc.or_token", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                Box(Modifier.height(6.dp))
                Field("soundcloud.oauth", v, c, { v = it }) { save(CredentialStore.Key.SOUNDCLOUD_OAUTH, v) }
            }
            Service.DEEZER -> {
                var v by remember { mutableStateOf(cred(CredentialStore.Key.DEEZER_ARL)) }
                WebLoginButton("deezer", c, lang) { v = it; save(CredentialStore.Key.DEEZER_ARL, it) }
                Box(Modifier.height(10.dp))
                BasicText(tr("svc.or_token", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                Box(Modifier.height(6.dp))
                Field("deezer.arl", v, c, { v = it }) { save(CredentialStore.Key.DEEZER_ARL, v) }
            }
            Service.QOBUZ -> {
                QobuzForm(lang, c, ::cred, ::save, scope)
                Box(Modifier.height(14.dp))
                BasicText(tr("svc.or_token", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                Box(Modifier.height(6.dp))
                var qt by remember { mutableStateOf(cred(CredentialStore.Key.QOBUZ_TOKEN)) }
                Field("qobuz.token", qt, c, { qt = it }) { save(CredentialStore.Key.QOBUZ_TOKEN, qt) }
                var qa by remember { mutableStateOf(cred(CredentialStore.Key.QOBUZ_APP_ID)) }
                Field("qobuz.app_id", qa, c, { qa = it }) { save(CredentialStore.Key.QOBUZ_APP_ID, qa) }
            }
            Service.TIDAL -> {
                TidalLoginBlock()
                Box(Modifier.height(14.dp))
                BasicText(tr("svc.or_token", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                Box(Modifier.height(6.dp))
                var tt by remember { mutableStateOf(cred(CredentialStore.Key.TIDAL_OAUTH)) }
                Field("tidal.oauth", tt, c, { tt = it }) { save(CredentialStore.Key.TIDAL_OAUTH, tt) }
                BasicText(tr("svc.tidal_json_hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 10.sp))
            }
            Service.SPOTIFY -> {
                var v by remember { mutableStateOf(cred(CredentialStore.Key.SPOTIFY_SP_DC)) }
                WebLoginButton("spotify", c, lang) { v = it; save(CredentialStore.Key.SPOTIFY_SP_DC, it) }
                Box(Modifier.height(10.dp))
                BasicText(tr("svc.or_token", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                Box(Modifier.height(6.dp))
                Field("spotify.sp_dc", v, c, { v = it }) { save(CredentialStore.Key.SPOTIFY_SP_DC, v) }
            }
            Service.YANDEX -> {
                var v by remember { mutableStateOf(cred(CredentialStore.Key.YANDEX_OAUTH)) }
                WebLoginButton("yandex", c, lang) { v = it; save(CredentialStore.Key.YANDEX_OAUTH, it) }
                Box(Modifier.height(6.dp))
                BasicText(tr("svc.or_token", lang), style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
                Box(Modifier.height(8.dp))
                Field("yandex.oauth", v, c, { v = it }) { save(CredentialStore.Key.YANDEX_OAUTH, v) }
                Box(Modifier.height(16.dp))
                YandexStationBlock()
            }
            Service.BBC -> BasicText(
                tr("svc.d_bbc_full", lang),
                style = TextStyle(color = c.text_secondary, fontSize = 12.sp),
            )
            Service.APPLE -> {
                BasicText(
                    tr("svc.d_apple", lang),
                    style = TextStyle(color = c.text_secondary, fontSize = 13.sp),
                )
                Box(Modifier.height(10.dp))
                BasicText(
                    tr("svc.apple_no_creds", lang),
                    style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                )
            }
            Service.BEATPORT -> {
                var u by remember { mutableStateOf(cred(CredentialStore.Key.BEATPORT_USERNAME)) }
                var p by remember { mutableStateOf(cred(CredentialStore.Key.BEATPORT_PASSWORD)) }
                Field("beatport.username", u, c, { u = it }) { save(CredentialStore.Key.BEATPORT_USERNAME, u) }
                Box(Modifier.height(8.dp))
                Field("beatport.password", p, c, { p = it }) { save(CredentialStore.Key.BEATPORT_PASSWORD, p) }
                Box(Modifier.height(6.dp))
                BasicText(
                    tr("svc.beatport_hint", lang),
                    style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                )
            }
        }
    }
}

@Composable
private fun QobuzForm(
    lang: AppLang, c: RipsterColors,
    cred: (CredentialStore.Key) -> String,
    save: (CredentialStore.Key, String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var email by remember { mutableStateOf(cred(CredentialStore.Key.QOBUZ_EMAIL)) }
    var pass by remember { mutableStateOf(cred(CredentialStore.Key.QOBUZ_PASSWORD)) }
    var msg by remember { mutableStateOf<String?>(null) }

    LabeledField(tr("svc.email", lang), email, c, onChange = { email = it })
    LabeledField(tr("svc.password", lang), pass, c, onChange = { pass = it }, password = true)
    Box(Modifier.height(8.dp))
    Btn(tr("svc.login", lang), c) {
        msg = tr("svc.checking", lang)
        save(CredentialStore.Key.QOBUZ_EMAIL, email)
        save(CredentialStore.Key.QOBUZ_PASSWORD, pass)
        scope.launch {
            val ok = runCatching { ServiceRegistry.get(Service.QOBUZ)?.isConfigured() == true }.getOrDefault(false)
            msg = if (ok) tr("svc.saved", lang) else tr("svc.bad_login", lang)
        }
    }
    msg?.let { Box(Modifier.height(6.dp)); BasicText(it, style = TextStyle(color = c.text_secondary, fontSize = 12.sp)) }
}

// ── простые разделы (порт из прежнего SettingsHost) ───────────────────────

@Composable
private fun QualitySection(lang: AppLang, c: RipsterColors) {
    val app = RipsterApp.from(LocalContext.current)
    val s by app.settings.state.collectAsState()
    val opts = listOf("flac_24" to "set.q_flac24", "flac_16" to "set.q_flac16", "mp3_320" to "set.q_mp3")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        GroupLabel(tr("set.q_wifi", lang), c)
        val w = s.qualityWifi.firstOrNull() ?: "flac_24"
        opts.forEach { (id, k) -> RadioRow(tr(k, lang), w == id, c) { app.settings.update { st -> st.copy(qualityWifi = listOf(id) + st.qualityWifi.filter { it != id }) } } }
        GroupLabel(tr("set.q_cell", lang), c)
        val ce = s.qualityCellular.firstOrNull() ?: "mp3_320"
        opts.forEach { (id, k) -> RadioRow(tr(k, lang), ce == id, c) { app.settings.update { st -> st.copy(qualityCellular = listOf(id) + st.qualityCellular.filter { it != id }) } } }

        // ── качество ПО СЕРВИСУ (Ripster учитывает это при скачивании) ──
        GroupLabel(tr("set.q_per_service", lang), c)
        BasicText(
            tr("set.q_per_service_note", lang),
            Modifier.padding(start = 24.dp, end = 16.dp, bottom = 6.dp),
            style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, lineHeight = 15.sp),
        )
        val svcTiers by androidx.compose.runtime.produceState<List<Pair<net.ripster.mobile.core.model.Service, List<net.ripster.mobile.core.model.QualityTier>>>>(emptyList()) {
            value = net.ripster.mobile.core.service.ServiceRegistry.all().map { cl ->
                cl.service to runCatching { cl.qualities() }.getOrDefault(emptyList())
            }
        }
        svcTiers.forEach { (svc, tiers) ->
            if (tiers.isEmpty()) return@forEach
            BasicText(
                svc.label,
                Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
                style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.W600),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 24.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val cur = s.perServiceQuality[svc.id]
                QPill(tr("set.q_default", lang), cur == null, c) {
                    app.settings.update { st -> st.copy(perServiceQuality = st.perServiceQuality - svc.id) }
                }
                tiers.forEach { t ->
                    QPill(t.label, cur == t.id, c) {
                        app.settings.update { st -> st.copy(perServiceQuality = st.perServiceQuality + (svc.id to t.id)) }
                    }
                }
            }
        }
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun QPill(label: String, on: Boolean, c: RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) c.surface_active else c.surface_raised, RoundedCornerShape(16.dp))
            .border(1.dp, if (on) c.accent_text else c.border_subtle, RoundedCornerShape(16.dp))
            .pressable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        BasicText(label, style = TextStyle(color = if (on) c.accent_text else c.text_tertiary, fontSize = 11.sp))
    }
}

@Composable
private fun StorageSection(lang: AppLang, c: RipsterColors) {
    val app = RipsterApp.from(LocalContext.current)
    val s by app.settings.state.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { app.storage.persist(uri); app.settings.update { it.copy(downloadTreeUri = uri.toString()) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        SubRow(
            tr("set.storage", lang),
            if (s.downloadTreeUri.isBlank()) tr("sc.folder_none", lang) else Uri.decode(s.downloadTreeUri.substringAfterLast('/')),
            c,
        ) { pick.launch(null) }
        var tpl by remember(s.nameTemplate) { mutableStateOf(s.nameTemplate) }
        LabeledField(tr("set.name_template", lang), tpl, c, onChange = { tpl = it })
        Box(Modifier.height(6.dp))
        Box(Modifier.padding(start = 24.dp)) {
            Btn("OK", c) { app.settings.update { it.copy(nameTemplate = tpl) } }
        }
    }
}

@Composable
private fun NetworkSection(lang: AppLang, c: RipsterColors) {
    val app = RipsterApp.from(LocalContext.current)
    val s by app.settings.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        ToggleRow(tr("set.wifi_only", lang), s.wifiOnly, c) { on -> app.settings.update { it.copy(wifiOnly = on) } }
        StepperRow(tr("set.parallel", lang), s.parallelDownloads, 1, 6, c) { n -> app.settings.update { it.copy(parallelDownloads = n) } }
    }
}

@Composable
private fun AppSection(lang: AppLang, c: RipsterColors) {
    val app = RipsterApp.from(LocalContext.current)
    val s by app.settings.state.collectAsState()
    val themes = listOf("Dark", "Light", "Midnight", "Ember", "Sepia", "Neon")
    val dens = listOf("Compact", "Normal", "Large")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        ChipsRow(tr("set.language", lang), AppLang.ORDER.map { it.display }, AppLang.ORDER.indexOf(AppLang.byTag(s.uiLang)), c) { i -> app.settings.update { it.copy(uiLang = AppLang.ORDER[i].tag) } }
        ChipsRow(tr("set.theme", lang), themes, themes.indexOf(s.theme).coerceAtLeast(0), c) { i -> app.settings.update { it.copy(theme = themes[i]) } }
        ChipsRow(tr("set.density", lang), dens, dens.indexOf(s.density).coerceAtLeast(0), c) { i -> app.settings.update { it.copy(density = dens[i]) } }
        BasicText(
            tr("set.accent", lang) + "  #FF4D8F",
            Modifier.padding(start = 24.dp, top = 10.dp),
            style = TextStyle(color = c.text_tertiary, fontSize = 12.sp),
        )
        ToggleRow(tr("set.adaptive", lang), s.adaptiveColors, c) { on -> app.settings.update { it.copy(adaptiveColors = on) } }
    }
}

// ── Сопряжение с ПК ──────────────────────────────────────────────────────
// Рукопожатие по коду с экрана ПК + разовая выкачка токенов сервисов в
// шифрованный стор. Протокол — ARCH_2026-08-29_pc_phone_pairing.md.

@Composable
private fun PairingSection(lang: AppLang, c: RipsterColors) {
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val scope = rememberCoroutineScope()
    val bridge = app.pcBridge

    var paired by remember { mutableStateOf(bridge.paired) }
    var addr by remember { mutableStateOf(bridge.manualAddress) }
    var ext by remember { mutableStateOf(bridge.manualAddress) }
    var code by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(bridge.capabilities) }
    var mode by remember { mutableStateOf(bridge.fanoutMode) }
    var reach by remember { mutableStateOf<String?>(null) }   // null=неизвестно, ""=нет, else адрес
    var msg by remember { mutableStateOf<String?>(null) }

    // при открытии экрана — тихо проверить, дотягиваемся ли до ПК
    LaunchedEffect(paired) {
        if (paired) reach = bridge.resolveBase(deep = false) ?: ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        BasicText(tr("pair.hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
        Box(Modifier.height(16.dp))

        if (paired) {
            BasicText(
                tr("pair.with", lang) + " " + bridge.pcName +
                    (bridge.pcId?.let { "  ·  ${it.take(8)}…" } ?: ""),
                style = TextStyle(color = c.accent_text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Box(Modifier.height(4.dp))
            BasicText(
                if (caps.isEmpty()) tr("pair.caps_none", lang)
                else tr("pair.caps", lang) + ": " + caps.joinToString(", "),
                style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
            )
            Box(Modifier.height(4.dp))
            BasicText(
                when (reach) {
                    null -> "…"
                    "" -> tr("pair.unreachable", lang)
                    else -> tr("pair.reachable", lang) + ": " + (reach ?: "")
                },
                style = TextStyle(
                    color = if (reach.isNullOrBlank() && reach != null) c.warning_text else c.text_tertiary,
                    fontSize = 11.sp,
                ),
            )

            // ── режим fan-out ──────────────────────────────────────────────
            Box(Modifier.height(16.dp))
            BasicText(tr("pair.mode", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            Box(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "mirror" to "pair.mode_mirror",
                    "initiator" to "pair.mode_initiator",
                    "isolation" to "pair.mode_isolation",
                ).forEach { (id, key) ->
                    QPill(tr(key, lang), mode == id, c) {
                        if (mode != id) {
                            mode = id
                            scope.launch { bridge.setMode(id) }
                        }
                    }
                }
            }
            Box(Modifier.height(4.dp))
            BasicText(
                tr("pair.mode_" + mode + "_d", lang),
                style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, lineHeight = 15.sp),
            )

            // ── внешний адрес (для своего туннеля/DDNS/VPN) ────────────────
            Box(Modifier.height(16.dp))
            LabeledField(tr("pair.ext_addr", lang), ext, c, onChange = { ext = it })
            BasicText(tr("pair.ext_addr_hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 10.sp, lineHeight = 14.sp))
            Box(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(tr("pair.reconnect", lang), c) {
                    if (working) return@Btn
                    working = true; msg = null
                    if (ext.isNotBlank()) bridge.manualAddress = ext
                    scope.launch {
                        reach = bridge.resolveBase(deep = true) ?: ""
                        working = false
                        msg = if (reach.isNullOrBlank()) tr("pair.unreachable", lang)
                        else tr("pair.reachable", lang) + ": " + reach
                    }
                }
                Btn(if (working) tr("pair.working", lang) else tr("pair.sync_creds", lang), c) {
                    if (working) return@Btn
                    working = true; msg = null
                    scope.launch {
                        val r = bridge.syncCredentials(app.credentials)
                        working = false
                        r.onSuccess { n ->
                            app.registerClients()
                            msg = if (n > 0) tr("pair.imported", lang) + ": " + n
                            else tr("pair.imported_none", lang)
                        }.onFailure { msg = tr("pair.err", lang) + ": " + (it.message ?: "") }
                    }
                }
            }
            Box(Modifier.height(10.dp))
            Btn(tr("pair.unpair", lang), c) {
                scope.launch { bridge.unpair() }
                paired = false; caps = emptySet(); code = ""; reach = null; msg = null
            }
        } else {
            LabeledField(tr("pair.addr", lang), addr, c, onChange = { addr = it })
            LabeledField(tr("pair.code", lang), code, c, onChange = { v -> code = v.filter { it.isDigit() }.take(8) })
            Box(Modifier.height(10.dp))
            Btn(if (working) tr("pair.working", lang) else tr("pair.connect", lang), c) {
                if (working || code.length < 8) return@Btn
                working = true; msg = null
                scope.launch {
                    val r = bridge.claim(addr, code)
                    r.onSuccess {
                        paired = true; caps = bridge.capabilities
                        mode = bridge.fanoutMode; ext = bridge.manualAddress
                        reach = bridge.resolveBase(deep = false) ?: ""
                        // Сразу тянем токены сервисов с ПК и регистрируем клиентов —
                        // как в онбординге. Без этого после сопряжения из Настроек
                        // сервисы оставались «Не подключён» до ручного «Забрать
                        // учётки с ПК».
                        val n = runCatching { bridge.syncCredentials(app.credentials).getOrDefault(0) }
                            .getOrDefault(0)
                        app.registerClients()
                        msg = tr("pair.paired", lang) +
                            (if (n > 0) " · " + tr("pair.imported", lang) + ": " + n else "")
                    }.onFailure { msg = tr("pair.err", lang) + ": " + (it.message ?: "") }
                    working = false
                }
            }
        }

        msg?.let {
            Box(Modifier.height(10.dp))
            BasicText(it, style = TextStyle(color = c.text_secondary, fontSize = 12.sp))
        }
    }
}

// ── О программе: иконка, версия, репозиторий, проверка обновлений ────────
private const val GH_REPO = "Raccoon-Trashpanda/Raccoon-Ripster-Mobile"

/** Сравнение версий по числовым сегментам («0.13» > «0.9», не лексикографически). */
private fun verCmp(a: String, b: String): Int {
    val pa = a.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
    val pb = b.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (d != 0) return d
    }
    return 0
}

private data class UpdCheck(val text: String, val url: String? = null)

@Composable
private fun AboutSection(lang: AppLang, c: RipsterColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var upd by remember { mutableStateOf<UpdCheck?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(net.ripster.mobile.R.drawable.ic_ripster),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Column {
                BasicText("Ripster", style = TextStyle(color = c.text_primary, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                BasicText("v$version", style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
            }
        }
        Box(Modifier.height(14.dp))
        BasicText(
            tr("about.desc", lang),
            style = TextStyle(color = c.text_secondary, fontSize = 13.sp, lineHeight = 19.sp),
        )
        Box(Modifier.height(18.dp))
        BasicText(
            tr("about.caps_title", lang),
            style = TextStyle(color = c.text_primary, fontSize = 14.sp, fontWeight = FontWeight.W700),
        )
        Box(Modifier.height(8.dp))
        listOf(
            "about.cap_engines", "about.cap_tags", "about.cap_gapless",
            "about.cap_naming", "about.cap_convert", "about.cap_radar",
        ).forEach { key ->
            Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("•", style = TextStyle(color = c.accent_text, fontSize = 13.sp))
                BasicText(
                    tr(key, lang),
                    style = TextStyle(color = c.text_secondary, fontSize = 12.5.sp, lineHeight = 18.sp),
                )
            }
        }
        Box(Modifier.height(16.dp))
        SubRow(tr("about.repo", lang), "github.com/$GH_REPO", c) {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/$GH_REPO"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        Box(Modifier.height(12.dp))
        Btn(if (checking) tr("about.checking", lang) else tr("about.check_updates", lang), c) {
            if (checking) return@Btn
            checking = true; upd = null
            scope.launch {
                upd = checkUpdate(version, lang)
                checking = false
            }
        }
        upd?.let { u ->
            Box(Modifier.height(10.dp))
            BasicText(u.text, style = TextStyle(color = c.text_secondary, fontSize = 12.sp, lineHeight = 17.sp))
            if (u.url != null) {
                Box(Modifier.height(8.dp))
                Btn(tr("about.get_update", lang), c) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u.url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        Box(Modifier.height(24.dp))
    }
}

private suspend fun checkUpdate(current: String, lang: AppLang): UpdCheck = withContext(Dispatchers.IO) {
    runCatching {
        val req = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/$GH_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        net.ripster.mobile.core.net.RipsterHttp.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext UpdCheck(tr("about.upd_http", lang) + " (HTTP ${r.code})")
            val body = r.body?.string().orEmpty()
            val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                ?: return@withContext UpdCheck(tr("about.upd_none", lang))
            val latest = tag.removePrefix("v")
            // прямая ссылка на .apk, если она есть в ассетах — иначе страница релиза
            val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(body)?.groupValues?.getOrNull(1)
            val page = Regex("\"html_url\"\\s*:\\s*\"([^\"]+/releases/[^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                ?: "https://github.com/$GH_REPO/releases/latest"
            if (verCmp(latest, current) > 0)
                UpdCheck(tr("about.upd_available", lang) + " v$latest", apk ?: page)
            else
                UpdCheck(tr("about.upd_current", lang) + " (v$current)")
        }
    }.getOrElse { UpdCheck(tr("about.upd_error", lang) + ": " + (it.message ?: tr("about.upd_offline", lang))) }
}

// ── Настройки радара: пояснение + список отслеживаемых артистов ──────────
@Composable
private fun RadarSettingsSection(lang: AppLang, c: RipsterColors) {
    val app = RipsterApp.from(LocalContext.current)
    val bridge = app.pcBridge
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (!bridge.paired) {
            BasicText(tr("radar.need_pair", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
            return@Column
        }
        Box(
            Modifier.fillMaxWidth()
                .background(c.surface_raised, RoundedCornerShape(10.dp))
                .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp)).padding(14.dp),
        ) {
            BasicText(tr("radar.settings_pc_note", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp, lineHeight = 17.sp))
        }
        Box(Modifier.height(14.dp))
        val res by androidx.compose.runtime.produceState<Result<List<net.ripster.mobile.core.pair.PcBridge.RadarItem>>?>(initialValue = null) {
            value = bridge.radar()
        }
        when (val r = res) {
            null -> BasicText(tr("radar.loading", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
            else -> {
                val list = r.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
                BasicText(
                    tr("radar.artists", lang) + ": " + list.size,
                    Modifier.padding(bottom = 8.dp),
                    style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                )
                list.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(a.name, maxLines = 1, style = TextStyle(color = c.text_primary, fontSize = 13.sp, fontWeight = FontWeight.W600))
                            BasicText(
                                a.service.ifBlank { "auto" } + (if (a.seenCount > 0) "  ·  ${a.seenCount}" else ""),
                                style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                            )
                        }
                        if (a.auto) BasicText(tr("radar.auto_on", lang), style = TextStyle(color = c.accent_text.copy(alpha = 0.85f), fontSize = 10.sp))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))
                }
            }
        }
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun PlayerSection(lang: AppLang, c: RipsterColors) {
    val app = RipsterApp.from(LocalContext.current)
    val s by app.settings.state.collectAsState()
    val styles = listOf("reference" to "player.style_reference", "immersive" to "player.style_immersive", "studio" to "player.style_studio")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        GroupLabel(tr("player.style", lang), c)
        styles.forEach { (id, k) ->
            RadioRow(tr(k, lang), s.playerStyle == id, c) {
                app.settings.update { it.copy(playerStyle = id) }
            }
        }
        BasicText(
            tr("player.audio_in_eq", lang),
            Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
            style = TextStyle(color = c.text_tertiary, fontSize = 10.sp, lineHeight = 14.sp),
        )
    }
}

// ── Эквалайзер и эффекты ─────────────────────────────────────────────────

@Composable
private fun EqualizerSection(lang: AppLang, c: RipsterColors) {
    val fx = net.ripster.mobile.player.AudioEffects
    val bands by fx.bands.collectAsState()
    val cfg by fx.config.collectAsState()
    val app = RipsterApp.from(LocalContext.current)
    val s by app.settings.state.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        // ── Аудиодвижок ──
        GroupLabel(tr("snd.engine", lang), c)
        val engineAvailable = net.ripster.mobile.player.NativeAudioEngine.isAvailable
        RadioRow(tr("snd.engine_exo", lang), !s.nativeEngine, c) {
            app.settings.update { it.copy(nativeEngine = false) }
        }
        RadioRow(
            tr("snd.engine_native", lang) + (if (engineAvailable) "" else "  —  " + tr("nae.no_lib", lang)),
            s.nativeEngine, c,
        ) {
            if (engineAvailable) app.settings.update { it.copy(nativeEngine = true) }
        }
        BasicText(
            tr("nae.setting_hint", lang),
            Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 4.dp),
            style = TextStyle(color = c.text_tertiary, fontSize = 10.sp, lineHeight = 14.sp),
        )

        // ── Эквалайзер ──
        GroupLabel(tr("snd.eq", lang), c)
        ToggleRow(tr("eq.enabled", lang), cfg.enabled, c) { fx.setEnabled(it) }

        if (bands.count == 0) {
            GroupLabel(tr("eq.unavailable", lang), c)
            return@Column
        }

        // пресеты
        GroupLabel(tr("eq.preset", lang), c)
        ChipsRow(
            "", bands.presetNames, cfg.preset.coerceAtLeast(-1).let { if (it < 0) -1 else it }, c,
        ) { i -> fx.setPreset(i) }
        if (cfg.preset < 0) {
            BasicText(
                tr("eq.manual", lang),
                Modifier.padding(start = 24.dp, top = 2.dp),
                style = TextStyle(color = c.text_tertiary, fontSize = 10.sp),
            )
        }

        // полосы
        Box(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            bands.centerHz.forEachIndexed { i, hz ->
                val level = cfg.levels.getOrElse(i) { 0 }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VBar(
                        value = (level - bands.minMdB).toFloat() / (bands.maxMdB - bands.minMdB),
                        enabled = cfg.enabled,
                        c = c,
                    ) { f ->
                        fx.setBand(i, (bands.minMdB + f * (bands.maxMdB - bands.minMdB)).toInt())
                    }
                    Box(Modifier.height(4.dp))
                    BasicText(hzLabel(hz), style = TextStyle(color = c.text_tertiary, fontSize = 9.sp))
                }
            }
        }

        // эффекты
        GroupLabel(tr("eq.effects", lang), c)
        HSlider(tr("eq.bass", lang), cfg.bassBoost / 1000f, cfg.enabled, c) { fx.setBassBoost((it * 1000).toInt()) }
        HSlider(tr("eq.stereo", lang), cfg.virtualizer / 1000f, cfg.enabled, c) { fx.setVirtualizer((it * 1000).toInt()) }
        HSlider(tr("eq.loudness", lang), cfg.loudnessMdB / 2000f, cfg.enabled, c) { fx.setLoudness((it * 2000).toInt()) }

        Box(Modifier.height(10.dp))
        Box(Modifier.padding(start = 24.dp)) { Btn(tr("eq.reset", lang), c) { fx.resetFlat() } }
        Box(Modifier.height(20.dp))
    }
}

private fun hzLabel(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"

@Composable
private fun VBar(value: Float, enabled: Boolean, c: RipsterColors, onChange: (Float) -> Unit) {
    val v = value.coerceIn(0f, 1f)
    Box(
        Modifier
            .width(26.dp)
            .height(96.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures {
                    onChange((1f - it.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures { ch, _ ->
                    onChange((1f - ch.position.y / size.height).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(c.surface_raised, RoundedCornerShape(2.dp)))
        Box(Modifier.width(4.dp).fillMaxHeight(v).background(if (enabled) c.accent_fill else c.surface_active, RoundedCornerShape(2.dp)))
        Box(
            Modifier.padding(bottom = (v * 84).dp).size(12.dp)
                .background(if (enabled) c.accent_fill else c.text_disabled, RoundedCornerShape(999.dp)),
        )
    }
}

@Composable
private fun HSlider(label: String, value: Float, enabled: Boolean, c: RipsterColors, onChange: (Float) -> Unit) {
    val v = value.coerceIn(0f, 1f)
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(label, style = TextStyle(color = c.text_primary, fontSize = 13.sp))
            BasicText("${(v * 100).toInt()}%", style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
        }
        Box(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(20.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures {
                        onChange((it.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { ch, _ ->
                        onChange((ch.position.x / size.width).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.surface_raised, RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(v).height(4.dp).background(if (enabled) c.accent_fill else c.surface_active, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun Soon(lang: AppLang, c: RipsterColors) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        BasicText(tr("set.soon", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
    }
}

// ── мелкие переиспользуемые ───────────────────────────────────────────────

@Composable
private fun GroupLabel(text: String, c: RipsterColors) {
    BasicText(text, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp),
        style = TextStyle(color = c.text_tertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold))
}

@Composable
private fun RadioRow(label: String, on: Boolean, c: RipsterColors, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable { onClick() }.padding(horizontal = 24.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(14.dp).background(if (on) c.accent_fill else c.surface_raised, RoundedCornerShape(999.dp)).padding(7.dp))
        BasicText("  $label", style = TextStyle(color = c.text_primary, fontSize = 14.sp))
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, c: RipsterColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f), style = TextStyle(color = c.text_primary, fontSize = 14.sp))
        Box(Modifier.background(if (on) c.accent_fill else c.surface_raised, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
            BasicText(if (on) "ON" else "OFF", style = TextStyle(color = if (on) c.text_on_fill else c.text_tertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, c: RipsterColors, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f), style = TextStyle(color = c.text_primary, fontSize = 14.sp))
        Step("–", c) { if (value > min) onChange(value - 1) }
        BasicText("  $value  ", style = TextStyle(color = c.text_primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        Step("+", c) { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun Step(s: String, c: RipsterColors, onClick: () -> Unit) {
    Box(Modifier.background(c.surface_raised, RoundedCornerShape(6.dp)).pressable { onClick() }.padding(horizontal = 10.dp, vertical = 3.dp)) {
        BasicText(s, style = TextStyle(color = c.text_primary, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun ChipsRow(label: String, options: List<String>, selected: Int, c: RipsterColors, onPick: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        BasicText(label, style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
        Box(Modifier.height(4.dp))
        // Один ряд с горизонтальной прокруткой — у эквалайзера ~10 пресетов
        // ("Hip Hop", "Heavy Metal" и т.д.) в экран не влезают и раньше
        // сминались по вертикали. Короткие ряды (язык/тема) выглядят как прежде.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { i, opt ->
                val on = i == selected
                Box(Modifier.background(if (on) c.surface_active else c.surface_raised, RoundedCornerShape(7.dp))
                    .border(1.dp, if (on) c.border_strong else c.border_subtle, RoundedCornerShape(7.dp))
                    .clickable { onPick(i) }.padding(horizontal = 9.dp, vertical = 6.dp)) {
                    BasicText(opt, style = TextStyle(color = if (on) c.text_primary else c.text_tertiary, fontSize = 11.sp))
                }
            }
        }
    }
}

@Composable
private fun SubRow(title: String, value: String, c: RipsterColors, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressable { onClick() }.padding(horizontal = 24.dp, vertical = 12.dp)) {
        BasicText(title, style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.height(2.dp))
        BasicText(value, style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
    }
}

@Composable
private fun Field(key: String, value: String, c: RipsterColors, onChange: (String) -> Unit, onCommit: () -> Unit) {
    val lang = LocalAppLang.current
    Column(Modifier.padding(vertical = 6.dp)) {
        BasicText(key, style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().background(c.surface_raised, RoundedCornerShape(8.dp))
            .border(1.dp, c.border_subtle, RoundedCornerShape(8.dp)).padding(12.dp)) {
            if (value.isEmpty()) BasicText(tr("acc.empty", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
            BasicTextField(value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = c.text_primary, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.height(6.dp))
        Btn(tr("svc.save", lang), c, onCommit)
    }
}

@Composable
private fun LabeledField(label: String, value: String, c: RipsterColors, onChange: (String) -> Unit, password: Boolean = false) {
    Column(Modifier.padding(vertical = 6.dp)) {
        BasicText(label, style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
        Box(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().background(c.surface_raised, RoundedCornerShape(8.dp))
            .border(1.dp, c.border_subtle, RoundedCornerShape(8.dp)).padding(12.dp)) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = TextStyle(color = c.text_primary, fontSize = 13.sp), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Btn(label: String, c: RipsterColors, onClick: () -> Unit) {
    Box(Modifier.background(c.accent_fill, RoundedCornerShape(8.dp)).pressable { onClick() }.padding(horizontal = 16.dp, vertical = 9.dp)) {
        BasicText(label, style = TextStyle(color = c.text_on_fill, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

/**
 * «Войти через сайт» — открывает окно входа сервиса (WebView) и, как только
 * появится нужная кука/токен, отдаёт её в [onToken]. Без DevTools и копипаста.
 */
@Composable
private fun WebLoginButton(service: String, c: RipsterColors, lang: AppLang, onToken: (String) -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val tok = res.data?.getStringExtra(
            net.ripster.mobile.ui.login.LoginWebViewActivity.EXTRA_TOKEN,
        )
        if (res.resultCode == android.app.Activity.RESULT_OK && !tok.isNullOrBlank()) {
            onToken(tok)
            status = tr("svc.login_ok", lang)
        } else {
            status = tr("svc.login_cancel", lang)
        }
    }
    Column {
        Btn(tr("svc.login_via_site", lang), c) {
            status = null
            launcher.launch(
                android.content.Intent(ctx, net.ripster.mobile.ui.login.LoginWebViewActivity::class.java)
                    .putExtra(net.ripster.mobile.ui.login.LoginWebViewActivity.EXTRA_SERVICE, service),
            )
        }
        status?.let {
            Box(Modifier.height(6.dp))
            BasicText(it, style = TextStyle(color = c.text_secondary, fontSize = 11.sp))
        }
    }
}

private const val YANDEX_OAUTH_URL =
    "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d"
