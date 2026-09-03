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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.model.DownloadState
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.ui.components.DownloadOrb
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.engineErrorText
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterColors
import net.ripster.mobile.ui.theme.RipsterTheme

private sealed interface ScUiState {
    data object Idle : ScUiState
    data object Searching : ScUiState
    data class Tracking(val id: String, val title: String) : ScUiState
    data class Failed(val message: String) : ScUiState
}

/**
 * Проверка сквозного пути SoundCloud: поиск → первый трек → постановка в
 * очередь (Room) → исполнение воркером (WorkManager, foreground) → прогресс
 * из Room на экране. Не финальный экран «Загрузки» — тот получит «орб»
 * загрузок из ПК-версии (см. план). Здесь показан один трек в работе.
 */
@Composable
fun SoundCloudLiveScreen() {
    val lang = LocalAppLang.current
    val c = RipsterTheme.colors
    val scope = rememberCoroutineScope()
    val app = RipsterApp.from(LocalContext.current)

    var query by remember { mutableStateOf("Forss Flickermood") }
    var state by remember { mutableStateOf<ScUiState>(ScUiState.Idle) }
    var live by remember { mutableStateOf<net.ripster.mobile.core.model.DownloadItem?>(null) }

    val settings by app.settings.state.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: android.net.Uri? ->
        if (uri != null) {
            app.storage.persist(uri)
            app.settings.update { it.copy(downloadTreeUri = uri.toString()) }
        }
    }

    val tracking = state as? ScUiState.Tracking
    LaunchedEffect(tracking?.id) {
        val id = tracking?.id ?: return@LaunchedEffect
        app.downloads.observe(id).collectLatest { live = it }
    }

    fun start() {
        state = ScUiState.Searching
        live = null
        scope.launch {
            val client = ServiceRegistry.get(Service.SOUNDCLOUD) ?: run {
                state = ScUiState.Failed("SoundCloud client not registered"); return@launch
            }
            try {
                val track = client.search(query).tracks.firstOrNull() ?: run {
                    state = ScUiState.Failed(tr("sc.nothing_found", lang)); return@launch
                }
                val id = app.downloads.enqueue(track)
                state = ScUiState.Tracking(id, track.title)
            } catch (t: Throwable) {
                state = ScUiState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    val queue by app.downloads.observeQueue().collectAsState(initial = emptyList())

    Box(Modifier.fillMaxSize().background(c.surface_canvas)) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
    ) {
        BasicText(
            tr("sc.title", lang),
            style = TextStyle(color = c.text_primary, fontSize = 19.sp, fontWeight = FontWeight.Bold),
        )
        Box(Modifier.height(14.dp))

        Box(
            Modifier.fillMaxWidth()
                .background(c.surface_raised, RoundedCornerShape(10.dp))
                .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            if (query.isEmpty()) {
                BasicText(tr("sc.query_hint", lang), style = TextStyle(color = c.text_tertiary, fontSize = 15.sp))
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = c.text_primary, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.height(12.dp))

        val busy = state is ScUiState.Searching ||
            (live?.state == DownloadState.RUNNING || live?.state == DownloadState.QUEUED)
        Box(
            Modifier
                .background(if (busy) c.surface_raised else c.accent_fill, RoundedCornerShape(10.dp))
                .clickable(enabled = !busy) { start() }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            BasicText(
                tr("sc.start", lang),
                style = TextStyle(
                    color = if (busy) c.text_tertiary else c.text_on_fill,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                ),
            )
        }

        Box(Modifier.height(16.dp))
        Box(
            Modifier
                .background(c.surface_raised, RoundedCornerShape(8.dp))
                .clickable { pickFolder.launch(null) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val folder = settings.downloadTreeUri
            val sub = if (folder.isBlank()) tr("sc.folder_none", lang)
            else android.net.Uri.decode(folder.substringAfterLast('/'))
            Column {
                BasicText(tr("sc.pick_folder", lang), style = TextStyle(color = c.text_secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Box(Modifier.height(2.dp))
                BasicText(sub, style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
            }
        }

        Box(Modifier.height(20.dp))
        StatusBlock(state, live, lang, c)
    }

    // Орб загрузок — внизу, поверх, ничему не мешает; пустая очередь = 0 высоты.
    DownloadOrb(
        items = queue,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 18.dp),
    )
    }
}

@Composable
private fun StatusBlock(
    state: ScUiState,
    live: net.ripster.mobile.core.model.DownloadItem?,
    lang: AppLang,
    c: RipsterColors,
) {
    when {
        state is ScUiState.Searching ->
            BasicText(tr("sc.searching", lang), style = TextStyle(color = c.text_secondary, fontSize = 14.sp))

        state is ScUiState.Failed -> Column {
            BasicText(tr("sc.failed", lang), style = TextStyle(color = c.text_primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Box(Modifier.height(4.dp))
            BasicText(state.message, style = TextStyle(color = c.text_secondary, fontSize = 12.sp))
        }

        state is ScUiState.Tracking && live != null -> Column {
            BasicText(live.track.title, style = TextStyle(color = c.text_primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Box(Modifier.height(6.dp))
            val label = when (live.state) {
                DownloadState.QUEUED -> tr("sc.searching", lang)
                DownloadState.RUNNING -> tr("sc.downloading", lang) +
                    (live.fraction?.let { "  ${(it * 100).toInt()}%" } ?: "")
                DownloadState.DONE -> tr("sc.done", lang)
                DownloadState.FAILED -> tr("sc.failed", lang) + ((engineErrorText(live.errorReason, lang) ?: live.errorReason)?.let { "  ·  $it" } ?: "")
                DownloadState.CANCELLED -> tr("sc.failed", lang)
            }
            BasicText(label, style = TextStyle(color = c.text_secondary, fontSize = 13.sp))
            Box(Modifier.height(8.dp))
            ProgressBar(if (live.state == DownloadState.DONE) 1f else live.fraction, c)
            live.filePath?.let {
                Box(Modifier.height(4.dp))
                BasicText(it, style = TextStyle(color = c.text_tertiary, fontSize = 11.sp))
            }
        }

        state is ScUiState.Tracking ->
            BasicText(tr("sc.searching", lang), style = TextStyle(color = c.text_secondary, fontSize = 14.sp))

        else -> Unit
    }
}

@Composable
private fun ProgressBar(fraction: Float?, c: RipsterColors) {
    Box(
        Modifier.fillMaxWidth().height(6.dp).background(c.surface_raised, RoundedCornerShape(3.dp)),
    ) {
        val f = fraction?.coerceIn(0f, 1f) ?: 0.12f
        Box(Modifier.fillMaxWidth(f).height(6.dp).background(c.accent_fill, RoundedCornerShape(3.dp)))
    }
}
