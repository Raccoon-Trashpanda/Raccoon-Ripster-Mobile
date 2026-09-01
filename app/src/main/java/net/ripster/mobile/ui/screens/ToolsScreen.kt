package net.ripster.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ripster.mobile.core.audio.AudioConverter
import net.ripster.mobile.core.audio.Spectrogram
import net.ripster.mobile.ui.i18n.LocalAppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterTheme

/**
 * «Инструменты» — отдельная вкладка нижнего меню. Две утилиты, работающие с
 * ЛЮБЫМ файлом (не только из библиотеки): спектр/проверка качества и конвертер
 * формата. Обе — на устройстве, без ffmpeg.
 */
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val c = RipsterTheme.colors
    val lang = LocalAppLang.current

    Column(
        modifier.fillMaxSize().background(c.surface_canvas)
            .verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        BasicText(
            tr("tools.title", lang),
            style = TextStyle(color = c.text_primary, fontSize = 20.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(14.dp))
        SpectrumTool(c, lang)
        Spacer(Modifier.height(16.dp))
        ConverterTool(c, lang)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SpectrumTool(c: net.ripster.mobile.ui.theme.RipsterColors, lang: net.ripster.mobile.ui.i18n.AppLang) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Spectrogram.Result?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true; result = null; err = null
        scope.launch {
            val ext = ctx.contentResolver.getType(uri)?.substringAfterLast('/')
            val r = runCatching {
                Spectrogram.analyze(ctx, uri.toString(), Spectrogram.Style.RIPSTER, heightPx = 360, containerExt = ext)
            }.getOrNull()
            busy = false
            if (r == null) err = tr("tools.failed", lang) else result = r
        }
    }

    Card(c) {
        BasicText(tr("tools.spec_title", lang), style = TextStyle(color = c.text_primary, fontSize = 15.sp, fontWeight = FontWeight.W700))
        Spacer(Modifier.height(6.dp))
        BasicText(tr("tools.spec_desc", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp, lineHeight = 17.sp))
        Spacer(Modifier.height(12.dp))
        PrimaryBtn(if (busy) tr("tools.analyzing", lang) else tr("tools.pick_file", lang), c, enabled = !busy) {
            picker.launch(arrayOf("audio/*"))
        }
        err?.let { Spacer(Modifier.height(10.dp)); BasicText(it, style = TextStyle(color = c.warning_text, fontSize = 12.sp)) }
        result?.let { r ->
            Spacer(Modifier.height(12.dp))
            Image(
                bitmap = r.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(900f / 460f)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFF111318))
                    .border(1.dp, c.border_subtle, RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(10.dp))
            val (mark, col) = when (r.verdict) {
                Spectrogram.Verdict.LOSSLESS -> "✓" to c.accent_text
                Spectrogram.Verdict.LOSSLESS_SOFT -> "✓" to c.text_secondary
                Spectrogram.Verdict.LOSSY -> "·" to c.text_secondary
                Spectrogram.Verdict.FAKE -> "⚠" to c.warning_text
                else -> "?" to c.text_tertiary
            }
            BasicText(
                "$mark  " + tr("spec.v_${r.verdict.name.lowercase()}", lang)
                    .replace("{cut}", "%.1f".format(r.cutoffKHz))
                    .replace("{ny}", "%.1f".format(r.sampleRateHz / 2000f)),
                style = TextStyle(color = col, fontSize = 12.5.sp, fontWeight = FontWeight.W600, lineHeight = 18.sp),
            )
        }
    }
}

@Composable
private fun ConverterTool(c: net.ripster.mobile.ui.theme.RipsterColors, lang: net.ripster.mobile.ui.i18n.AppLang) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<android.net.Uri?>(null) }
    var target by remember { mutableStateOf(AudioConverter.Target.WAV) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val srcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        src = uri; status = null
    }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            if (target == AudioConverter.Target.WAV) "audio/x-wav" else "audio/mp4",
        ),
    ) { dst ->
        val s = src
        if (dst == null || s == null) return@rememberLauncherForActivityResult
        busy = true; status = tr("tools.converting", lang)
        scope.launch {
            val r = AudioConverter.convert(ctx, s, dst, target)
            busy = false
            status = r.fold(
                onSuccess = { tr("tools.done", lang) },
                onFailure = { tr("tools.failed", lang) + ": " + (it.message ?: "") },
            )
        }
    }

    Card(c) {
        BasicText(tr("tools.conv_title", lang), style = TextStyle(color = c.text_primary, fontSize = 15.sp, fontWeight = FontWeight.W700))
        Spacer(Modifier.height(6.dp))
        BasicText(tr("tools.conv_desc", lang), style = TextStyle(color = c.text_tertiary, fontSize = 12.sp, lineHeight = 17.sp))
        Spacer(Modifier.height(12.dp))
        PrimaryBtn(tr("tools.pick_file", lang), c, enabled = !busy) { srcPicker.launch(arrayOf("audio/*")) }
        src?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it.lastPathSegment ?: it.toString(), maxLines = 1, style = TextStyle(color = c.text_secondary, fontSize = 11.sp))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FmtChip(tr("tools.fmt_wav", lang), target == AudioConverter.Target.WAV, c) { target = AudioConverter.Target.WAV }
            FmtChip(tr("tools.fmt_m4a", lang), target == AudioConverter.Target.M4A_AAC, c) { target = AudioConverter.Target.M4A_AAC }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryBtn(
            if (busy) tr("tools.converting", lang) else tr("tools.conv_title", lang),
            c, enabled = src != null && !busy,
        ) {
            val ext = if (target == AudioConverter.Target.WAV) "wav" else "m4a"
            savePicker.launch("ripster-convert.$ext")
        }
        status?.let { Spacer(Modifier.height(10.dp)); BasicText(it, style = TextStyle(color = c.text_secondary, fontSize = 12.sp)) }
    }
}

// ── детали ──────────────────────────────────────────────────────────────


@Composable
private fun Card(c: net.ripster.mobile.ui.theme.RipsterColors, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(c.surface_raised, RoundedCornerShape(14.dp))
            .border(1.dp, c.border_subtle, RoundedCornerShape(14.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun PrimaryBtn(label: String, c: net.ripster.mobile.ui.theme.RipsterColors, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (enabled) c.accent_fill else c.surface_active, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (enabled) c.text_on_fill else c.text_tertiary,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun FmtChip(label: String, on: Boolean, c: net.ripster.mobile.ui.theme.RipsterColors, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (on) c.surface_active else c.surface_raised)
            .border(1.dp, if (on) c.accent_text else c.border_subtle, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicText(label, style = TextStyle(color = if (on) c.accent_text else c.text_tertiary, fontSize = 11.sp))
    }
}
