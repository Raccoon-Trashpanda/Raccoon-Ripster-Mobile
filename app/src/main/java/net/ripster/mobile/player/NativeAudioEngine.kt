package net.ripster.mobile.player

import net.ripster.mobile.core.errors.attempt

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Нативный аудиодвижок Ripster (см. трекер s-au).
 *
 * Что уже есть:
 *  · Oboe (AAudio exclusive, Float) — вывод в обход ExoPlayer;
 *  · свой C++-декод FLAC/WAV по fd (SAF `content://`);
 *  · рабочий поток → кольцевой буфер → аудио-callback: развязка декодера от
 *    звука + ГЭПЛЕСС между треками;
 *  · ОЧЕРЕДЬ: playQueue([uri…]) + native next/prev/seek;
 *  · DSP-зачаток: программная громкость с TPDF-дизером; линейный ресемпл для
 *    треков с частотой ≠ частоте потока (честно помечаем «не bit-perfect»).
 *
 * НЕ трогает: MP3/AAC, сетевой стрим, Bluetooth, медиасессию — это остаётся на
 * Media3/ExoPlayer. [PlayerController] решает, каким трактом играть.
 */
object NativeAudioEngine {

    @Volatile private var available = false

    /**
     * Открытые под движок дескрипторы.
     *
     * Обычным списком они лежали до 21.09.2026 — и `stop()` (главный поток) с
     * `playQueue` (IO) правили его ОДНОВРЕМЕННО: `forEach` из releaseFds ловил
     * `ConcurrentModificationException` ровно тогда, когда человек нажимал «стоп»,
     * пока очередь ещё открывалась. Список — сам по себе, закрытие — строго один раз.
     */
    private val openFds = CloseableBag<ParcelFileDescriptor>()
    @Volatile private var streamRate = 44100

    private const val TAG = "RipsterAudio"

    /** Почему нативный тракт отказал в последний раз. Для диагностики. */
    @Volatile var lastError: String? = null
        private set

    init {
        available = runCatching { System.loadLibrary("ripster_audio") }.isSuccess
    }

    val isAvailable: Boolean get() = available

    /** Может ли нативный тракт сыграть этот локальный файл (по расширению/MIME). */
    fun canPlay(context: Context, uri: Uri): Boolean = detectFormat(context, uri) >= 0

    /**
     * Открыть очередь локальных FLAC/WAV и начать со [startIndex].
     * Файлы, формат которых не распознан, пропускаются — если после фильтра
     * ничего не осталось, вернётся ошибка (вызывающий уходит на ExoPlayer).
     */
    suspend fun playQueue(
        context: Context,
        uris: List<Uri>,
        startIndex: Int,
        requireAll: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        attempt {
            check(available) { "нативная библиотека не загрузилась" }
            releaseFds()
            val fds = ArrayList<Int>()
            val fmts = ArrayList<Int>()
            var newStart = 0
            uris.forEachIndexed { i, u ->
                val fmt = detectFormat(context, u)
                if (fmt < 0) {
                    require(!requireAll) { "элемент не FLAC/WAV/ALAC — очередь целиком уходит на ExoPlayer" }
                    return@forEachIndexed
                }
                val pfd = openFd(context, u)
                if (pfd == null) {
                    require(!requireAll) { "файл не открылся" }
                    return@forEachIndexed
                }
                if (i <= startIndex) newStart = fds.size
                openFds.add(pfd)
                fds.add(pfd.fd)
                fmts.add(fmt)
            }
            require(fds.isNotEmpty()) { "нет локального lossless в очереди" }
            check(nLoadQueue(fds.toIntArray(), fmts.toIntArray(), newStart)) { "декодер/Oboe не открылись" }
            streamRate = nSampleRate().coerceAtLeast(1)
            check(nStart()) { "поток не стартовал" }
        }.onFailure {
            releaseFds()
            // Молчаливый откат на ExoPlayer — это ровно тот «мёртвый
            // детектор», из-за которого движок годился только на веру.
            // Причина должна быть хотя бы в логе. Текст английский:
            // журнал читают инструментами, а не глазами пользователя.
            lastError = it.toString()
            // ШТАТНЫЙ откат печатаем ОДНОЙ строкой, без трассы стека.
            //
            // «Элемент не FLAC/WAV/ALAC» — это не сбой, а обычное положение
            // дел: половина фонотеки в m4a/mp3, и нативный тракт для них не
            // предназначен. Полная трасса на каждый такой случай забивает
            // журнал так, что настоящие поломки в нём не найти — я сам на этом
            // потерял время 06.09.2026, разбирая чужой ANR по нашему логу.
            //
            // Трасса остаётся там, где она нужна: когда упал сам декодер или
            // Oboe, то есть когда причина НЕ очевидна из текста.
            val routine = it is IllegalArgumentException
            if (routine) {
                android.util.Log.i(TAG, "queue goes to ExoPlayer: ${it.message}")
            } else {
                android.util.Log.w(TAG, "native queue not opened, falling back to ExoPlayer", it)
            }
        }
    }

    /** Одиночный трек — частный случай очереди. */
    suspend fun play(context: Context, uri: Uri): Result<Unit> = playQueue(context, listOf(uri), 0)

    fun pause() = nPause()
    fun resume() { nStart() }
    fun stop() { nStop(); releaseFds() }
    fun next() = nNext()
    fun previous() = nPrev()
    fun setIndex(i: Int) = nSetIndex(i)
    fun seekMs(ms: Long) = nSeek(NativeTiming.seekFrames(ms, currentRate()))
    fun setGain(g: Float) = nSetGain(g)

    /**
     * Позиция и длительность живут в РАЗНЫХ доменах кадров, и путать их
     * нельзя: движок считает позицию по частоте ПОТОКА (`outPos_` — кадры,
     * ушедшие в audio callback), а длительность — по частоте ИСТОЧНИКА
     * (`totalFrames` конкретного файла).
     *
     * Раньше оба делили на `nSampleRate()` (частоту файла), и в очереди со
     * смешанными частотами — поток открыт под первый трек, второй частоту
     * ресемплим сами — позиция отставала ровно во столько раз, во сколько
     * частота файла выше частоты потока. 96 кГц в потоке 48 кГц: полоса
     * доползала до 50 % к концу трека, хотя `durationMs` показывал правду.
     */
    fun positionMs(): Long = NativeTiming.positionMs(nPositionFrames(), outputRate())
    fun durationMs(): Long = NativeTiming.durationMs(nDurationFrames(), currentRate())
    fun index(): Int = if (available) nIndex() else 0
    fun count(): Int = if (available) nCount() else 0
    fun isPlaying(): Boolean = available && nIsPlaying()
    fun isEnded(): Boolean = available && nIsEnded()

    /** Декод всего файла (до [cap] кадров) в моно float-PCM нашими нативными
     *  декодерами — фолбэк для спектра/паспорта, когда системный MediaCodec
     *  формат не тянет (напр. ALAC). fmt: 0 flac,1 wav,2 alac,3 wavpack,4 dsd.
     *  Возвращает (моно PCM, sampleRate) или null. */
    fun decodeMono(fd: Int, fmt: Int, cap: Int): Pair<FloatArray, Int>? {
        if (!available) return null
        val rate = IntArray(1)
        val pcm = runCatching { nDecodeMono(fd, fmt, cap, rate) }.getOrNull() ?: return null
        return pcm to rate[0].coerceAtLeast(1)
    }

    /** Частота ИСТОЧНИКА текущего трека (для перевода мс↔кадры). */
    private fun currentRate(): Int = nSampleRate().coerceAtLeast(1)

    /**
     * Частота ПОТОКА — домен, в котором посчитана позиция.
     *
     * `granted` — что реально дало устройство; пока поток не открыт (или
     * открылся молча), берём запрошенную частоту: `streamRate_` и есть та, под
     * которую движок ресемплит.
     */
    private fun outputRate(): Int =
        (if (available) nGrantedRate() else 0).takeIf { it > 0 }
            ?: nStreamRate().coerceAtLeast(1)

    /**
     * Чем именно отдаётся звук: точь-в-точь как в файле, через ресемпл или на
     * другой частоте устройства.
     *
     * Отдельно от текста намеренно. Раньше [formatLine] возвращал строку с
     * русской пометкой «(ресемпл → … — не bit-perfect)», и она уезжала прямо в
     * плеер — при английском интерфейсе человек видел русский. Движок не должен
     * сочинять слова для экрана: он сообщает ЧТО произошло, переводит это
     * `ui/i18n`. Это тот же случай, что уже разбирали с ошибками движков
     * (маркеры `__e.<key>__` + один переводчик).
     */
    enum class RateNote { BIT_PERFECT, RESAMPLED, DEVICE_RATE }

    /** Пометка и частота, которую реально дало устройство (Hz). */
    fun rateNote(): Pair<RateNote, Int> {
        if (!available) return RateNote.BIT_PERFECT to 0
        val r = nSampleRate()
        val granted = nGrantedRate()
        return when {
            nResampled() -> RateNote.RESAMPLED to granted
            granted in 1 until r || granted > r -> RateNote.DEVICE_RATE to granted
            else -> RateNote.BIT_PERFECT to granted
        }
    }

    /** Техническая часть строки формата — цифры, одинаковые на любом языке. */
    fun formatLine(): String {
        if (!available) return "—"
        val khz = "%.1f".format(Locale.US, nSampleRate() / 1000f).removeSuffix(".0")
        return "${nBitDepth()}-bit · $khz kHz · ${nChannels()}ch"
    }

    /**
     * Что нативный тракт может сказать о ПУТИ звука прямо сейчас.
     *
     * Нативный декодер распаковывает безlossy-файл один-в-один в PCM, поэтому
     * разрядность и частота ИСТОЧНИКА здесь равны разрядности и частоте
     * ДЕКОДЕРА — это и есть его обещание, и держится оно только пока не трогает
     * ресемплер. `nResampled()`/`nGrantedRate()` — уже настоящие рантайм-значения
     * Oboe, а не предположение.
     *
     * Прямой выход (`directOutput`) честным образом = false: движок идёт через
     * AAudio/OpenSL и системный микшер, собственного USB-UAC обхода AudioFlinger
     * у нас нет (RESONADA_GAP:32-39). Поэтому бит-в-бит на этом тракте не
     * заявляется, даже когда ресемпл молчит — экран обязан сказать почему.
     */
    fun snapshot(): SignalSnapshot {
        if (!available) return SignalSnapshot(engine = PathEngine.NATIVE)
        val rate = nSampleRate().coerceAtLeast(0)
        val bits = nBitDepth().coerceAtLeast(0)
        return SignalSnapshot(
            engine = PathEngine.NATIVE,
            sourceBitDepth = bits,
            sourceRateHz = rate,
            channels = nChannels().coerceAtLeast(0),
            decoderBitDepth = bits,
            decoderRateHz = rate,
            resamplerActive = nResampled(),
            grantedRateHz = nGrantedRate().coerceAtLeast(0),
            outputKnown = nGrantedRate() > 0,
            directOutput = false,
        )
    }

    private fun releaseFds() {
        openFds.drain().forEach { runCatching { it.close() } }
    }

    /**
     * Открыть файл под нативный декодер.
     *
     * Библиотека хранит СКАЧАННОЕ обычным путём файловой системы
     * (`/storage/emulated/0/Android/data/.../x.flac`), а импортированное своей
     * папкой — SAF-адресом `content://`. У первого нет схемы вообще, и
     * `contentResolver.openFileDescriptor` на нём бросает.
     *
     * Из-за этого нативный тракт МОЛЧА падал на ExoPlayer на всём, что
     * приложение скачало само: в настройках выбран «Нативный», играет обычный,
     * и сказать об этом некому — исключение съедал `runCatching` вокруг всей
     * очереди (поймано 05.09.2026 живьём: ни одной строки Oboe в логе и строка
     * формата от Exo). Путь открываем напрямую, SAF-адрес — резолвером.
     */
    private fun openFd(context: Context, uri: Uri): ParcelFileDescriptor? = runCatching {
        when (uri.scheme) {
            null -> ParcelFileDescriptor.open(File(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)
            "file" -> ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            else -> context.contentResolver.openFileDescriptor(uri, "r")
        }
    }.getOrNull()

    private fun detectFormat(context: Context, uri: Uri): Int {
        val name = (uri.lastPathSegment ?: "").lowercase()
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()

        // СНАЧАЛА байты, потом имя. Расширение — это обещание, а обещание
        // расходится с содержимым: 05.09.2026 в библиотеке нашёлся файл
        // `03 - Teardrop.flac`, внутри которого MP4 (`ftyp iso8`). Движок
        // верил имени, читал MP4 как FLAC, drflac_open возвращал null — и всё
        // молча уезжало на ExoPlayer.
        when (sniff(context, uri)) {
            "flac" -> return 0
            "wav" -> return 1
            // WavPack — наш, и это ЕДИНСТВЕННЫЙ путь: системный декодер
            // Android его не читает вовсе, то есть на ExoPlayer такой файл
            // означал бы тишину.
            "wv" -> return 3
            "m4a" -> return if (isAlacContainer(context, uri)) 2 else -1
            // DSD (.dsf/.dff) — тоже ЕДИНСТВЕННЫЙ путь: системный декодер
            // Android его не читает, и на ExoPlayer это была бы тишина.
            // Наружу отдаём PCM 88.2 кГц (децимация в dsd.h) — однобитного
            // тракта на телефоне нет, и обещать его было бы враньём.
            "dsf", "dff" -> return 4
            // Опознали что-то заведомо чужое (mp3/ogg) — нативный тракт не про них.
            "mp3", "ogg", "aiff" -> return -1
        }

        when {
            name.endsWith(".flac") || "flac" in mime -> return 0
            name.endsWith(".wav") || "wav" in mime || "x-wav" in mime -> return 1
            name.endsWith(".wv") -> return 3
            name.endsWith(".dsf") || name.endsWith(".dff") -> return 4
        }
        // .m4a / .alac / .mp4 — только если внутри реально ALAC (не AAC).
        if (name.endsWith(".m4a") || name.endsWith(".alac") || name.endsWith(".mp4") ||
            name.endsWith(".m4b") || "mp4" in mime || "m4a" in mime) {
            return if (isAlacContainer(context, uri)) 2 else -1
        }
        return -1
    }

    /** Первые байты файла → контейнер. `null`, если прочитать не удалось. */
    private fun sniff(context: Context, uri: Uri): String? = runCatching {
        openFd(context, uri)?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { s ->
                val buf = ByteArray(net.ripster.mobile.core.audio.ContainerSniff.HEAD_BYTES)
                val n = s.read(buf)
                if (n <= 0) null else net.ripster.mobile.core.audio.ContainerSniff.of(buf.copyOf(n))
            }
        }
    }.getOrNull()

    /** Быстрая проверка контейнера: есть ли аудиодорожка audio/alac. */
    private fun isAlacContainer(context: Context, uri: Uri): Boolean = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            openFd(context, uri).use { pfd ->
                if (pfd == null) return false
                ex.setDataSource(pfd.fileDescriptor)
                for (i in 0 until ex.trackCount) {
                    val m = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.lowercase()
                    if (m != null && ("alac" in m)) return true
                }
            }
        } finally { ex.release() }
        false
    }.getOrDefault(false)

    // ── JNI (src/main/cpp/native_audio.cpp) ──
    private external fun nLoadQueue(fds: IntArray, fmts: IntArray, startIdx: Int): Boolean
    private external fun nStart(): Boolean
    private external fun nPause()
    private external fun nStop()
    private external fun nNext()
    private external fun nPrev()
    private external fun nSetIndex(i: Int)
    private external fun nSeek(frame: Long)
    private external fun nSetGain(g: Float)
    private external fun nPositionFrames(): Long
    private external fun nDurationFrames(): Long
    private external fun nIndex(): Int
    private external fun nCount(): Int
    private external fun nSampleRate(): Int
    private external fun nGrantedRate(): Int
    private external fun nStreamRate(): Int
    private external fun nChannels(): Int
    private external fun nBitDepth(): Int
    private external fun nResampled(): Boolean
    private external fun nIsPlaying(): Boolean
    private external fun nIsEnded(): Boolean
    private external fun nDecodeMono(fd: Int, fmt: Int, capFrames: Int, rateOut: IntArray): FloatArray?
}

/**
 * Перевод кадров в миллисекунды — отдельно от JNI, чтобы домены кадров можно
 * было проверить без прибора.
 *
 * `position` приходит из потока (частота [outRate]), `duration` — из файла
 * (частота [srcRate]). См. `NativeAudioEngine.positionMs`.
 */
internal object NativeTiming {
    fun positionMs(outFrames: Long, outRate: Int): Long =
        if (outRate <= 0) 0L else outFrames * 1000L / outRate

    fun durationMs(srcFrames: Long, srcRate: Int): Long =
        if (srcRate <= 0) 0L else srcFrames * 1000L / srcRate

    /** Кадр ИСТОЧНИКА, куда просим перемотать (перемотка считается по файлу). */
    fun seekFrames(ms: Long, srcRate: Int): Long =
        if (srcRate <= 0) 0L else ms * srcRate / 1000L
}
