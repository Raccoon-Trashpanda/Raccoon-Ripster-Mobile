package net.ripster.mobile.core.storage

import net.ripster.mobile.core.model.Track
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Проставление тегов в СКАЧАННЫЙ файл (в кэше, до переноса в папку
 * пользователя). Форматы — через JAudiotagger: FLAC → Vorbis comments,
 * MP3 → ID3v2, M4A → MP4 atoms. Сырой ADTS-`.aac` тегов не держит — такой
 * файл просто пропускаем.
 *
 * Ничего отсюда не должно ронять загрузку: файл уже скачан, теги — бонус.
 */
object TagWriter {

    init {
        // JAudiotagger по умолчанию сыплет INFO-логами на каждый вызов.
        runCatching { Logger.getLogger("org.jaudiotagger").level = Level.SEVERE }
    }

    private val TAGGABLE = setOf("flac", "mp3", "m4a", "mp4", "ogg")

    /** @return true если теги записаны; false — формат без тегов или ошибка (не критично). */
    fun write(file: File, track: Track, artwork: ByteArray?): Boolean {
        val ext = file.extension.lowercase()
        if (ext !in TAGGABLE || !file.exists()) return false
        return runCatching {
            val af = AudioFileIO.read(file)
            val tag = af.tagOrCreateAndSetDefault

            tag.setField(FieldKey.TITLE, track.title)
            tag.setField(FieldKey.ARTIST, track.artist)
            (track.albumTitle ?: track.title).let { tag.setField(FieldKey.ALBUM, it) }
            (track.albumArtist ?: track.artist).let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
            track.trackNumber?.let { tag.setField(FieldKey.TRACK, it.toString()) }
            track.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
            track.year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
            track.isrc?.let { runCatching { tag.setField(FieldKey.ISRC, it) } }

            if (artwork != null && artwork.isNotEmpty()) {
                runCatching {
                    val art = ArtworkFactory.getNew().apply {
                        binaryData = artwork
                        mimeType = if (isPng(artwork)) "image/png" else "image/jpeg"
                    }
                    tag.deleteArtworkField()
                    tag.setField(art)
                }
            }
            af.commit()
            true
        }.getOrDefault(false)
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
}
