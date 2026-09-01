package net.ripster.mobile.service.deezer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Расшифровка потока Deezer (`BF_CBC_STRIPE`). НЕ порт нашего кода — логика
 * взята из deemix/streamrip: файл идёт кусками по 2048 Б, зашифрован каждый
 * ТРЕТИЙ кусок (Blowfish-CBC, IV = 0x0001020304050607), остальные и хвост
 * короче 2048 Б — как есть. Ключ выводится из id трека.
 */
object DeezerCrypto {

    private const val SECRET = "g4el58wc0zvf9na1"
    private const val CHUNK = 2048
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /** Ключ Blowfish для конкретного трека. */
    fun blowfishKey(trackId: String): ByteArray {
        val idMd5 = md5Hex(trackId)
        val key = CharArray(16)
        for (i in 0 until 16) {
            key[i] = (idMd5[i].code xor idMd5[i + 16].code xor SECRET[i].code).toChar()
        }
        return String(key).toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Читает [input], пишет расшифрованное в [out], вызывает [onProgress] с
     * числом записанных байт. Останавливается по отмене корутины.
     */
    suspend fun decryptStream(
        input: InputStream,
        out: OutputStream,
        key: ByteArray,
        totalBytes: Long?,
        onProgress: (written: Long, total: Long?) -> Unit,
    ) {
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "Blowfish")
        val ivSpec = IvParameterSpec(IV)

        val buf = ByteArray(CHUNK)
        var index = 0
        var written = 0L
        input.use { src ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = readFully(src, buf)
                if (n <= 0) break
                val outBytes = if (n == CHUNK && index % 3 == 0) {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                    cipher.doFinal(buf, 0, CHUNK)
                } else {
                    buf.copyOf(n)
                }
                out.write(outBytes)
                written += outBytes.size
                onProgress(written, totalBytes)
                index++
                if (n < CHUNK) break
            }
        }
        out.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1))
        val sb = StringBuilder(32)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }
}
