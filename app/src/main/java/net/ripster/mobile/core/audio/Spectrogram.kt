package net.ripster.mobile.core.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Спектрограмма скачанного файла — своя, на устройстве, без ffmpeg. Декод в
 * PCM через `MediaCodec`, оконное STFT, свой radix-2 FFT, раскраска в bitmap.
 *
 * Пять пресетов вида — калька набора из ПК-версии (`ripster/routes/
 * spectrogram.py` `SPEC_STYLES`): палитра + шкала амплитуды + усиление. Плюс
 * разбор «всего потока»: по усреднённому спектру считаем срез частот и
 * кирпичную стену, из них — вердикт (lossless подтверждён / lossy / подделка),
 * ровно как `_verdict_with_fingerprint` на десктопе.
 *
 * Это ЗНАК, а не студийный анализатор: 1024-точечный FFT, ~900 столбцов.
 */
object Spectrogram {

    private const val FFT = 1024
    private const val HOP = FFT / 2
    private const val BINS = FFT / 2
    private const val MAX_COLS = 900
    private const val DB_FLOOR = -95f

    /** Пресеты вида — id совпадают с ПК-версией, не переименовывать. */
    enum class Style(val id: String, val scale: Scale, val gain: Float, val palette: Palette) {
        RIPSTER("ripster", Scale.CBRT, 2f, Palette.PLASMA),
        SOX("sox", Scale.LOG, 2f, Palette.FIRE),
        MAGMA("magma", Scale.LOG, 3f, Palette.MAGMA),
        MONO("mono", Scale.LOG, 4f, Palette.INTENSITY),
        COOL("cool", Scale.CBRT, 2f, Palette.COOL);

        companion object {
            fun byId(id: String?): Style = entries.firstOrNull { it.id == id } ?: RIPSTER
        }
    }

    enum class Scale { LOG, CBRT }
    enum class Palette { PLASMA, FIRE, MAGMA, INTENSITY, COOL }

    /**
     * Что за файл на самом деле — по спектру, а не по расширению.
     * LOSSLESS — полоса до потолка; LOSSLESS_SOFT — lossless-контейнер, спад
     * плавный без кирпичной стены (материал трека, не кодек).
     */
    enum class Verdict { LOSSLESS, LOSSLESS_SOFT, LOSSY, FAKE, UNKNOWN }

    data class Result(
        val bitmap: Bitmap,
        val verdict: Verdict,
        /** Частота среза, кГц (где спектр реально заканчивается). */
        val cutoffKHz: Float,
        /** Найдена ли резкая «кирпичная стена» на срезе (признак транскода). */
        val brickwall: Boolean,
        val sampleRateHz: Int,
    )

    suspend fun analyze(
        context: Context,
        source: String,
        style: Style = Style.RIPSTER,
        heightPx: Int = 360,
        containerExt: String? = null,
    ): Result? = withContext(Dispatchers.IO) {
        val dec = runCatching { decodeMono(context, source) }.getOrNull() ?: return@withContext null
        if (dec.pcm.size < FFT) return@withContext null
        runCatching { build(dec, style, heightPx, containerExt) }.getOrNull()
    }

    // ── декод в моно float [-1..1] + частота дискретизации ─────────────────

    private class Decoded(val pcm: FloatArray, val sampleRateHz: Int)

    private fun decodeMono(context: Context, source: String): Decoded {
        val ex = MediaExtractor()
        if (source.startsWith("content://") || source.startsWith("file://")) {
            ex.setDataSource(context, Uri.parse(source), null)
        } else {
            ex.setDataSource(source)
        }
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; format = f; break
            }
        }
        if (track < 0 || format == null) { ex.release(); error("no audio track") }
        ex.selectTrack(track)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Float>(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        val timeoutUs = 10_000L
        val cap = 2_600_000   // ~1 мин @ 44.1к — спектру-отпечатку хватает, вдвое меньше памяти

        while (!sawOutputEnd && out.size < cap) {
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
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.asShortBuffer()
                    val frame = ShortArray(shorts.remaining())
                    shorts.get(frame)
                    var i = 0
                    while (i < frame.size) {
                        var acc = 0
                        var ch = 0
                        while (ch < channels && i < frame.size) { acc += frame[i]; i++; ch++ }
                        out.add((acc.toFloat() / channels) / 32768f)
                    }
                }
                codec.releaseOutputBuffer(outIx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        return Decoded(out.toFloatArray(), sampleRate)
    }

    // ── STFT → bitmap + вердикт ──────────────────────────────────────────

    private val HANN = FloatArray(FFT) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / (FFT - 1)).toFloat() }

    private fun build(dec: Decoded, style: Style, heightPx: Int, containerExt: String?): Result {
        val pcm = dec.pcm
        val frames = ((pcm.size - FFT) / HOP + 1).coerceAtLeast(1)
        val stride = max(1, frames / MAX_COLS)
        val cols = (frames + stride - 1) / stride
        val h = heightPx.coerceIn(120, 480)
        val bmp = Bitmap.createBitmap(cols, h, Bitmap.Config.ARGB_8888)

        val re = FloatArray(FFT)
        val im = FloatArray(FFT)
        val binSum = DoubleArray(BINS)      // для усреднённого спектра → вердикт
        var binN = 0

        var col = 0
        var f = 0
        while (f < frames && col < cols) {
            val start = f * HOP
            for (i in 0 until FFT) {
                val s = start + i
                re[i] = if (s < pcm.size) pcm[s] * HANN[i] else 0f
                im[i] = 0f
            }
            fft(re, im)

            val mags = FloatArray(BINS)
            for (b in 0 until BINS) {
                val m = sqrt(re[b] * re[b] + im[b] * im[b]) / FFT
                mags[b] = m
                binSum[b] += m.toDouble()
            }
            binN++

            for (y in 0 until h) {
                // Линейная шкала частоты (0 Гц снизу, Nyquist сверху) — как в
                // ПК-версии, чтобы подписи оси совпадали с картинкой.
                val frac = 1f - y.toFloat() / (h - 1)
                val bin = (frac * (BINS - 1)).toInt().coerceIn(0, BINS - 1)
                val t = intensity(mags[bin], style)
                bmp.setPixel(col, y, colorFor(t, style.palette))
            }
            col++
            f += stride
        }

        // ── усреднённый спектр → срез + кирпичная стена → вердикт ──────────
        val avgDb = FloatArray(BINS) {
            val a = (binSum[it] / max(1, binN)).toFloat()
            (20.0 * ln(max(a, 1e-9f).toDouble()) / LN10).toFloat()
        }
        // Срез — относительно ШУМОВОГО ПОЛА, а не пика (порт логики
        // `spectrogram.py`): абсолютный порог «пик − 45 дБ» ложно метил тихий,
        // но настоящий ВЧ-контент (эмбиент) как lossy с срезом 2–3 кГц.
        // Шумовой пол = медиана верхних 5% корзин; срез = самая высокая
        // корзина, где уровень поднимается на 10 дБ выше пола.
        val topFrom = (BINS * 0.95f).toInt().coerceIn(1, BINS - 2)
        val topSorted = avgDb.copyOfRange(topFrom, BINS).sorted()
        val noiseFloor = topSorted[topSorted.size / 2]
        var cutoffBin = BINS - 1
        for (b in BINS - 1 downTo 1) {
            if (avgDb[b] > noiseFloor + 10f) { cutoffBin = b; break }
        }
        val nyquist = dec.sampleRateHz / 2f
        val cutoffKHz = (cutoffBin.toFloat() / (BINS - 1)) * nyquist / 1000f
        // кирпичная стена: резкий обрыв на срезе (>25 дБ на ~0.5 кГц)
        val lo = (cutoffBin - BINS / 40).coerceAtLeast(1)
        val brickwall = cutoffBin < BINS - 2 && (avgDb[lo] - avgDb[(cutoffBin + 1).coerceAtMost(BINS - 1)]) > 25f

        val claimsLossless = containerExt?.lowercase() in setOf("flac", "wav", "alac", "aiff")
        val fullBand = cutoffKHz >= nyquist / 1000f * 0.86f
        val verdict = when {
            claimsLossless && !fullBand && brickwall -> Verdict.FAKE
            claimsLossless && fullBand -> Verdict.LOSSLESS
            claimsLossless -> Verdict.LOSSLESS_SOFT
            !claimsLossless && fullBand -> Verdict.LOSSLESS_SOFT
            !claimsLossless && brickwall -> Verdict.LOSSY
            !claimsLossless && !fullBand -> Verdict.LOSSY
            else -> Verdict.UNKNOWN
        }

        val analyzedSec = pcm.size.toFloat() / dec.sampleRateHz.coerceAtLeast(1)
        val framed = frame(bmp, nyquist, analyzedSec)
        return Result(framed, verdict, cutoffKHz, brickwall, dec.sampleRateHz)
    }

    // ── рамка с осями — калька ПК (`spectrogram.py` `_generate_spectrogram`) ──
    // Слева шкала частот (кГц), снизу — времени, тонкая сетка, рамка и
    // словесный знак «R I P S T E R» вместо ffmpeg-легенды.

    private const val FRAME_W = 900
    private const val FRAME_H = 460
    private const val PAD_L = 64
    private const val PAD_T = 14
    private const val PAD_R = 14
    private const val PAD_B = 34

    private val COL_BG     = Color.rgb(17, 19, 24)
    private val COL_BORDER = Color.rgb(58, 58, 88)
    private val COL_TEXT   = Color.rgb(232, 232, 248)
    private val COL_MUTED  = Color.rgb(144, 144, 168)
    private val COL_ACCENT = Color.rgb(192, 132, 160)
    private val COL_GRID   = Color.argb(90, 58, 58, 88)

    private fun frame(spectrum: Bitmap, nyquistHz: Float, durationSec: Float): Bitmap {
        val out = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val cv = AndroidCanvas(out)
        cv.drawColor(COL_BG)

        val left = PAD_L.toFloat()
        val top = PAD_T.toFloat()
        val right = (FRAME_W - PAD_R).toFloat()
        val bottom = (FRAME_H - PAD_B).toFloat()
        val plotW = right - left
        val plotH = bottom - top

        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        cv.drawBitmap(
            spectrum,
            Rect(0, 0, spectrum.width, spectrum.height),
            RectF(left, top, right, bottom),
            bmpPaint,
        )

        val grid = Paint().apply { color = COL_GRID; strokeWidth = 1f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COL_MUTED; textSize = 21f; typeface = Typeface.SANS_SERIF
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COL_BORDER; style = Paint.Style.STROKE; strokeWidth = 1.4f
        }

        // ось частот — 0 снизу, Nyquist сверху, линейно
        if (nyquistHz > 0f) {
            for (hz in niceTicks(nyquistHz, 8)) {
                val y = bottom - (hz / nyquistHz) * plotH
                cv.drawLine(left, y, right, y, grid)
                val t = if (hz <= 0f) "0" else fmtNum(hz / 1000f) + "k"
                cv.drawText(t, left - label.measureText(t) - 9f, y + 7f, label)
            }
        }
        // ось времени — 0 слева, длина проанализированного отрезка справа
        if (durationSec > 0.5f) {
            for (sec in niceTicks(durationSec, 7)) {
                val x = left + (sec / durationSec) * plotW
                cv.drawLine(x, top, x, bottom, grid)
                val s = sec.toInt()
                val t = if (s >= 60) "${s / 60}:${(s % 60).toString().padStart(2, '0')}" else "${s}s"
                cv.drawText(t, (x + 4f).coerceAtMost(right - label.measureText(t)), bottom + 22f, label)
            }
        }

        cv.drawRect(left, top, right, bottom, border)

        val wm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COL_ACCENT; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.18f
        }
        val wmText = "R I P S T E R"
        cv.drawText(wmText, right - wm.measureText(wmText), bottom + 22f, wm)
        return out
    }

    /** Круглые деления от 0 до maxVal (~count штук) — порт `_nice_ticks`. */
    private fun niceTicks(maxVal: Float, count: Int = 6): List<Float> {
        if (maxVal <= 0f) return listOf(0f)
        val rawStep = maxVal / count
        val magnitude = 10.0.pow(floor(log10(rawStep.toDouble()))).toFloat()
        val residual = rawStep / magnitude
        val step = (when {
            residual > 5 -> 10f; residual > 2 -> 5f; residual > 1 -> 2f; else -> 1f
        }) * magnitude
        val ticks = ArrayList<Float>()
        var v = 0f
        while (v <= maxVal + step * 0.001f) { ticks.add(v); v += step }
        return ticks
    }

    private fun fmtNum(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString()
        else ((v * 10).toInt() / 10f).toString()

    /** магнитуда → интенсивность 0..1 по шкале и усилению стиля. */
    private fun intensity(mag: Float, style: Style): Float {
        val g = style.gain
        return when (style.scale) {
            Scale.LOG -> {
                val db = (20.0 * ln(max(mag, 1e-9f).toDouble()) / LN10).toFloat().coerceIn(DB_FLOOR, 0f)
                ((db - DB_FLOOR) / (0f - DB_FLOOR) * (g / 2f)).coerceIn(0f, 1f)
            }
            Scale.CBRT -> (cbrt((mag * 40f * g).toDouble()).toFloat()).coerceIn(0f, 1f)
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ── палитры (t 0..1) ────────────────────────────────────────────────
    private fun colorFor(t: Float, p: Palette): Int {
        val x = t.coerceIn(0f, 1f)
        return when (p) {
            Palette.PLASMA -> rgb(0.05f + x * 0.9f, x * x * 0.75f, 0.5f + 0.5f * min(1f, x * 1.5f) - x * 0.3f)
            Palette.FIRE -> rgb(min(1f, x * 2.2f), max(0f, x - 0.30f) * 1.7f, max(0f, x - 0.72f) * 3.4f)
            Palette.MAGMA -> rgb(min(1f, x * 1.9f), max(0f, x - 0.35f) * 1.7f, if (x < 0.5f) x * 1.4f else 0.7f + (x - 0.5f) * 0.6f)
            Palette.INTENSITY -> rgb(x, x, x)
            Palette.COOL -> rgb(x * x * 0.6f, 0.15f + x * 0.7f, 0.35f + min(1f, x * 1.3f) * 0.65f)
        }
    }

    private fun rgb(r: Float, g: Float, b: Float) = Color.rgb(
        (255 * r).toInt().coerceIn(0, 255),
        (255 * g).toInt().coerceIn(0, 255),
        (255 * b).toInt().coerceIn(0, 255),
    )

    private val LN10 = ln(10.0)
}
