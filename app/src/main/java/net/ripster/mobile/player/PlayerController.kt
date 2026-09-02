package net.ripster.mobile.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.ripster.mobile.core.db.LibraryEntity

/**
 * Приложенческая обёртка над [PlaybackService]: подключает [MediaController],
 * отдаёт наружу [state] (`StateFlow`) для UI (MiniPlayer / экран Now
 * Playing) и принимает команды play/pause/seek.
 */
class PlayerController(context: Context) {

    data class State(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artworkUrl: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        /** Сколько уже загружено в буфер (для «полоски кэша» на перемотке). */
        val bufferedMs: Long = 0,
        val durationMs: Long = 0,
        val hasItem: Boolean = false,
        val format: String = "",
        val lossless: Boolean = false,
        val fakeLossless: Boolean = false,
        val shuffle: Boolean = false,
        /** Повтор включён (ONE или ALL). */
        val repeat: Boolean = false,
        /** Запрашивали lossless-тир (FLAC/Hi-Res), но файл им не оказался. */
        val qualityMismatch: Boolean = false,
        /** Текущая очередь воспроизведения (для экрана «Трек-лист»). */
        val queue: List<QueueEntry> = emptyList(),
        val queueIndex: Int = 0,
        /** Путь/URI текущего файла — для панели «Спектр». */
        val currentPath: String? = null,
    )

    data class QueueEntry(
        val id: String,
        val title: String,
        val artist: String,
        val artworkUrl: String? = null,
        val durationSec: Int = 0,
        val label: String? = null,
        /** Короткая тех-строка: «FLAC · 16/44.1» / «MP3 · 320». */
        val spec: String = "",
        val lossless: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs = appContext.getSharedPreferences("playback_state", android.content.Context.MODE_PRIVATE)
    /** Отдаёт [LibraryEntity] по id — ставится из [net.ripster.mobile.RipsterApp] после сборки БД. */
    @Volatile private var idsToEntities: (suspend (List<String>) -> List<LibraryEntity>)? = null

    private var controller: MediaController? = null
    private var queueEntities: List<LibraryEntity> = emptyList()

    // ── нативный движок (Oboe) для локального lossless ──
    /** Включён в настройках. Ставится из RipsterApp по AppSettings. */
    @Volatile var nativeEnabled: Boolean = false
    private var nativeQueue: List<LibraryEntity> = emptyList()
    private val nativeActive: Boolean get() = nativeQueue.isNotEmpty()
    // Передача СЕТЕВОГО lossless-потока нативному движку: ExoPlayer играет сразу,
    // фоном тянем файлы во временные, готово → бесшовно уводим на Oboe.
    private var handoffJob: kotlinx.coroutines.Job? = null
    private var streamTemps: List<java.io.File> = emptyList()
    private fun clearStreamTemps() {
        handoffJob?.cancel(); handoffJob = null
        streamTemps.forEach { runCatching { it.delete() } }
        streamTemps = emptyList()
    }
    private fun nativeCurrent(): LibraryEntity? =
        nativeQueue.getOrNull(NativeAudioEngine.index().coerceIn(0, (nativeQueue.size - 1).coerceAtLeast(0)))

    private fun isLocalLossless(path: String?): Boolean {
        val p = path?.lowercase() ?: return false
        val local = p.startsWith("content://") || p.startsWith("file://") || p.startsWith("/")
        return local && (p.endsWith(".flac") || p.endsWith(".wav") ||
            p.endsWith(".m4a") || p.endsWith(".alac") || p.endsWith(".m4b") || p.endsWith(".mp4"))
    }

    /**
     * Попробовать нативный тракт для очереди; при неудаче — [exoFallback].
     * `true` — заявка принята (нативно или упадём на Exo сами); `false` —
     * даже не пробуем (движок выключен/недоступен/нелокальные файлы).
     */
    private fun playNative(items: List<LibraryEntity>, startIndex: Int, exoFallback: () -> Unit): Boolean {
        if (!nativeEnabled || !NativeAudioEngine.isAvailable) return false
        if (items.any { !isLocalLossless(it.filePath) } || items.isEmpty()) return false
        runCatching { controller?.stop() }
        queueEntities = emptyList()
        nativeQueue = items
        val start = startIndex.coerceIn(0, items.size - 1)
        scope.launch {
            val r = NativeAudioEngine.playQueue(
                appContext, items.map { Uri.parse(it.filePath) }, start, requireAll = true,
            )
            if (r.isSuccess) {
                items.getOrNull(start)?.let { logPlayIfNew(it.title, it.artist, it.album.orEmpty(), it.artworkUrl, it) }
                pushNativeState()
            } else {
                nativeQueue = emptyList()
                exoFallback()
            }
        }
        return true
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = pushState()
                })
            }
            maybeRestore()
            pushState()
        }, MoreExecutors.directExecutor())

        // Позиция не приходит событием — тикаем сами, пока играет. На тике
        // обновляем ТОЛЬКО позицию/буфер (не пересобираем весь список очереди
        // каждые 500мс — это лишние аллокации и перекомпоновки).
        scope.launch {
            while (true) {
                delay(1000)   // 1с достаточно для шкалы; реже = меньше перекомпоновок во время игры
                if (nativeActive) {
                    if (NativeAudioEngine.isEnded()) { NativeAudioEngine.stop(); nativeQueue = emptyList(); pushState() }
                    else pushNativeState()
                    continue
                }
                val c = controller
                if (c?.isPlaying == true) { pushState(positionOnly = true); persistPosition(c) }
            }
        }
    }

    /** Вызывается из RipsterApp после того, как БД собрана — включает восстановление. */
    fun bindLibrary(resolver: suspend (List<String>) -> List<LibraryEntity>) {
        idsToEntities = resolver
        if (controller != null && controller?.currentMediaItem == null) maybeRestore()
    }

    /** Логгер истории прослушивания — ставится из RipsterApp (пишет в play_history). */
    private var playLogger: (suspend (net.ripster.mobile.core.db.PlayEntity) -> Unit)? = null
    private var lastLoggedKey: String? = null

    fun bindPlayLog(recorder: suspend (net.ripster.mobile.core.db.PlayEntity) -> Unit) {
        playLogger = recorder
    }

    private fun logPlayIfNew(title: String, artist: String, album: String, art: String?, e: LibraryEntity?) {
        val rec = playLogger ?: return
        if (title.isBlank()) return
        val key = "$title|$artist"
        if (key == lastLoggedKey) return
        lastLoggedKey = key
        scope.launch {
            val genre = withContext(Dispatchers.IO) {
                runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        e?.filePath?.let { mmr.setDataSource(it) }
                        mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
                    } finally { runCatching { mmr.release() } }
                }.getOrNull().orEmpty()
            }
            runCatching {
                rec(
                    net.ripster.mobile.core.db.PlayEntity(
                        title = title, artist = artist, album = album.ifBlank { null },
                        genre = genre.trim(), serviceId = e?.serviceId.orEmpty(),
                        artworkUrl = art ?: e?.artworkUrl, playedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun persist(ids: List<String>, index: Int) {
        prefs.edit().putString("ids", ids.joinToString(",")).putInt("idx", index).putLong("pos", 0L).apply()
    }

    private fun persistPosition(c: MediaController) {
        prefs.edit().putInt("idx", c.currentMediaItemIndex).putLong("pos", c.currentPosition.coerceAtLeast(0)).apply()
    }

    /** Восстановить прошлую очередь — на ПАУЗЕ, с той же позиции. */
    private fun maybeRestore() {
        val c = controller ?: return
        if (c.currentMediaItem != null) return
        val ids = prefs.getString("ids", "")?.split(',')?.filter { it.isNotBlank() } ?: return
        if (ids.isEmpty()) return
        val resolve = idsToEntities ?: return
        val idx = prefs.getInt("idx", 0)
        val pos = prefs.getLong("pos", 0L)
        scope.launch {
            val ents = runCatching { resolve(ids) }.getOrNull().orEmpty()
            if (ents.isEmpty() || controller?.currentMediaItem != null) return@launch
            queueEntities = ents
            controller?.apply {
                setMediaItems(
                    ents.map { mediaItemOf(it) },
                    idx.coerceIn(0, ents.size - 1), pos,
                )
                playWhenReady = false   // стартуем на паузе
                prepare()
            }
            pushState()
        }
    }

    private fun mediaItemOf(e: LibraryEntity) = MediaItem.Builder()
        .setUri(Uri.parse(e.filePath))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(e.title).setArtist(e.artist).setAlbumTitle(e.album)
                .apply { e.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build(),
        )
        .build()

    fun play(item: LibraryEntity) {
        if (playNative(listOf(item), 0) { playExo(item) }) return
        if (nativeActive) { NativeAudioEngine.stop(); nativeQueue = emptyList() }
        playExo(item)
    }

    private fun playExo(item: LibraryEntity) {
        val c = controller ?: return
        c.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.parse(item.filePath))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .apply { item.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                        .build(),
                )
                .build(),
        )
        c.prepare()
        c.play()
    }

    fun playQueue(items: List<LibraryEntity>, startIndex: Int) {
        if (items.isEmpty()) return
        val idx = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        persist(items.map { it.id }, idx)
        if (playNative(items, idx) { playExoQueue(items, idx) }) return
        if (nativeActive) { NativeAudioEngine.stop(); nativeQueue = emptyList() }
        playExoQueue(items, idx)
    }

    private fun playExoQueue(items: List<LibraryEntity>, idx: Int) {
        val c = controller ?: return
        queueEntities = items
        c.setMediaItems(items.map { mediaItemOf(it) }, idx.coerceIn(0, items.size - 1), 0L)
        c.prepare()
        c.play()
    }

    /** Один элемент потокового воспроизведения (стрим без скачивания). */
    data class StreamItem(
        val url: String,
        val title: String,
        val artist: String,
        val artworkUrl: String? = null,
        /** Поток lossless (FLAC/ALAC) — кандидат на передачу нативному движку. */
        val lossless: Boolean = false,
        val container: String = "",
    )

    /**
     * Потоковое воспроизведение прямых стрим-URL (релиз/жанровая станция) — без
     * скачивания на диск. queueEntities обнуляется: это не библиотечная очередь,
     * строка формата/бейджи качества берутся из заголовков потока по факту.
     */
    fun playStream(items: List<StreamItem>, startIndex: Int = 0) {
        val c = controller ?: return
        if (items.isEmpty()) return
        clearStreamTemps()
        if (nativeActive) { runCatching { NativeAudioEngine.stop() }; nativeQueue = emptyList() }
        queueEntities = emptyList()
        val media = items.map { s ->
            MediaItem.Builder()
                .setUri(Uri.parse(s.url))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title).setArtist(s.artist)
                        .apply { s.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                        .build(),
                )
                .build()
        }
        c.setMediaItems(media, startIndex.coerceIn(0, items.size - 1), 0L)
        prefs.edit().remove("ids").apply()   // потоковую очередь не восстанавливаем
        c.playWhenReady = true
        c.prepare()
        c.play()
        // Нативная передача СТРИМА на Oboe (bit-perfect) — код готов, но НЕ
        // включён: на x86-эмуляторе фиделити не проверить, а хэндофф в тесте не
        // сработал стабильно. Возврат к этому — на реальном arm64-устройстве.
        // armNativeHandoff(items, startIndex.coerceIn(0, items.size - 1))
    }

    /**
     * Если включён нативный движок и в потоковой очереди есть lossless — фоном
     * тянем эти треки во временные файлы, затем БЕСШОВНО переключаем
     * воспроизведение с ExoPlayer на Oboe с той же позиции (bit-perfect тракт).
     * MP3/AAC-потоки не трогаем — остаются на ExoPlayer.
     */
    private fun armNativeHandoff(items: List<StreamItem>, startIndex: Int) {
        handoffJob?.cancel()
        if (!nativeEnabled || !NativeAudioEngine.isAvailable) return
        if (items.getOrNull(startIndex)?.lossless != true) return
        val cap = items.take(8)
        handoffJob = scope.launch {
            // Даём ExoPlayer фору — он должен УСПЕТЬ забуферить и заиграть без
            // конкуренции за сеть. Тянем СНАЧАЛА только текущий трек.
            delay(3_000)
            val curFile = withContext(Dispatchers.IO) {
                runCatching {
                    net.ripster.mobile.core.audio.SpectrumSource.fetchPlayingToTemp(
                        appContext, cap[startIndex].url, capBytes = 160L * 1024 * 1024, prefix = "nae",
                    )
                }.getOrNull()
            } ?: return@launch
            if (curFile.length() < 8192) { curFile.delete(); return@launch }
            val c = controller ?: return@launch
            // ещё играем этот же поток (не переключились, не ушли на нативный)?
            if (queueEntities.isNotEmpty() || nativeActive || c.currentMediaItemIndex != startIndex) {
                curFile.delete(); return@launch
            }
            val exoPos = c.currentPosition.coerceAtLeast(0)
            val ent = LibraryEntity(
                id = "stream:${cap[startIndex].url.hashCode()}",
                title = cap[startIndex].title, artist = cap[startIndex].artist, album = null,
                serviceId = "", container = cap[startIndex].container.ifBlank { "flac" },
                bitrateKbps = null, durationSec = 0, filePath = curFile.absolutePath,
                sizeBytes = curFile.length(), artworkUrl = cap[startIndex].artworkUrl,
                addedAt = System.currentTimeMillis(), lossless = true,
            )
            val r = NativeAudioEngine.playQueue(appContext, listOf(Uri.fromFile(curFile)), 0, requireAll = false)
            if (!r.isSuccess) { curFile.delete(); return@launch }
            nativeQueue = listOf(ent)
            streamTemps = listOf(curFile)
            NativeAudioEngine.seekMs(exoPos)
            runCatching { c.pause() }
            pushNativeState()
            // v1: нативный тракт держит ТОЛЬКО текущий трек стрима. Трек кончился —
            // тик-цикл увидит isEnded и вернёт управление; следующий пойдёт
            // обычным путём (снова с хэндоффом).
        }
    }

    /** Дорезолвить хвост станции и дописать в конец текущей очереди. */
    fun appendStream(items: List<StreamItem>) {
        val c = controller ?: return
        if (items.isEmpty()) return
        c.addMediaItems(
            items.map { s ->
                MediaItem.Builder()
                    .setUri(Uri.parse(s.url))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s.title).setArtist(s.artist)
                            .apply { s.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                            .build(),
                    )
                    .build()
            },
        )
    }

    fun togglePlay() {
        if (nativeActive) {
            if (NativeAudioEngine.isPlaying()) NativeAudioEngine.pause() else NativeAudioEngine.resume()
            pushNativeState()
            return
        }
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Полностью остановить и очистить — по кнопке «закрыть плеер». */
    fun stop() {
        clearStreamTemps()
        if (nativeActive) { runCatching { NativeAudioEngine.stop() }; nativeQueue = emptyList() }
        runCatching { controller?.stop() }
        runCatching { controller?.clearMediaItems() }
        queueEntities = emptyList()
        pushState()
    }

    fun seekTo(ms: Long) {
        if (nativeActive) { NativeAudioEngine.seekMs(ms); pushNativeState(); return }
        controller?.seekTo(ms)
    }
    fun next() {
        if (nativeActive) { NativeAudioEngine.next(); pushNativeState(); return }
        controller?.seekToNextMediaItem()
    }
    fun previous() {
        if (nativeActive) { NativeAudioEngine.previous(); pushNativeState(); return }
        controller?.seekToPreviousMediaItem()
    }

    private fun pushNativeState() {
        val it = nativeCurrent() ?: run { pushState(); return }
        val idx = NativeAudioEngine.index().coerceIn(0, (nativeQueue.size - 1).coerceAtLeast(0))
        logPlayIfNew(it.title, it.artist, it.album.orEmpty(), it.artworkUrl, it)
        _state.value = State(
            title = it.title, artist = it.artist, album = it.album.orEmpty(),
            artworkUrl = it.artworkUrl,
            isPlaying = NativeAudioEngine.isPlaying(),
            positionMs = NativeAudioEngine.positionMs().coerceAtLeast(0),
            bufferedMs = NativeAudioEngine.durationMs(),   // весь файл на диске
            durationMs = NativeAudioEngine.durationMs(),
            hasItem = true,
            format = NativeAudioEngine.formatLine(),
            lossless = it.lossless,
            fakeLossless = it.fakeLossless,
            currentPath = it.filePath,
            queue = nativeQueue.map { e ->
                QueueEntry(e.id, e.title, e.artist, e.artworkUrl, e.durationSec, e.label, formatLine(e), e.lossless)
            },
            queueIndex = idx,
        )
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        pushState()
    }

    /** OFF → ALL → ONE → OFF (как «мощный плеер»). */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        pushState()
    }

    /** Прыгнуть на позицию в очереди (экран «Трек-лист»). */
    fun playIndex(index: Int) {
        if (nativeActive) {
            if (index in nativeQueue.indices) { NativeAudioEngine.setIndex(index); pushNativeState() }
            return
        }
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) { c.seekTo(index, 0L); c.play() }
    }

    /** Убрать трек из очереди по позиции. Текущий убрать нельзя — молча игнор. */
    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount || index == c.currentMediaItemIndex) return
        c.removeMediaItem(index)
        queueEntities = queueEntities.toMutableList().also { if (index < it.size) it.removeAt(index) }
        pushState()
    }

    companion object {
        /** «FLAC · 24-bit/96 kHz» или «MP3 · 320 kbps». */
        fun formatLine(e: LibraryEntity): String {
            val c = e.container.uppercase()
            if (e.lossless) {
                val bits = e.bitDepth?.let { "$it-bit" }
                val khz = e.sampleRateHz?.let {
                    val v = it / 1000.0
                    (if (v % 1.0 == 0.0) "%.0f".format(v) else "%.1f".format(v)) + " kHz"
                }
                val spec = listOfNotNull(bits, khz).joinToString("/")
                return listOf(c, spec).filter { it.isNotBlank() }.joinToString("  ·  ")
            }
            return listOfNotNull(c, e.bitrateKbps?.let { "$it kbps" }).joinToString("  ·  ")
        }

        /** Запрошенный тир подразумевал lossless (по его id). */
        private fun requestedLossless(id: String?): Boolean {
            val s = id?.lowercase() ?: return false
            return s.startsWith("flac") || "lossless" in s || "hires" in s || "hi_res" in s
        }

        /** «Просил FLAC — получил lossy»: запрошен lossless-тир, а заголовок — нет. */
        fun isQualityMismatch(e: LibraryEntity): Boolean =
            requestedLossless(e.requestedQualityId) && !e.lossless && !e.fakeLossless &&
                e.container.isNotBlank()
    }

    private fun pushState(positionOnly: Boolean = false) {
        val c = controller
        if (c == null || c.currentMediaItem == null) {
            _state.value = State()
            return
        }
        if (positionOnly && _state.value.hasItem) {
            // дёшево: только то, что меняется на тике
            _state.value = _state.value.copy(
                positionMs = c.currentPosition.coerceAtLeast(0),
                bufferedMs = c.bufferedPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                isPlaying = c.isPlaying,
            )
            return
        }
        val md = c.mediaMetadata
        val e = queueEntities.getOrNull(c.currentMediaItemIndex)
        val tTitle = md.title?.toString().orEmpty()
        val tArtist = md.artist?.toString().orEmpty()
        val tAlbum = md.albumTitle?.toString().orEmpty()
        logPlayIfNew(tTitle, tArtist, tAlbum, md.artworkUri?.toString(), e)
        _state.value = State(
            title = tTitle,
            artist = tArtist,
            album = tAlbum,
            artworkUrl = md.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            bufferedMs = c.bufferedPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            hasItem = true,
            format = e?.let { formatLine(it) } ?: "",
            lossless = e?.lossless ?: false,
            fakeLossless = e?.fakeLossless ?: false,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode != Player.REPEAT_MODE_OFF,
            qualityMismatch = e?.let { isQualityMismatch(it) } ?: false,
            queue = queueEntities.map {
                QueueEntry(
                    id = it.id,
                    title = it.title,
                    artist = it.artist,
                    artworkUrl = it.artworkUrl,
                    durationSec = it.durationSec,
                    label = it.label,
                    spec = formatLine(it),
                    lossless = it.lossless,
                )
            },
            queueIndex = c.currentMediaItemIndex,
            // Скачанный файл → его путь; потоковое воспроизведение → URI потока
            // (панель «Спектр» вытянет его сама и построит спектр по нему).
            currentPath = e?.filePath
                ?: c.currentMediaItem?.localConfiguration?.uri?.toString(),
        )
    }
}
