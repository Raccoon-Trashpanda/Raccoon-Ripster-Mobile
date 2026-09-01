package net.ripster.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.settings.CredentialStore
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * Ручной ввод учёток сервисов + проверка, что шифрованный стор переживает
 * перезапуск. Не финальный экран настроек — просто редактор всех полей
 * [CredentialStore.Key]. Синк с ПК по сопряжению будет наполнять тот же стор.
 */
@Composable
fun ServiceAccountsScreen() {
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors
    val app = RipsterApp.from(LocalContext.current)
    val store = app.credentials

    val edits = remember {
        mutableStateMapOf<CredentialStore.Key, String>().apply {
            CredentialStore.Key.entries.forEach { put(it, store.get(it) ?: "") }
        }
    }
    var savedTick by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.surface_canvas)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        BasicText(
            tr("acc.title", lang),
            style = TextStyle(color = c.text_primary, fontSize = 19.sp, fontWeight = FontWeight.Bold),
        )
        Box(Modifier.height(6.dp))
        BasicText(tr("acc.hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
        Box(Modifier.height(14.dp))

        TidalLoginBlock()
        Box(Modifier.height(10.dp))
        net.ripster.mobile.ui.screens.cast.YandexStationBlock()
        Box(Modifier.height(16.dp))

        CredentialStore.Key.entries.forEach { key ->
            BasicText(
                key.id,
                style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
            Box(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface_raised, RoundedCornerShape(8.dp))
                    .border(1.dp, c.border_subtle, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val v = edits[key] ?: ""
                if (v.isEmpty()) {
                    BasicText(tr("acc.empty", lang), style = TextStyle(color = c.text_tertiary, fontSize = 13.sp))
                }
                BasicTextField(
                    value = v,
                    onValueChange = { edits[key] = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text_primary, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.height(12.dp))
        }

        Box(Modifier.height(4.dp))
        Box(
            Modifier
                .background(c.accent_fill, RoundedCornerShape(9.dp))
                .clickable {
                    CredentialStore.Key.entries.forEach { key ->
                        val newV = edits[key]?.trim().orEmpty()
                        if (newV != (store.get(key) ?: "")) {
                            store.set(key, newV.ifEmpty { null })
                        }
                    }
                    app.registerClients() // подхватить новый токен SoundCloud
                    savedTick++
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            BasicText(
                if (savedTick > 0) tr("acc.saved", lang) else tr("acc.save", lang),
                style = TextStyle(color = c.text_on_fill, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }

        Box(Modifier.height(10.dp))
        Box(
            Modifier
                .clickable {
                    store.clearAll()
                    CredentialStore.Key.entries.forEach { edits[it] = "" }
                    app.registerClients()
                    savedTick = 0
                }
                .padding(vertical = 8.dp),
        ) {
            BasicText(tr("acc.clear", lang), style = TextStyle(color = c.danger_text, fontSize = 13.sp))
        }
    }
}
