package net.ripster.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * Общие модели для всех сервисов. Каждый клиент (Deezer, Qobuz, Tidal, …)
 * мапит свой сырой JSON в эти типы — дальше по приложению ходят только они,
 * как на десктопе движки сводятся к общему словарю событий в `runner.py`.
 *
 * Ничего сервис-специфичного здесь быть не должно: если поле нужно только
 * одному сервису, ему место в `raw` или в реализации клиента, не тут.
 */

/** Какой сервис отдал эту сущность. Строка совпадает с ключом в [net.ripster.mobile.core.service.ServiceRegistry]. */
@Serializable
enum class Service(val id: String, val label: String) {
    SOUNDCLOUD("soundcloud", "SoundCloud"),
    DEEZER("deezer", "Deezer"),
    QOBUZ("qobuz", "Qobuz"),
    TIDAL("tidal", "Tidal"),
    BBC("bbc", "BBC"),
    SPOTIFY("spotify", "Spotify"),
    YANDEX("yandex", "Yandex Music"),
    BEATPORT("beatport", "Beatport"),
    /** Только через сопряжение с ПК (Docker-враппер живёт там). */
    APPLE("apple", "Apple Music");

    companion object {
        fun byId(id: String): Service? = entries.firstOrNull { it.id == id }
    }
}

/** Тип ссылки/запроса, который распознал клиент. */
@Serializable
enum class MediaKind { TRACK, ALBUM, PLAYLIST, ARTIST }

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val service: Service,
    val artworkUrl: String? = null,
)

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val service: Service,
    val year: Int? = null,
    val trackCount: Int? = null,
    val artworkUrl: String? = null,
    val upc: String? = null,
    // ── Полные метаданные релиза ─────────────────────────────────────────
    // Сервисы отдают их на самом альбоме (Deezer/Qobuz/Tidal — genre, label,
    // release_date, copyright), а модель хранила только год: карточка релиза
    // на телефоне показывала меньше, чем та же карточка в ПК-версии, хотя
    // данные приходили в том же ответе. 04.09.2026.
    val genre: String? = null,
    val label: String? = null,
    val releaseDate: String? = null,
    val copyright: String? = null,
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val service: Service,
    val albumTitle: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    // ── Полные метаданные для тегов файла ────────────────────────────────
    // Жалоба тестера 03.09.2026: «в скачанных из Qobuz файлах нет обложки,
    // жанра, номера трека, композитора». Писать было НЕЧЕГО — модель этих
    // полей просто не имела, хотя сервисы их отдают. Всё опционально: чего
    // сервис не дал, то в тег и не уедет.
    val genre: String? = null,
    val composer: String? = null,
    val label: String? = null,
    val copyright: String? = null,
    val upc: String? = null,
    val trackTotal: Int? = null,
    val discTotal: Int? = null,
    /** Полная дата релиза, ISO `YYYY-MM-DD` (год отдельно лежит в [year]). */
    val releaseDate: String? = null,
    /** Сырые поля сервиса, которые понадобятся его же загрузчику (id стрима, ключ, регион…). */
    val raw: Map<String, String> = emptyMap(),
)

/**
 * Что клиент нашёл по поисковому запросу или по [ServiceClient.resolve].
 * `tracks`/`albums`/`artists` — то, что реально вернул сервис; пустые списки
 * это нормальный ответ, не ошибка.
 */
data class MediaSelection(
    val kind: MediaKind,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    /** Заголовок альбома/плейлиста, если запрос был на контейнер. */
    val containerTitle: String? = null,
)

/**
 * Уровень качества. У каждого сервиса свой набор — клиент отдаёт список
 * доступного через [ServiceClient.qualities], пользователь или настройка
 * выбирает. `bitrateKbps`/`bitDepth`/`sampleRateHz` — null, если сервис не
 * обещает конкретику (тогда честный бейдж «не измерено», а не выдуманное число).
 */
@Serializable
data class QualityTier(
    val id: String,
    val label: String,
    val lossless: Boolean,
    val container: String,          // "flac", "mp3", "m4a", …
    val bitrateKbps: Int? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
) {
    companion object {
        /** Порядок предпочтения по умолчанию — общий, до per-service override из настроек. */
        val DEFAULT_PREFERENCE = listOf("flac_24", "flac_16", "flac", "mp3_320", "aac_256", "mp3_128")
    }
}

/** Конкретный поток для скачивания — результат [ServiceClient.streamInfo]. */
data class StreamInfo(
    val url: String,
    val quality: QualityTier,
    /** Схема расшифровки, если поток зашифрован (Deezer Blowfish и т.п.). null = чистый файл. */
    val decryption: Decryption? = null,
    /** Байт, если сервис их сообщил заранее — для честного прогресса. */
    val sizeBytes: Long? = null,
    val headers: Map<String, String> = emptyMap(),
)

sealed interface Decryption {
    /** Deezer: Blowfish-CBC блоками по 2048 Б, дешифруется каждый третий блок; ключ выводится из id трека. */
    data class DeezerBlowfish(val trackId: String) : Decryption

    /** Яндекс lossless (`transport: encraw`): весь поток — AES-128-CTR, IV = 16 нулей. */
    data class YandexAesCtr(val keyHex: String) : Decryption
}
