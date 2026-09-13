package net.ripster.mobile.core.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.audio.StreamInfo
import net.ripster.mobile.core.audio.StreamProbe
import net.ripster.mobile.core.db.LibraryEntity
import java.security.MessageDigest

/**
 * Завести в Ripster музыку, которую он не качал.
 *
 * До этого выбор папки в настройках задавал ТОЛЬКО место для новых загрузок —
 * из неё ничего не читалось, и библиотека содержала ровно то, что скачано через
 * приложение. Своя коллекция на телефоне для плеера Ripster не существовала.
 *
 * Три вещи, из-за которых наивный обход даёт кашу вместо библиотеки, и что с
 * ними здесь сделано:
 *
 *  * **Многодисковые релизы.** `Альбом/CD1`, `Альбом/Disc 2` — это ОДИН альбом,
 *    а не два. Папка-диск распознаётся и пропускается: альбомом считается
 *    родитель над ней, а номер диска сохраняется отдельно.
 *  * **Несколько версий одного альбома.** «Album» и «Album (Deluxe)» рядом —
 *    это РАЗНЫЕ релизы, и сливать их нельзя: у делюкса другой треклист.
 *    Никакой «нормализации» названий здесь нет намеренно.
 *  * **Пустые теги.** Тогда единственное, что есть, — имена папок. Соглашение
 *    `Артист/Альбом/трек` покрывает подавляющее большинство коллекций; если и
 *    папок не хватает, берём имя файла как название и оставляем артиста
 *    пустым, а не выдумываем.
 *
 * Повторный импорт той же папки ничего не дублирует: идентификатор строки —
 * хеш от адреса файла, и запись просто перезаписывается.
 */
object FolderImport {

    /** Расширения, которые вообще имеет смысл заводить. */
    val AUDIO_EXT = setOf(
        "flac", "wav", "alac", "m4a", "mp3", "ogg", "opus", "aac",
        "aiff", "aif", "wv", "ape", "mpc", "dsf", "dff",
    )

    /** Папка вида `CD1`, `Disc 2`, `Диск 3` — часть релиза, а не отдельный релиз. */
    private val DISC_DIR = Regex(
        """^(cd|disc|disk|диск)\s*[-_. ]?\s*(\d{1,2})$""",
        RegexOption.IGNORE_CASE,
    )

    data class PathHint(val artist: String, val album: String, val disc: Int?)

    /**
     * Что можно понять из пути, когда теги пусты.
     *
     * [dirs] — имена папок от корня выбранного дерева к файлу.
     */
    fun hintFromDirs(dirs: List<String>): PathHint {
        val clean = dirs.filter { it.isNotBlank() }
        if (clean.isEmpty()) return PathHint("", "", null)
        val last = clean.last()
        val m = DISC_DIR.find(last.trim())
        return if (m != null) {
            // …/Артист/Альбом/CD2 — альбом уровнем выше, номер диска отсюда.
            val disc = m.groupValues[2].toIntOrNull()
            val rest = clean.dropLast(1)
            PathHint(
                artist = rest.getOrNull(rest.size - 2).orEmpty(),
                album = rest.lastOrNull().orEmpty(),
                disc = disc,
            )
        } else {
            PathHint(
                artist = clean.getOrNull(clean.size - 2).orEmpty(),
                album = last,
                disc = null,
            )
        }
    }

    /** Стабильный идентификатор строки — по адресу файла. */
    fun idFor(uri: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        return "imp:" + d.take(12).joinToString("") { "%02x".format(it) }
    }

    data class Report(
        val scanned: Int = 0,
        val added: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        /** Записей убрано, потому что файлов больше нет. */
        val forgotten: Int = 0,
    )

    /**
     * Обойти дерево и завести найденное в библиотеку.
     *
     * [onProgress] зовётся по мере обхода — экран показывает, что процесс идёт:
     * большая коллекция читается минутами, и молчание в это время неотличимо от
     * зависания.
     */
    suspend fun run(
        context: Context,
        treeUri: Uri,
        existingPaths: Set<String>,
        upsert: suspend (LibraryEntity) -> Unit,
        onProgress: (Report, String) -> Unit = { _, _ -> },
        /** Убрать запись библиотеки по её адресу. Нужен, чтобы повторный импорт
         *  забывал файлы, которых в папке больше нет. */
        forget: (suspend (String) -> Unit)? = null,
    ): Report = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext Report()
        var rep = Report()
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()

        suspend fun walk(dir: DocumentFile, dirs: List<String>) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    walk(f, dirs + (f.name ?: ""))
                    continue
                }
                val name = f.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXT) continue
                rep = rep.copy(scanned = rep.scanned + 1)
                onProgress(rep, name)

                val uri = f.uri.toString()
                seen += uri
                if (uri in existingPaths) {
                    rep = rep.copy(skipped = rep.skipped + 1)
                    continue
                }
                val info = runCatching { StreamProbe.probe(context, uri) }.getOrNull()
                if (info == null) {
                    rep = rep.copy(failed = rep.failed + 1)
                    continue
                }
                runCatching {
                    upsert(entityFor(uri, name, ext, info, hintFromDirs(dirs), now))
                    rep = rep.copy(added = rep.added + 1)
                }.onFailure { rep = rep.copy(failed = rep.failed + 1) }
            }
        }

        walk(root, emptyList())

        // Файл удалили или папку переименовали — запись должна уйти вместе с
        // ним. Иначе библиотека копит призраков: строка есть, играть нечего.
        // Трогаем ТОЛЬКО то, что лежит под импортируемым деревом: чужие записи
        // (скачанное, другие импортированные папки) не наше дело.
        if (forget != null) {
            val prefix = treeUri.toString()
            val gone = existingPaths.filter { it.startsWith(prefix) && it !in seen }
            for (p in gone) {
                runCatching { forget(p) }
                rep = rep.copy(forgotten = rep.forgotten + 1)
            }
        }
        onProgress(rep, "")
        rep
    }

    /** Единая сборка строки библиотеки из пробы — общая для полного импорта и
     *  выборочного (чтобы выбранные треки не пробировались повторно). */
    internal fun entityFor(
        uri: String, name: String, ext: String,
        info: StreamInfo, hint: PathHint, now: Long,
    ): LibraryEntity {
        val title = info.title.ifBlank { name.substringBeforeLast('.') }
        val artist = info.artist.ifBlank { hint.artist }
        val album = info.album.ifBlank { hint.album }.ifBlank { null }
        val extLossless = ext in setOf("flac", "wav", "alac", "aiff", "aif", "wv", "ape")
        val codecLossless = info.codec.lowercase().let {
            "flac" in it || "alac" in it || "lossless" in it || "pcm" in it
        }
        return LibraryEntity(
            id = idFor(uri), title = title, artist = artist, album = album,
            serviceId = "local", container = ext,
            bitrateKbps = info.bitrateKbps.takeIf { it > 0 },
            durationSec = info.durationSec, filePath = uri, sizeBytes = info.fileBytes,
            artworkUrl = null, addedAt = now,
            sampleRateHz = info.sampleRateHz.takeIf { it > 0 },
            bitDepth = info.bitDepth.takeIf { it > 0 },
            lossless = codecLossless || (extLossless && info.bitrateKbps > 700),
            fakeLossless = extLossless && !codecLossless && info.bitrateKbps in 1..700,
        )
    }

    /** Один найденный при [scan] трек — с УЖЕ собранной строкой (без повторной
     *  пробы при импорте) и полями для группировки/порядка/показа. */
    data class ScanTrack(
        val entity: LibraryEntity,
        val album: String,          // "" — «без альбома»
        val artist: String,
        val disc: Int,
        val trackNo: Int,
        val fileName: String,
        val durationSec: Int,
        val lossless: Boolean,
        /** Уже в библиотеке (тот же путь) — по умолчанию НЕ выбран. */
        val already: Boolean,
    ) {
        val uri get() = entity.filePath
    }

    data class ScanAlbum(val album: String, val artist: String, val tracks: List<ScanTrack>)

    /**
     * Обойти дерево и вернуть найденное БЕЗ записи — чтобы человек отметил
     * галочками, что заводить. Пробуем каждый файл (иначе не будет тегов для
     * порядка и вида), но в библиотеку ничего не пишем: это делает [importSelected].
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        existingPaths: Set<String>,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): List<ScanTrack> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val out = ArrayList<ScanTrack>()
        val now = System.currentTimeMillis()
        var n = 0

        suspend fun walk(dir: DocumentFile, dirs: List<String>) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) { walk(f, dirs + (f.name ?: "")); continue }
                val name = f.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXT) continue
                n++; onProgress(n, name)
                val uri = f.uri.toString()
                val info = runCatching { StreamProbe.probe(context, uri) }.getOrNull() ?: continue
                val hint = hintFromDirs(dirs)
                val ent = entityFor(uri, name, ext, info, hint, now)
                out += ScanTrack(
                    entity = ent,
                    album = (ent.album ?: "").trim(),
                    artist = ent.artist,
                    disc = info.discNo,
                    trackNo = info.trackNo,
                    fileName = name,
                    durationSec = info.durationSec,
                    lossless = ent.lossless,
                    already = uri in existingPaths,
                )
            }
        }
        walk(root, emptyList())
        out
    }

    /** Естественная сортировка по имени файла: "2" < "10" (не строкой). */
    private val NUM = Regex("""\d+""")
    private fun natKey(s: String): List<Comparable<*>> =
        NUM.split(s.lowercase()).zip(NUM.findAll(s.lowercase()).map { it.value }.toList() + "")
            .flatMap { (a, b) -> listOf(a, (b.toLongOrNull() ?: -1L)) }

    /** Сгруппировать по (альбом, артист), треки — по (диск, №, имя файла). */
    fun groupAlbums(tracks: List<ScanTrack>): List<ScanAlbum> =
        tracks.groupBy { it.album to it.artist }
            .map { (key, ts) ->
                ScanAlbum(
                    album = key.first, artist = key.second,
                    tracks = ts.sortedWith(
                        compareBy<ScanTrack> { it.disc.takeIf { d -> d > 0 } ?: 1 }
                            .thenBy { it.trackNo.takeIf { t -> t > 0 } ?: Int.MAX_VALUE }
                            .thenBy { it.fileName.lowercase() },
                    ),
                )
            }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }))

    /** Завести ТОЛЬКО отмеченное (по uri). Строки уже собраны в [scan] — не
     *  пробируем заново. */
    suspend fun importSelected(
        selected: Set<String>,
        tracks: List<ScanTrack>,
        upsert: suspend (LibraryEntity) -> Unit,
    ): Report = withContext(Dispatchers.IO) {
        var rep = Report()
        for (t in tracks) {
            if (t.uri !in selected) continue
            rep = rep.copy(scanned = rep.scanned + 1)
            runCatching { upsert(t.entity) }
                .onSuccess { rep = rep.copy(added = rep.added + 1) }
                .onFailure { rep = rep.copy(failed = rep.failed + 1) }
        }
        rep
    }
}
