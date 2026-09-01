package net.ripster.mobile.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val openFds = ArrayList<ParcelFileDescriptor>()
    @Volatile private var streamRate = 44100

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
        runCatching {
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
                val pfd = context.contentResolver.openFileDescriptor(u, "r")
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
        }.onFailure { releaseFds() }
    }

    /** Одиночный трек — частный случай очереди. */
    suspend fun play(context: Context, uri: Uri): Result<Unit> = playQueue(context, listOf(uri), 0)

    fun pause() = nPause()
    fun resume() { nStart() }
    fun stop() { nStop(); releaseFds() }
    fun next() = nNext()
    fun previous() = nPrev()
    fun setIndex(i: Int) = nSetIndex(i)
    fun seekMs(ms: Long) = nSeek(ms * currentRate() / 1000)
    fun setGain(g: Float) = nSetGain(g)

    fun positionMs(): Long = nPositionFrames() * 1000L / currentRate()
    fun durationMs(): Long = nDurationFrames() * 1000L / currentRate()
    fun index(): Int = if (available) nIndex() else 0
    fun count(): Int = if (available) nCount() else 0
    fun isPlaying(): Boolean = available && nIsPlaying()
    fun isEnded(): Boolean = available && nIsEnded()

    /** Частота ИСТОЧНИКА текущего трека (для перевода мс↔кадры). */
    private fun currentRate(): Int = nSampleRate().coerceAtLeast(1)

    /** Строка формата для UI + честная пометка про bit-perfect / ресемпл. */
    fun formatLine(): String {
        if (!available) return "—"
        val r = nSampleRate()
        val granted = nGrantedRate()
        val khz = "%.1f".format(Locale.US, r / 1000f).removeSuffix(".0")
        val base = "${nBitDepth()}-bit · $khz kHz · ${nChannels()}ch"
        return when {
            nResampled() -> "$base  (ресемпл → ${granted} Hz — не bit-perfect)"
            granted in 1 until r || granted > r -> "$base  (устройство: $granted Hz)"
            else -> "$base  · bit-perfect"
        }
    }

    private fun releaseFds() {
        openFds.forEach { runCatching { it.close() } }
        openFds.clear()
    }

    private fun detectFormat(context: Context, uri: Uri): Int {
        val name = (uri.lastPathSegment ?: "").lowercase()
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        when {
            name.endsWith(".flac") || "flac" in mime -> return 0
            name.endsWith(".wav") || "wav" in mime || "x-wav" in mime -> return 1
        }
        // .m4a / .alac / .mp4 — только если внутри реально ALAC (не AAC).
        if (name.endsWith(".m4a") || name.endsWith(".alac") || name.endsWith(".mp4") ||
            name.endsWith(".m4b") || "mp4" in mime || "m4a" in mime) {
            return if (isAlacContainer(context, uri)) 2 else -1
        }
        return -1
    }

    /** Быстрая проверка контейнера: есть ли аудиодорожка audio/alac. */
    private fun isAlacContainer(context: Context, uri: Uri): Boolean = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
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
    private external fun nChannels(): Int
    private external fun nBitDepth(): Int
    private external fun nResampled(): Boolean
    private external fun nIsPlaying(): Boolean
    private external fun nIsEnded(): Boolean
}
