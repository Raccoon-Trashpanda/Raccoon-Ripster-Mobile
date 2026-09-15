package net.ripster.mobile.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Честная форма волны для сик-бара: декодируем ВЕСЬ трек и на лету копим пик
 * (max |амплитуды|) по [BUCKETS] корзинам. Память — O(корзин), не весь PCM;
 * время — один проход по файлу, поэтому зовём с IO и кэшируем по источнику.
 *
 * Только для локальных файлов (байты на диске). Для стрима вернём null —
 * вызывающий откатывается на обычную полосу.
 */
object Waveform {

    const val BUCKETS = 120

    // Пики уже посчитанных треков — чтобы смена темы плеера / поворот не
    // запускали декод заново. Ключ — источник (путь/uri).
    private val cache = ConcurrentHashMap<String, FloatArray>()

    /** Нормированные пики 0..1 длиной [BUCKETS], либо null (не локальный/не вышло). */
    suspend fun peaks(context: Context, source: String?): FloatArray? {
        if (source.isNullOrBlank()) return null
        if (source.startsWith("http", ignoreCase = true)) return null   // стрим — не наш случай
        cache[source]?.let { return it }
        return withContext(Dispatchers.IO) {
            val r = runCatching { decode(context, source) }.getOrNull()
            if (r != null) cache[source] = r
            r
        }
    }

    private fun decode(context: Context, source: String): FloatArray? {
        val ex = MediaExtractor()
        try {
            if (source.startsWith("content://") || source.startsWith("file://")) {
                ex.setDataSource(context, Uri.parse(source), null)
            } else {
                ex.setDataSource(source)
            }
        } catch (_: Throwable) {
            ex.release(); return null
        }
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
        }
        if (track < 0 || format == null) { ex.release(); return null }
        ex.selectTrack(track)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2).coerceAtLeast(1)
        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
        val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        // Всего моно-кадров в треке — по длительности. Без неё разложить по
        // корзинам нельзя (не знаем полного размера заранее) → откат на полосу.
        val totalFrames = durationUs * sampleRate / 1_000_000L
        if (totalFrames <= 0L) { ex.release(); return null }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val peaks = FloatArray(BUCKETS)
        var frameIdx = 0L
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        val timeoutUs = 10_000L
        try {
            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIx >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(inIx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIx >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIx)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val shorts = buf.asShortBuffer()
                        val frame = ShortArray(shorts.remaining())
                        shorts.get(frame)
                        var i = 0
                        while (i < frame.size) {
                            var acc = 0
                            var ch = 0
                            while (ch < channels && i < frame.size) { acc += frame[i]; i++; ch++ }
                            val mono = abs(acc.toFloat() / channels) / 32768f
                            val b = ((frameIdx * BUCKETS) / totalFrames).toInt().coerceIn(0, BUCKETS - 1)
                            if (mono > peaks[b]) peaks[b] = mono
                            frameIdx++
                        }
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }
        } catch (_: Throwable) {
            codec.runCatching { stop() }; codec.release(); ex.release(); return null
        }
        codec.stop(); codec.release(); ex.release()

        // Нормируем к пику. Тихий трек не должен выглядеть «плоским».
        val max = peaks.max()
        if (max <= 0f) return null
        for (i in peaks.indices) peaks[i] = (peaks[i] / max).coerceIn(0f, 1f)
        return peaks
    }
}
