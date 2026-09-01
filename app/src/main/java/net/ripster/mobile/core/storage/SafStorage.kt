package net.ripster.mobile.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Track
import java.io.File

/**
 * Перенос готового файла из кэша в папку пользователя через SAF
 * (`ACTION_OPEN_DOCUMENT_TREE`). Прямой доступ к путям на Android 11+ закрыт —
 * пишем только в дерево, которое пользователь выбрал сам, и держим на него
 * persistable-разрешение.
 *
 * Путь внутри дерева задаёт [NameTemplate]; недостающие подпапки создаём.
 */
class SafStorage(private val context: Context) {

    fun hasTree(treeUri: String): Boolean =
        treeUri.isNotBlank() && runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.canWrite() == true
        }.getOrDefault(false)

    /** Зафиксировать разрешение на дерево (вызывать из колбэка пикера). */
    fun persist(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
    }

    /**
     * Переносит [cacheFile] в дерево [treeUri] по имени из [template].
     * Возвращает `content://`-строку итогового документа или null при ошибке
     * (тогда вызывающий оставляет файл в кэше и не врёт про «сохранено»).
     */
    fun moveIntoLibrary(
        cacheFile: File,
        treeUri: String,
        template: String,
        track: Track,
        quality: QualityTier,
    ): String? {
        if (!cacheFile.exists()) return null
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
            ?: return null

        val relPath = NameTemplate.render(template, track, quality)
        val parts = relPath.split('/')
        val fileName = parts.last()
        val dirs = parts.dropLast(1)

        var dir: DocumentFile = root
        for (name in dirs) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(name)
                ?: return null
        }

        // Затираем одноимённый — повторная загрузка не должна плодить "(1)".
        dir.findFile(fileName)?.delete()
        val mime = mimeFor(quality.container)
        val doc = dir.createFile(mime, fileName) ?: return null

        val ok = runCatching {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                cacheFile.inputStream().use { it.copyTo(out) }
            } != null
        }.getOrDefault(false)

        if (!ok) {
            doc.delete()
            return null
        }
        cacheFile.delete()
        return doc.uri.toString()
    }

    private fun mimeFor(container: String): String = when (container.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
