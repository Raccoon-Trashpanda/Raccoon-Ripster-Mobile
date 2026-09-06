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
        /**
         * Обещание БЫЛО и оно сдержано: сохранён запрошенный тир, файл измерен,
         * и класс совпал. `false` означает «сверять не с чем», а не «плохо» —
         * у файла из папки или у потока обещания просто нет.
         */
        val qualityVerified: Boolean = false,
        /** Текущая очередь воспроизведения (для экрана «Трек-лист»). */
        val queue: List<QueueEntry> = emptyList(),
        val queueIndex: Int = 0,
        /** Путь/URI текущего файла — для панели «Спектр». */
        val currentPath: String? = null,
        /** Отдаётся ли звук точь-в-точь как в файле. `null` — играет не нативный тракт,
         *  и сказать про bit-perfect нечего. Текст собирает UI — движок словами не говорит. */
        val rateNote: NativeAudioEngine.RateNote? = null,
        /** Частота, которую реально дало устройство, Hz. */
        val grantedRateHz: Int = 0,
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
    private var _nativeQueue: List<LibraryEntity> = emptyList()

    /**
     * Очередь нативного тракта. Через сеттер, а не поле: медиасессию нужно
     * отдавать нативному игроку и возвращать ExoPlayer РОВНО тогда, когда
     * очередь появляется и пропадает. Присваиваний в коде десяток, и помнить
     * про подмену в каждом — верный способ однажды забыть.
     */
    private var nativeQueue: List<LibraryEntity>
        get() = _nativeQueue
        set(value) {
            val was = _nativeQueue.isNotEmpty()
            _nativeQueue = value
            val now = value.isNotEmpty()
            if (was != now) bindSession(now)
        }

    /**
     * Игрок медиасессии на время работы нативного тракта. Пока его не было,
     * система показывала прошлый трек ExoPlayer и его же кнопками управляла.
     */
    private val nativeSession: NativeSessionPlayer by lazy {
        NativeSessionPlayer(
            looper = android.os.Looper.getMainLooper(),
            queue = { nativeQueue },
            onPlay = { NativeAudioEngine.resume(); pushNativeState() },
            onPause = { NativeAudioEngine.pause(); pushNativeState() },
            onNext = { NativeAudioEngine.next(); pushNativeState() },
            onPrevious = { NativeAudioEngine.previous(); pushNativeState() },
            onSeek = { NativeAudioEngine.seekMs(it); pushNativeState() },
            onStop = { stop() },
        )
    }

    /** Отдать сессию нативному тракту или вернуть её ExoPlayer. */
    private fun bindSession(toNative: Boolean) {
        val svc = PlaybackService.live ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            runCatching { svc.useSessionPlayer(if (toNative) nativeSession else null) }
        }
    }
    private val nativeActive: Boolean get() = _nativeQueue.isNotEmpty()
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
                if (c?.isPlaying == true) maybeExtendQueue(c)
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

    /**
     * Кого человек слушает — для станции. Ставится из RipsterApp тем же
     * способом, что и логгер: контроллер к БД напрямую не ходит.
     * Не поставили — вкус просто не участвует, это законное «не знаю».
     */
    private var tasteProvider: (suspend () -> List<String>)? = null

    fun bindTaste(provider: suspend () -> List<String>) {
        tasteProvider = provider
    }

    /**
     * Какое качество просил человек. Продолжение эфира обязано спрашивать то же
     * самое, что и обычное воспроизведение.
     *
     * До 06.09.2026 автодобор звал `toStreamItems(..., quality = emptyList())` —
     * с пустым списком предпочтений, тогда как ВСЕ остальные вызовы передавали
     * настройки. Что вернёт сервис на пустой список, не определено: у части
     * клиентов это «ни один тир не подошёл», то есть пустой ответ. Добор молча
     * не давал ничего, очередь не росла, и музыка кончалась на последнем треке
     * (жалоба владельца: «4 трека вижу, а потом упёрлись, последний доиграет и
     * стоп»).
     */
    private var qualityProvider: (() -> List<String>)? = null

    fun bindQuality(provider: () -> List<String>) {
        qualityProvider = provider
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
    // ── «Волна»: очередь не должна кончаться тишиной ──────────────────────
    //
    // Включив ОДИН трек, человек дослушивал его и попадал в тишину: продолжения
    // не существовало вовсе — ни обработчика конца очереди, ни автодобора
    // (жалоба владельца 04.09.2026: «включая 1 трек, он допел и всё встало»).
    //
    // Продолжение строится ОТ ТЕКУЩЕГО трека — по его исполнителю, через тот же
    // StationBuilder, что собирает жанровые станции на Главной. Сеять случайным
    // запросом нельзя: сегодня мы уже ловили подмену чужими треками, и «волна»
    // из посторонних песен была бы той же ошибкой, только медленной.
    @Volatile private var extending = false
    @Volatile private var lastSeed = ""

    private fun maybeExtendQueue(c: MediaController) {
        if (extending) return
        // Добираем заранее — на последнем треке, а не после тишины.
        val left = c.mediaItemCount - c.currentMediaItemIndex - 1
        if (left > 0) return
        val md = c.mediaMetadata
        val artist = (md.artist ?: "").toString().trim()
        val title = (md.title ?: "").toString().trim()
        if (artist.isBlank() && title.isBlank()) return
        val seed = "$artist|$title"
        if (seed == lastSeed) return          // по этому треку уже добирали
        extending = true
        scope.launch {
            try {
                val more = net.ripster.mobile.core.service.StationBuilder.build(
                    scGenreSlug = "",
                    fallbackQuery = artist.ifBlank { title },
                    size = 12,
                    // Продолжение эфира тоже смотрит, кого человек слушает.
                    taste = net.ripster.mobile.core.service.StationRanker.Taste.of(
                        tasteProvider?.let { p -> runCatching { p() }.getOrDefault(emptyList()) }
                            ?: emptyList(),
                    ),
                )
                // Сам текущий трек в продолжение не берём.
                val fresh = more.filterNot {
                    it.title.equals(title, true) && it.artist.equals(artist, true)
                }
                if (fresh.isEmpty()) return@launch
                val items = net.ripster.mobile.core.service.StreamResolver
                    .toStreamItems(fresh, quality = qualityProvider?.invoke().orEmpty(), limit = 12)
                if (items.isNotEmpty()) {
                    appendStream(items)
                    // Отмечаем ТОЛЬКО удачу. Раньше отметка ставилась до попытки,
                    // и одна неудача запирала добор по этому треку навсегда: тишина
                    // становилась окончательной там, где следующая попытка вполне
                    // могла сработать.
                    lastSeed = seed
                } else {
                    // По-английски: это журнал, а не текст для человека —
                    // сторож i18n прав, что ловит кириллицу в боевом коде.
                    android.util.Log.i(
                        "RipsterPlayer",
                        "queue extend: none of ${fresh.size} candidate tracks returned a stream",
                    )
                }
            } catch (_: Throwable) {
                // Молча: продолжение — удобство, а не обещание. Ошибка здесь не
                // должна прерывать то, что уже играет.
            } finally {
                extending = false
            }
        }
    }

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

    /** Очередь пуста — добавлять некуда, надо просто начать играть. */
    private fun queueIsEmpty(): Boolean =
        nativeQueue.isEmpty() && (controller?.mediaItemCount ?: 0) == 0

    /**
     * Добавить в КОНЕЦ очереди, не сбивая то, что играет сейчас.
     *
     * Владелец: «в трек лист можно завести что угодно, даже трек из поиска, из
     * плеера, из библиотеки». До этого ▶ на любой карточке ЗАМЕНЯЛ очередь
     * целиком — собрать свой список из разных мест было нечем.
     *
     * Три случая, и в каждом кнопка обязана сделать что-то видимое:
     *
     *  * очередь пуста — добавлять некуда, просто начинаем играть добавленное;
     *  * играет обычный тракт — дописываем в конец, текущий трек не трогаем;
     *  * играет нативный движок — он умеет только локальные lossless-файлы по
     *    файловым дескрипторам, поток в его очередь не встаёт. Тогда очередь
     *    переезжает на обычный тракт с того же места и той же позиции: слышен
     *    короткий стык, но воспроизведение продолжается — это честнее, чем
     *    молча проглотить добавление или оборвать музыку.
     */
    fun enqueue(items: List<StreamItem>) {
        if (items.isEmpty()) return
        if (queueIsEmpty()) { playStream(items); return }
        if (nativeActive) { handOffNativeToExo(); }
        appendStream(items)
    }

    /**
     * То же для записей библиотеки — «добавить в очередь» из своей коллекции.
     *
     * [queueEntities] держится в синхроне с очередью плеера: из него берутся
     * строка формата и бейджи качества, и запись, добавленная мимо него,
     * показывалась бы в списке без них.
     */
    fun enqueueLibrary(items: List<LibraryEntity>) {
        if (items.isEmpty()) return
        if (queueIsEmpty()) { playQueue(items, 0); return }
        if (nativeActive) {
            // Дописать в нативную очередь нельзя — nLoadQueue грузит её целиком.
            // Локальный lossless перезагружаем нативно (движок остаётся), всё
            // остальное уводим на обычный тракт.
            if (items.all { isLocalLossless(it.filePath) }) { reloadNative(nativeQueue + items); return }
            handOffNativeToExo()
        }
        val c = controller ?: return
        queueEntities = queueEntities + items
        c.addMediaItems(items.map { mediaItemOf(it) })
    }

    /** Перевести играющее с нативного тракта на Exo, сохранив место и позицию. */
    private fun handOffNativeToExo() {
        val c = controller ?: return
        val items = nativeQueue
        val idx = NativeAudioEngine.index().coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val pos = runCatching { NativeAudioEngine.positionMs() }.getOrDefault(0L)
        runCatching { NativeAudioEngine.stop() }
        nativeQueue = emptyList()
        queueEntities = items
        c.setMediaItems(items.map { mediaItemOf(it) }, idx, pos)
        c.prepare()
        c.play()
    }

    /** Перезагрузить нативную очередь целиком, вернувшись на то же место. */
    private fun reloadNative(items: List<LibraryEntity>) {
        val idx = NativeAudioEngine.index().coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val pos = runCatching { NativeAudioEngine.positionMs() }.getOrDefault(0L)
        nativeQueue = items
        scope.launch {
            val r = NativeAudioEngine.playQueue(
                appContext, items.map { Uri.parse(it.filePath) }, idx, requireAll = true,
            )
            if (r.isSuccess) {
                runCatching { NativeAudioEngine.seekMs(pos) }
                pushNativeState()
            } else {
                // Движок не принял расширенную очередь — не оставляем тишину.
                nativeQueue = emptyList()
                playQueue(items, idx)
            }
        }
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
        // Заодно освежаем медиасессию: экран блокировки и Bluetooth читают её,
        // а не наше состояние.
        runCatching { nativeSession.refresh() }
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
            rateNote = NativeAudioEngine.rateNote().first,
            grantedRateHz = NativeAudioEngine.rateNote().second,
            lossless = it.lossless,
            fakeLossless = it.fakeLossless,
            qualityMismatch = isQualityMismatch(it),
            qualityVerified = isQualityVerified(it),
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

    /** Живая позиция прямо из движка — для видов, которым 1-секундный тик
     *  push-state слишком груб. Синхронный текст по нему «догонял» песню
     *  (жалоба 03.09.2026). Дёшево, читать можно хоть каждый кадр. */
    fun livePositionMs(): Long =
        if (nativeActive) NativeAudioEngine.positionMs().coerceAtLeast(0)
        else controller?.currentPosition?.coerceAtLeast(0) ?: 0L

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

        /**
         * Обещание было и сдержано.
         *
         * Три условия, и все обязательны: тир запрашивали (иначе сверять не с
         * чем), файл измерен (иначе нечем сверять), и КЛАСС совпал — просили
         * lossless и получили lossless, либо просили lossy и получили lossy.
         *
         * Проверяем класс, а не точные цифры: обещание тира «FLAC» — это
         * обещание не терять данные, а не обещание конкретной частоты.
         * Утверждать большее было бы той же самонадеянностью, из-за которой у
         * конкурента золотой Hi-Res висит над апскейлом.
         */
        fun isQualityVerified(e: LibraryEntity): Boolean {
            val promised = e.requestedQualityId?.takeIf { it.isNotBlank() } ?: return false
            if (e.container.isBlank()) return false      // не измеряли
            if (e.fakeLossless) return false             // это отдельный разговор
            return if (requestedLossless(promised)) e.lossless else !e.lossless
        }
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
            qualityVerified = e?.let { isQualityVerified(it) } ?: false,
            queue = if (queueEntities.isNotEmpty()) queueEntities.map {
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
            } else (0 until c.mediaItemCount).map { i ->
                // Потоковая очередь (поиск / радар / плейлист) живёт в ExoPlayer,
                // а не в queueEntities — трек-лист был пустым при живом
                // воспроизведении (жалоба 03.09.2026). Метаданные берём из
                // самого MediaItem; тех-строки/длительности для стрима нет.
                val m = c.getMediaItemAt(i).mediaMetadata
                QueueEntry(
                    id = "s$i",
                    title = m.title?.toString().orEmpty(),
                    artist = m.artist?.toString().orEmpty(),
                    artworkUrl = m.artworkUri?.toString(),
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
