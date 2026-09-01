package net.ripster.mobile.core.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Полный разбор аудиопотока — «всё про файл»: контейнер, кодек, битрейт,
 * частота, разрядность, каналы, длительность, размер, путь. Аналог инспектора
 * потока, но шире. Работает и с `content://`, и с `file://`.
 */
data class StreamInfo(
    val container: String = "",
    val codec: String = "",
    val mime: String = "",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val durationSec: Int = 0,
    val fileBytes: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val path: String = "",
) {
    /** Пары «ключ i18n → значение». Локализует потребитель (`tr(key, lang)`);
     *  значения — числа/единицы, они интернациональны. */
    fun row(): List<Pair<String, String>> = buildList {
        if (container.isNotBlank()) add("probe.container" to container.uppercase())
        if (codec.isNotBlank()) add("probe.codec" to codec)
        if (mime.isNotBlank()) add("probe.mime" to mime)
        if (bitrateKbps > 0) add("probe.bitrate" to "$bitrateKbps kbps")
        if (sampleRateHz > 0) add("probe.samplerate" to "%.1f kHz".format(sampleRateHz / 1000f))
        if (bitDepth > 0) add("probe.bitdepth" to "$bitDepth-bit")
        if (channels > 0) add("probe.channels" to when (channels) { 1 -> "Mono"; 2 -> "Stereo"; else -> "${channels}ch" })
        if (durationSec > 0) add("probe.duration" to "%d:%02d".format(durationSec / 60, durationSec % 60))
        if (fileBytes > 0) add("probe.size" to "%.1f MB".format(fileBytes / 1_048_576f))
    }
}

object StreamProbe {

    suspend fun probe(context: Context, source: String): StreamInfo = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(source) }.getOrNull()
        var info = StreamInfo(path = source)

        // размер
        var size = 0L
        if (uri != null && (source.startsWith("content://"))) {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cr ->
                    val ix = cr.getColumnIndex(OpenableColumns.SIZE)
                    if (ix >= 0 && cr.moveToFirst()) size = cr.getLong(ix)
                }
            }
        } else runCatching { size = java.io.File(source).length() }
        info = info.copy(fileBytes = size)

        // MediaExtractor — частота/каналы/битрейт/mime на любом API
        runCatching {
            val ex = MediaExtractor()
            if (uri != null && (source.startsWith("content://") || source.startsWith("file://"))) {
                ex.setDataSource(context, uri, null)
            } else ex.setDataSource(source)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("audio/")) continue
                info = info.copy(
                    mime = m,
                    codec = m.substringAfter('/').uppercase(),
                    sampleRateHz = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(info.sampleRateHz),
                    channels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(info.channels),
                    bitrateKbps = runCatching { f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 }.getOrDefault(info.bitrateKbps),
                )
                break
            }
            ex.release()
        }

        // MediaMetadataRetriever — длительность, битрейт (fallback), тэги, разрядность (API 31+)
        runCatching {
            val mmr = MediaMetadataRetriever()
            if (uri != null) mmr.setDataSource(context, uri) else mmr.setDataSource(source)
            fun k(id: Int) = mmr.extractMetadata(id)
            val dur = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)?.toInt() ?: 0
            val br = k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000) ?: 0
            info = info.copy(
                durationSec = if (dur > 0) dur else info.durationSec,
                bitrateKbps = if (info.bitrateKbps <= 0 && br > 0) br else info.bitrateKbps,
                title = k(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
            )
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val bps = runCatching { k(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() }.getOrNull() ?: 0
                if (bps > 0) info = info.copy(bitDepth = bps)
            }
            mmr.release()
        }

        val ext = source.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (ext.isNotBlank() && ext.length <= 5) info = info.copy(container = ext)

        info
    }
}
