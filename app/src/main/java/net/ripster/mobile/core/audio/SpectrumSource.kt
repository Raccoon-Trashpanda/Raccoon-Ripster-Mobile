package net.ripster.mobile.core.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.service.deezer.DeezerCrypto
import okhttp3.Request
import java.io.File

/**
 * Играющий сейчас трек уже течёт по сети — значит байты доступны, и спектр по
 * нему построить МОЖНО, не заставляя человека «сначала скачай». Тянем начало
 * потока (~14 МБ, этого хватает и на пару минут, и на отпечаток), при
 * необходимости расшифровываем Deezer, кладём во временный файл рядом с кэшем —
 * дальше обычный [Spectrogram.analyze] по локальному пути.
 */
object SpectrumSource {

    // Полный трек, но с потолком: FLAC 4-минутника ~30–40 МБ, спектру-отпечатку
    // хватает и меньшего, а MediaExtractor давится обрезанным по Range файлом —
    // поэтому качаем контейнер целиком (до потолка), без Range.
    private const val CAP_BYTES = 48L * 1024 * 1024

    suspend fun fetchPlayingToTemp(context: Context, playingUrl: String): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val frag = playingUrl.substringAfter('#', "")
                val dzId = frag.split('&', ';')
                    .firstOrNull { it.startsWith("dzbf=") }?.removePrefix("dzbf=")?.takeIf { it.isNotBlank() }
                val clean = playingUrl.substringBefore('#')
                val req = Request.Builder()
                    .url(clean)
                    .header("User-Agent", "RipsterMobile")
                    .build()
                RipsterHttp.client.newCall(req).execute().use { r ->
                    val ct = r.header("Content-Type").orEmpty().lowercase()
                    if (!r.isSuccessful && r.code != 206) return@runCatching null
                    val body = r.body ?: return@runCatching null
                    // Расширение по типу ответа — MediaExtractor так надёжнее
                    // выбирает парсер (Tidal lossless приходит как audio/mp4).
                    val ext = when {
                        "flac" in ct -> "flac"
                        "mp4" in ct || "m4a" in ct || "alac" in ct || "aac" in ct -> "m4a"
                        "mpeg" in ct || "mp3" in ct -> "mp3"
                        "ogg" in ct || "opus" in ct -> "ogg"
                        "wav" in ct -> "wav"
                        else -> "m4a"
                    }
                    val tmp = File(context.cacheDir, "spec_${playingUrl.hashCode()}.$ext")
                    tmp.outputStream().use { os ->
                        if (dzId != null) {
                            DeezerCrypto.decryptStream(
                                body.byteStream(), os, DeezerCrypto.blowfishKey(dzId),
                                body.contentLength().takeIf { it > 0 },
                            ) { _, _ -> }
                        } else {
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            body.byteStream().use { ins ->
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    os.write(buf, 0, n)
                                    total += n
                                    if (total >= CAP_BYTES) break
                                }
                            }
                        }
                    }
                    if (tmp.length() < 8192) { tmp.delete(); null } else tmp
                }
            }.getOrNull()
        }
}
