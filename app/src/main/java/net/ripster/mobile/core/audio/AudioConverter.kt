package net.ripster.mobile.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Конвертер формата НА УСТРОЙСТВЕ — аналог «Формат вывода» ПК-версии, но
 * средствами `MediaCodec`/`MediaMuxer`, без ffmpeg.
 *
 * Сейчас две цели: WAV (16-бит PCM, без потерь, крупный) и M4A/AAC 256k.
 * FLAC (энкодер `audio/flac` есть с API 29), MP3 и OPUS — следующим заходом
 * (MP3/OPUS у Android нет системного энкодера — уйдут «через ПК»).
 *
 * Всё в try/catch на верхнем уровне у вызывающего: на эмуляторе часть
 * кодеков может отсутствовать.
 */
object AudioConverter {

    enum class Target { WAV, M4A_AAC }

    private class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray)

    suspend fun convert(
        context: Context,
        src: Uri,
        dst: Uri,
        target: Target,
        aacBitrate: Int = 256_000,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val pcm = decodePcm(context, src)
            when (target) {
                Target.WAV -> {
                    context.contentResolver.openOutputStream(dst)?.use { out ->
                        writeWav(out, pcm)
                    } ?: error("cannot open output")
                }
                Target.M4A_AAC -> {
                    context.contentResolver.openFileDescriptor(dst, "rw")?.use { pfd ->
                        encodeAac(pfd.fileDescriptor, pcm, aacBitrate)
                    } ?: error("cannot open output")
                }
            }
        }
    }

    // ── декод исходника в 16-бит PCM (интерливед, как отдал декодер) ──────────

    private fun decodePcm(context: Context, uri: Uri): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; fmt = f; break
            }
        }
        if (track < 0 || fmt == null) { ex.release(); error("no audio track") }
        ex.selectTrack(track)

        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sampleRate = runCatching { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
        var channels = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val out = ByteArrayOutputStream(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var sawInEnd = false
        var sawOutEnd = false
        val timeoutUs = 10_000L
        // предохранитель по размеру: ~30 мин стерео 44.1к 16-бит ≈ 300 МБ
        val cap = 320 * 1024 * 1024

        while (!sawOutEnd && out.size() < cap) {
            if (!sawInEnd) {
                val inIx = codec.dequeueInputBuffer(timeoutUs)
                if (inIx >= 0) {
                    val buf = codec.getInputBuffer(inIx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInEnd = true
                    } else {
                        codec.queueInputBuffer(inIx, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIx >= 0 -> {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        buf.get(chunk)
                        out.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutEnd = true
                }
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    sampleRate = runCatching { of.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(sampleRate)
                    channels = runCatching { of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        return Pcm(sampleRate, channels.coerceIn(1, 2), out.toByteArray())
    }

    // ── WAV (RIFF/PCM16) ────────────────────────────────────────────────────

    private fun writeWav(out: OutputStream, pcm: Pcm) {
        val dataLen = pcm.data.size
        val byteRate = pcm.sampleRate * pcm.channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                       // subchunk1 size
        header.putShort(1)                      // PCM
        header.putShort(pcm.channels.toShort())
        header.putInt(pcm.sampleRate)
        header.putInt(byteRate)
        header.putShort((pcm.channels * 2).toShort())  // block align
        header.putShort(16)                     // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        out.write(header.array())
        out.write(pcm.data)
        out.flush()
    }

    // ── M4A / AAC-LC ────────────────────────────────────────────────────────

    private fun encodeAac(fd: java.io.FileDescriptor, pcm: Pcm, bitrate: Int) {
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val encFmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        val info = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        var inPos = 0
        val total = pcm.data.size
        val timeoutUs = 10_000L
        var sawInEnd = false
        var sawOutEnd = false
        val bytesPerUs = pcm.sampleRate.toDouble() * pcm.channels * 2 / 1_000_000.0

        while (!sawOutEnd) {
            if (!sawInEnd) {
                val inIx = enc.dequeueInputBuffer(timeoutUs)
                if (inIx >= 0) {
                    val buf = enc.getInputBuffer(inIx)!!
                    buf.clear()
                    val room = buf.capacity()
                    val chunk = minOf(room, total - inPos)
                    if (chunk <= 0) {
                        enc.queueInputBuffer(inIx, 0, 0, ptsUs(inPos, bytesPerUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInEnd = true
                    } else {
                        buf.put(pcm.data, inPos, chunk)
                        enc.queueInputBuffer(inIx, 0, chunk, ptsUs(inPos, bytesPerUs), 0)
                        inPos += chunk
                    }
                }
            }
            val outIx = enc.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(enc.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIx >= 0 -> {
                    val buf = enc.getOutputBuffer(outIx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buf, info)
                    }
                    enc.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutEnd = true
                }
            }
        }
        enc.stop(); enc.release()
        runCatching { muxer.stop() }
        muxer.release()
    }

    private fun ptsUs(bytePos: Int, bytesPerUs: Double): Long =
        (bytePos / bytesPerUs).toLong()
}
