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

            // Полный набор тегов. Жалоба тестера 03.09.2026: в скачанном из
            // Qobuz не было ни жанра, ни композитора, ни номера трека — сервис
            // всё это отдаёт, просто до файла не доезжало. Каждое поле в своём
            // runCatching: не все контейнеры знают все ключи (напр. у сырого
            // ADTS/OGG нет части полей), и падать на этом загрузка не должна.
            track.genre?.let { runCatching { tag.setField(FieldKey.GENRE, it) } }
            track.composer?.let { runCatching { tag.setField(FieldKey.COMPOSER, it) } }
            track.label?.let { runCatching { tag.setField(FieldKey.RECORD_LABEL, it) } }
            track.trackTotal?.let { runCatching { tag.setField(FieldKey.TRACK_TOTAL, it.toString()) } }
            track.discTotal?.let { runCatching { tag.setField(FieldKey.DISC_TOTAL, it.toString()) } }
            track.upc?.let { runCatching { tag.setField(FieldKey.BARCODE, it) } }
            // Полная дата важнее одного года: плееры сортируют по ней.
            track.releaseDate?.let { runCatching { tag.setField(FieldKey.ORIGINAL_YEAR, it) } }
            track.copyright?.let {
                runCatching { tag.setField(FieldKey.COMMENT, it) }
            }

            if (artwork != null && artwork.isNotEmpty()) {
                // Обложка кладётся ОТДЕЛЬНО от текстовых тегов и раньше падала
                // молча в своём runCatching: текст в файл попадал, картинка —
                // нет, и снаружи это выглядело как «теги есть, обложки нет»
                // (проверено вскрытием FLAC 03.09.2026). Теперь: у Artwork
                // заполняются ОБЯЗАТЕЛЬНЫЕ поля (mime, тип, описание) — без них
                // FLAC-тег отвергает картинку, — а провал пишется в лог.
                val mime = if (isPng(artwork)) "image/png" else "image/jpeg"
                runCatching {
                    tag.deleteArtworkField()
                    val art = ArtworkFactory.getNew().apply {
                        binaryData = artwork
                        this.mimeType = mime
                        description = ""
                        pictureType = 3          // Cover (front)
                    }
                    tag.setField(art)
                }.recoverCatching {
                    // У FLAC собственный конструктор поля картинки: generic-путь
                    // на части версий библиотеки бросает, а этот проходит.
                    val flac = tag as? org.jaudiotagger.tag.flac.FlacTag
                        ?: throw it
                    flac.setField(
                        flac.createArtworkField(
                            artwork, 3, mime, "", 0, 0, 0, 0,
                        ),
                    )
                }.onFailure {
                    android.util.Log.w("RipsterTag", "обложка не записалась в ${file.name}: $it")
                }
            }
            af.commit()
            true
        }.onFailure {
            // НЕ глотаем: молчаливый провал давал файл вообще без тегов, и
            // понять это можно было только вскрыв файл (03.09.2026 — так и
            // вышло). Пишем причину в лог.
            android.util.Log.w("RipsterTag", "теги не записались в ${file.name}: $it")
        }.getOrDefault(false)
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
}
