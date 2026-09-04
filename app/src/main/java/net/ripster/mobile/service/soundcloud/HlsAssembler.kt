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
        var url = m3u8Url
        var playlist = fetchText(url)
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
            throw IOException(EngineErrors.DRM_UNSUPPORTED)
        }
        // Мастер-плейлист (несколько битрейтов) сегментов не содержит — в нём
        // ссылки на варианты. Раньше их принимали за сегменты и склеивали в файл
        // текст плейлистов вместо звука. У BBC отдаётся именно такой (ABR),
        // поэтому спускаемся на вариант с наибольшим битрейтом. 04.09.2026.
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variant = bestVariant(playlist, url)
                ?: throw IOException("HLS: master playlist without variants ($url)")
            url = variant
            playlist = fetchText(url)
            if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
                throw IOException(EngineErrors.DRM_UNSUPPORTED)
            }
        }
        val segments = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { absolute(it, url) }
            .toList()
        if (segments.isEmpty()) throw IOException("SoundCloud: empty m3u8 ($url)")

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

    /** Вариант с наибольшим BANDWIDTH из мастер-плейлиста. */
    private fun bestVariant(playlist: String, base: String): String? {
        val lines = playlist.lines()
        var best: Pair<Long, String>? = null
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val href = lines.drop(i + 1).map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (best == null || bw > best!!.first) best = bw to absolute(href, base)
        }
        return best?.second
    }

    /**
     * Ссылка сегмента/варианта относительно плейлиста.
     *
     * В m3u8 они сплошь и рядом относительные — у SoundCloud приходили полные,
     * поэтому раньше это сходило с рук, а у BBC относительные, и без разрешения
     * запрос уходил в никуда.
     */
    private fun absolute(href: String, base: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        else -> runCatching { java.net.URI(base).resolve(href).toString() }.getOrDefault(href)
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
