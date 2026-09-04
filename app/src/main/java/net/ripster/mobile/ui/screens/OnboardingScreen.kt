package net.ripster.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.ui.components.pressable
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme
import net.ripster.mobile.ui.theme.RipsterThemeName
import net.ripster.mobile.ui.i18n.errorText

/**
 * Первый запуск: язык → тема → размер шрифта → папка загрузок → в учётки.
 * Каждый выбор применяется сразу (весь app перерисовывается от AppSettings),
 * так что тему и кегль человек видит в тот же момент.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val app = RipsterApp.from(ctx)
    val s by app.settings.state.collectAsState()
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors

    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    val steps = 5

    // ── шаг «сопряжение с ПК» (необязательный) ──
    var pairAddr by remember { mutableStateOf(app.pcBridge.manualAddress.removePrefix("http://").removePrefix("https://")) }
    var pairCode by remember { mutableStateOf("") }
    var pairBusy by remember { mutableStateOf(false) }
    var pairMsg by remember { mutableStateOf<String?>(null) }
    var pairOk by remember { mutableStateOf(app.pcBridge.paired) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { app.storage.persist(uri) }
            app.settings.update { it.copy(downloadTreeUri = uri.toString()) }
        }
    }

    Box(Modifier.fillMaxSize().background(c.surface_canvas)) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            // индикатор шагов
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps) { i ->
                    Box(
                        Modifier.height(4.dp)
                            .width(if (i == step) 26.dp else 14.dp)
                            .background(
                                if (i <= step) c.accent_fill else c.border_subtle,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            BasicText(
                tr("ob.title", lang),
                style = TextStyle(color = c.text_primary, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                when (step) {
                    0 -> tr("ob.lang", lang)
                    1 -> tr("ob.theme", lang)
                    2 -> tr("ob.font", lang)
                    3 -> tr("ob.folder", lang)
                    else -> tr("ob.pair", lang)
                },
                style = TextStyle(color = c.text_secondary, fontSize = 13.sp),
            )
            Spacer(Modifier.height(22.dp))

            when (step) {
                0 -> AppLang.ORDER.forEach { l ->
                    Selectable(l.display, s.uiLang == l.tag, c) {
                        app.settings.update { it.copy(uiLang = l.tag) }
                    }
                }

                1 -> {
                    val names = mapOf(
                        RipsterThemeName.Dark to "ob.theme_dark",
                        RipsterThemeName.Light to "ob.theme_light",
                        RipsterThemeName.Midnight to "ob.theme_midnight",
                        RipsterThemeName.Ember to "ob.theme_ember",
                        RipsterThemeName.Sepia to "ob.theme_sepia",
                        RipsterThemeName.Neon to "ob.theme_neon",
                    )
                    RipsterThemeName.entries.forEach { t ->
                        Selectable(tr(names[t] ?: "ob.theme_dark", lang), s.theme == t.name, c) {
                            app.settings.update { it.copy(theme = t.name) }
                        }
                    }
                }

                2 -> {
                    listOf(
                        "Compact" to "ob.font_s",
                        "Normal" to "ob.font_m",
                        "Large" to "ob.font_l",
                    ).forEach { (id, key) ->
                        Selectable(tr(key, lang), s.density == id, c) {
                            app.settings.update { it.copy(density = id) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(c.surface_raised, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        BasicText(
                            tr("ob.font_sample", lang),
                            style = TextStyle(color = c.text_primary, fontSize = 15.sp, lineHeight = 21.sp),
                        )
                    }
                }

                3 -> {
                    BasicText(
                        tr("ob.folder_why", lang),
                        style = TextStyle(color = c.text_tertiary, fontSize = 12.sp, lineHeight = 17.sp),
                    )
                    Spacer(Modifier.height(14.dp))
                    val chosen = s.downloadTreeUri.isNotBlank()
                    Selectable(
                        if (chosen) tr("ob.folder_set", lang) + ": " +
                            android.net.Uri.decode(s.downloadTreeUri.substringAfterLast('/'))
                        else tr("ob.pick_folder", lang),
                        chosen, c,
                    ) { pickFolder.launch(null) }
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        tr("ob.folder_later", lang),
                        style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                    )
                }

                else -> {
                    BasicText(
                        tr("ob.pair_why", lang),
                        style = TextStyle(color = c.text_tertiary, fontSize = 12.sp, lineHeight = 17.sp),
                    )
                    Spacer(Modifier.height(14.dp))
                    if (pairOk) {
                        Selectable(tr("ob.pair_done", lang) + ": " + app.pcBridge.pcName, true, c) {}
                    } else {
                        ObField(tr("ob.pair_addr", lang), pairAddr, c) { pairAddr = it }
                        Spacer(Modifier.height(8.dp))
                        ObField(tr("ob.pair_code", lang), pairCode, c, number = true) {
                            pairCode = it.filter { ch -> ch.isDigit() }.take(8)
                        }
                        Spacer(Modifier.height(12.dp))
                        PrimaryBtn(
                            if (pairBusy) tr("ob.pair_busy", lang) else tr("ob.pair_go", lang), c,
                        ) {
                            if (!pairBusy && pairAddr.isNotBlank() && pairCode.length >= 6) {
                                pairBusy = true; pairMsg = null
                                scope.launch {
                                    val r = app.pcBridge.claim(pairAddr, pairCode)
                                    if (r.isSuccess) {
                                        runCatching { app.pcBridge.syncCredentials(app.credentials) }
                                        app.registerClients()
                                        pairOk = true
                                        pairMsg = tr("ob.pair_ok", lang)
                                    } else {
                                        pairMsg = tr("ob.pair_fail", lang) + ": " +
                                            errorText(r.exceptionOrNull(), lang)
                                    }
                                    pairBusy = false
                                }
                            }
                        }
                    }
                    pairMsg?.let {
                        Spacer(Modifier.height(10.dp))
                        BasicText(it, style = TextStyle(color = if (pairOk) c.success_text else c.warning_text, fontSize = 12.sp))
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        tr("ob.pair_later", lang),
                        style = TextStyle(color = c.text_tertiary, fontSize = 11.sp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) {
                    GhostBtn(tr("ob.back", lang), c) { step-- }
                }
                PrimaryBtn(
                    if (step < steps - 1) tr("ob.next", lang) else tr("ob.done", lang),
                    c,
                ) {
                    if (step < steps - 1) step++ else onFinish()
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ObField(
    hint: String,
    value: String,
    c: net.ripster.mobile.ui.theme.RipsterColors,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .background(c.surface_raised, RoundedCornerShape(12.dp))
            .border(1.dp, c.border_subtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            BasicText(hint, style = TextStyle(color = c.text_tertiary, fontSize = 14.sp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = if (number) {
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            } else {
                androidx.compose.foundation.text.KeyboardOptions.Default
            },
            textStyle = TextStyle(color = c.text_primary, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Selectable(label: String, on: Boolean, c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (on) c.accent_fill.copy(alpha = 0.16f) else c.surface_raised, RoundedCornerShape(12.dp))
            .border(1.dp, if (on) c.accent_text else c.border_subtle, RoundedCornerShape(12.dp))
            .pressable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .background(if (on) c.accent_fill else c.surface_active)
                .border(1.dp, if (on) c.accent_fill else c.border_subtle, CircleShape),
        )
        BasicText(label, style = TextStyle(color = c.text_primary, fontSize = 15.sp))
    }
}

@Composable
private fun PrimaryBtn(label: String, c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier.background(c.accent_fill, RoundedCornerShape(12.dp))
            .pressable { onClick() }
            .padding(horizontal = 26.dp, vertical = 14.dp),
    ) {
        BasicText(label, style = TextStyle(color = androidx.compose.ui.graphics.Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun GhostBtn(label: String, c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, c.border_subtle, RoundedCornerShape(12.dp))
            .pressable { onClick() }
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        BasicText(label, style = TextStyle(color = c.text_secondary, fontSize = 15.sp))
    }
}
