package net.ripster.mobile.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.diag.Diagnostics
import net.ripster.mobile.ui.components.ButtonLevel
import net.ripster.mobile.ui.components.RipsterButton
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.tr
import net.ripster.mobile.ui.theme.RipsterColors
import java.io.File

/**
 * «Отчёт о неполадке»: собрать всё и отдать одной кнопкой.
 *
 * Просьба владельца 06.09.2026: «чтобы пользователь мог одной кнопкой
 * скопировать весь лог и отправить мне».
 *
 * Отчёт ПОКАЗЫВАЕТСЯ до отправки, и это не украшение. Человек отправляет
 * файл со своего устройства в чужой чат — он вправе увидеть, что именно
 * уходит. Заодно это единственная честная проверка вычистки секретов: если
 * бы что-то просочилось, оно было бы видно здесь.
 *
 * Две кнопки, потому что это два разных случая. Буфер обмена — когда человек
 * уже в переписке и просто вставит текст. Отправка файлом — когда лог
 * длинный: мессенджеры режут длинные сообщения, а вложение доходит целиком.
 */
@Composable
internal fun DiagnosticsSection(lang: AppLang, c: RipsterColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    // Собираем при открытии: сбор идёт секунды, и заставлять человека жать
    // ещё одну кнопку, чтобы просто увидеть отчёт, незачем.
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            runCatching { Diagnostics.build(ctx) }
                .getOrElse { "report build failed: ${it.javaClass.simpleName}: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface_canvas)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            BasicText(
                tr("diag.hint", lang),
                style = TextStyle(color = c.text_secondary, fontSize = 13.sp),
            )
            Spacer(Modifier.height(14.dp))

            val text = report
            // Кнопки — из дизайн-системы: уровни, состояния и цвет отключённой
            // кнопки там уже решены, и второй набор правил рядом разъехался бы
            // с первым при первой же смене темы.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RipsterButton(
                    text = tr("diag.copy", lang),
                    onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Ripster report", text))
                        note = tr("diag.copied", lang)
                    },
                    level = ButtonLevel.Primary,
                    enabled = text != null,
                )
                RipsterButton(
                    text = tr("diag.send", lang),
                    onClick = {
                        scope.launch {
                            note = runCatching { share(ctx, text.orEmpty(), lang) }
                                .fold({ "" }, { tr("diag.send_failed", lang) })
                        }
                    },
                    level = ButtonLevel.Standard,
                    enabled = text != null,
                )
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                BasicText(note, style = TextStyle(color = c.text_tertiary, fontSize = 12.sp))
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border_subtle))

        // Сам отчёт. Моноширинный и прокручиваемый: это лог, и читать его
        // пропорциональным шрифтом с переносами по словам невозможно.
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
            BasicText(
                report ?: tr("diag.collecting", lang),
                style = TextStyle(
                    color = c.text_tertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

/**
 * Отдать отчёт как ФАЙЛ, а не как текст в `EXTRA_TEXT`.
 *
 * Длинный текст в extra Intent-а упирается в лимит транзакции Binder (около
 * мегабайта на всё), и приложение падает или получатель молча получает
 * обрезок. Вложение доходит целиком и не зависит от того, как мессенджер
 * обходится с длинными сообщениями.
 */
private fun share(ctx: android.content.Context, text: String, lang: AppLang) {
    val dir = File(ctx.cacheDir, "diag").apply { mkdirs() }
    // Имя постоянное: старый отчёт перезаписывается, а не копится в кэше.
    val f = File(dir, "ripster-report.txt")
    f.writeText(text)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Ripster Mobile report")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(
        Intent.createChooser(send, tr("diag.send", lang))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
