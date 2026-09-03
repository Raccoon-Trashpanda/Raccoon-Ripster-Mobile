package net.ripster.mobile.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import net.ripster.mobile.service.deezer.DeezerCrypto
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * DataSource.Factory для ExoPlayer, которая на лету расшифровывает потоки
 * Deezer (`BF_CBC_STRIPE`). Раньше `StreamResolver` терял поле `decryption`,
 * и ExoPlayer получал зашифрованные байты → ТИШИНА при воспроизведении
 * Deezer из карточек/поиска/станций.
 *
 * Способ передачи ключа: `StreamResolver` дописывает во фрагмент URL
 * `#dzbf=<track_id>`; здесь фрагмент срезается, а поток заворачивается в
 * [DeezerBlowfishDataSource].
 */
class RipsterDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = DispatchDataSource(upstream.createDataSource())
}

private const val DZBF = "dzbf="
private const val YAX = "yaxctr="

/** Помечает URL как зашифрованный Deezer-поток для [RipsterDataSourceFactory]. */
fun tagDeezerBlowfish(url: String, trackId: String): String =
    if (url.contains("#")) "$url&$DZBF$trackId" else "$url#$DZBF$trackId"

/** Помечает URL как Яндекс lossless AES-128-CTR поток (ключ — hex, 32 символа). */
fun tagYandexAesCtr(url: String, keyHex: String): String =
    if (url.contains("#")) "$url&$YAX$keyHex" else "$url#$YAX$keyHex"

private class DispatchDataSource(private val http: DataSource) : DataSource {
    private var active: DataSource = http

    override fun open(dataSpec: DataSpec): Long {
        val parts = dataSpec.uri.fragment?.split('&', ';').orEmpty()
        val dzId = parts.firstOrNull { it.startsWith(DZBF) }?.removePrefix(DZBF)
        val yaxKey = parts.firstOrNull { it.startsWith(YAX) }?.removePrefix(YAX)
        return when {
            !dzId.isNullOrBlank() -> {
                active = DeezerBlowfishDataSource(http, DeezerCrypto.blowfishKey(dzId))
                active.open(dataSpec.withUri(dataSpec.uri.buildUpon().fragment(null).build()))
            }
            !yaxKey.isNullOrBlank() && yaxKey.length == 32 -> {
                active = YandexAesCtrDataSource(http, hexToBytes(yaxKey))
                active.open(dataSpec.withUri(dataSpec.uri.buildUpon().fragment(null).build()))
            }
            else -> {
                active = http
                active.open(dataSpec)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)
    override fun getUri(): Uri? = active.uri
    override fun close() = active.close()
}

/**
 * Расшифровывает поток Deezer поблочно (2048 Б, зашифрован каждый 3-й блок,
 * Blowfish-CBC, IV = 0x0001020304050607). Поддерживает старт с ненулевой
 * позиции — выравнивает вниз к границе блока и отбрасывает лишний префикс.
 */
private class DeezerBlowfishDataSource(
    private val up: DataSource,
    key: ByteArray,
) : DataSource {
    private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
    private val keySpec = SecretKeySpec(key, "Blowfish")
    private val iv = IvParameterSpec(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))

    private val raw = ByteArray(2048)
    private var plain = ByteArray(0)
    private var plainPos = 0
    private var blockIndex = 0L
    private var eos = false
    private val skipScratch = ByteArray(4096)

    override fun open(dataSpec: DataSpec): Long {
        val start = dataSpec.position
        val aligned = start - (start % 2048)
        blockIndex = aligned / 2048
        val extra = start - aligned                     // байт до нужной позиции внутри блока
        val reqLen = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else dataSpec.length + extra
        val upSpec = dataSpec.buildUpon().setPosition(aligned).setLength(reqLen).build()
        val upLen = up.open(upSpec)
        var skip = extra
        while (skip > 0) {
            val n = read(skipScratch, 0, min(skip, skipScratch.size.toLong()).toInt())
            if (n <= 0) break
            skip -= n
        }
        return if (upLen == C.LENGTH_UNSET.toLong()) upLen else (upLen - extra).coerceAtLeast(0)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (plainPos >= plain.size) {
            if (eos) return C.RESULT_END_OF_INPUT
            if (!fillBlock()) return C.RESULT_END_OF_INPUT
        }
        val n = min(length, plain.size - plainPos)
        System.arraycopy(plain, plainPos, buffer, offset, n)
        plainPos += n
        return n
    }

    private fun fillBlock(): Boolean {
        var fill = 0
        while (fill < 2048) {
            val r = up.read(raw, fill, 2048 - fill)
            if (r == C.RESULT_END_OF_INPUT) { eos = true; break }
            fill += r
        }
        if (fill == 0) return false
        plain = if (fill == 2048 && blockIndex % 3L == 0L) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
            cipher.doFinal(raw, 0, 2048)
        } else {
            raw.copyOf(fill)
        }
        plainPos = 0
        blockIndex++
        return true
    }

    override fun addTransferListener(transferListener: TransferListener) = up.addTransferListener(transferListener)
    override fun getUri(): Uri? = up.uri
    override fun close() = up.close()
}

private fun hexToBytes(h: String): ByteArray =
    ByteArray(h.length / 2) { ((h[it * 2].digitToInt(16) shl 4) + h[it * 2 + 1].digitToInt(16)).toByte() }

/**
 * Яндекс lossless (`transport: encraw`) — весь поток AES-128-CTR, начальный
 * счётчик = 16 нулей, инкремент 128-битным big-endian на каждые 16 Б.
 * Seek: счётчик для позиции `p` = `p / 16`, затем отбрасываем `p % 16` Б
 * первого расшифрованного блока (upstream открываем с 16-байтной границы).
 */
private class YandexAesCtrDataSource(
    private val up: DataSource,
    private val key: ByteArray,        // 16 байт
) : DataSource {
    private var cipher: Cipher? = null
    private val scratch = ByteArray(8192)

    override fun open(dataSpec: DataSpec): Long {
        val start = dataSpec.position
        val block = start / 16
        val within = (start % 16).toInt()
        val iv = ByteArray(16)
        var b = block
        for (i in 15 downTo 0) { iv[i] = (b and 0xFF).toByte(); b = b ushr 8 }
        cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val reqLen = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else dataSpec.length + within
        val upLen = up.open(
            dataSpec.buildUpon().setPosition(start - within).setLength(reqLen).build()
        )
        var skip = within
        while (skip > 0) {
            val n = read(scratch, 0, min(skip, scratch.size))
            if (n <= 0) break
            skip -= n
        }
        return if (upLen == C.LENGTH_UNSET.toLong()) upLen else (upLen - within).coerceAtLeast(0)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val tmp = if (length <= scratch.size) scratch else ByteArray(length)
        val n = up.read(tmp, 0, length)
        if (n == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        if (n <= 0) return n
        val dec = cipher!!.update(tmp, 0, n) ?: return 0   // CTR: update отдаёт n Б
        System.arraycopy(dec, 0, buffer, offset, dec.size)
        return dec.size
    }

    override fun addTransferListener(transferListener: TransferListener) = up.addTransferListener(transferListener)
    override fun getUri(): Uri? = up.uri
    override fun close() = up.close()
}
