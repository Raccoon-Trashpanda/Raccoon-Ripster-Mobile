package net.ripster.mobile.service.soundcloud

import net.ripster.mobile.core.errors.EngineErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Сборка не-DRM HLS-потока SoundCloud в один файл.
 *
 * Для нешифрованных пресетов SC (`mp3`, `aac_160k`/`aac_256k`) сегменты m3u8 —
 * это сырые кадры MP3 или ADTS-AAC. Их достаточно склеить побайтно: получится
 * валидный `.mp3` / `.aac`, без ffmpeg. Шифрованные (`#EXT-X-KEY` с методом,
 * отличным от NONE) сюда не попадают — их отсекает [SoundCloudClient] раньше.
 */
object HlsAssembler {

    /** Скачивает все сегменты m3u8 в [out], отдаёт прогресс по числу сегментов. */
    fun assemble(m3u8Url: String, out: File): Flow<Progress> = flow {
        val playlist = fetchText(m3u8Url)
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
            throw IOException(EngineErrors.DRM_UNSUPPORTED)
        }
        val segments = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (segments.isEmpty()) throw IOException("SoundCloud: empty m3u8 ($m3u8Url)")

        out.outputStream().buffered().use { sink ->
            var written = 0L
            segments.forEachIndexed { i, segUrl ->
                currentCoroutineContext().ensureActive()
                val bytes = fetchBytes(segUrl)
                sink.write(bytes)
                written += bytes.size
                emit(Progress(i + 1, segments.size, written))
            }
        }
    }.flowOn(Dispatchers.IO)

    data class Progress(val segmentsDone: Int, val segmentsTotal: Int, val bytesWritten: Long) {
        val fraction: Float get() = segmentsDone.toFloat() / segmentsTotal
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", SoundCloudClientId.UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GET $url -> HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("GET $url -> empty body")
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", SoundCloudClientId.UA).build()
        RipsterHttp.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("segment $url -> HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IOException("segment $url -> empty body")
        }
    }
}
