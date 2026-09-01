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

/** Помечает URL как зашифрованный Deezer-поток для [RipsterDataSourceFactory]. */
fun tagDeezerBlowfish(url: String, trackId: String): String =
    if (url.contains("#")) "$url&$DZBF$trackId" else "$url#$DZBF$trackId"

private class DispatchDataSource(private val http: DataSource) : DataSource {
    private var active: DataSource = http

    override fun open(dataSpec: DataSpec): Long {
        val frag = dataSpec.uri.fragment
        val id = frag?.split('&', ';')
            ?.firstOrNull { it.startsWith(DZBF) }
            ?.removePrefix(DZBF)
        return if (id.isNullOrBlank()) {
            active = http
            active.open(dataSpec)
        } else {
            active = DeezerBlowfishDataSource(http, DeezerCrypto.blowfishKey(id))
            val clean = dataSpec.withUri(dataSpec.uri.buildUpon().fragment(null).build())
            active.open(clean)
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
