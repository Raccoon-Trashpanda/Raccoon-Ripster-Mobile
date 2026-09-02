package net.ripster.mobile.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Строка очереди загрузок. Переживает перезапуск процесса — это её смысл:
 * WorkManager может возобновить работу, а UI показать историю. Полный [Track]
 * лежит JSON-ом в [trackJson], чтобы воркер мог пересобрать его и отдать
 * клиенту сервиса (клиенту нужен `raw` с permalink/ключами).
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val serviceId: String,
    val trackJson: String,
    val title: String,
    val artist: String,
    /** [net.ripster.mobile.core.model.DownloadState] по имени. */
    val state: String,
    val fraction: Float?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    /** Путь к готовому файлу в кэше (до переноса в пользовательскую папку). */
    val filePath: String?,
    val errorReason: String?,
    val qualityId: String?,
    val forcedQualityId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Скачанное, попавшее в библиотеку. Пишется при успешном завершении загрузки. */
@Entity(tableName = "library")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    /** Лейбл релиза, если сервис его отдал. Пусто — не показываем строку. */
    val label: String? = null,
    val serviceId: String,
    val container: String,
    val bitrateKbps: Int?,
    /** Длительность трека, сек (из заголовка файла). 0 — неизвестно. */
    val durationSec: Int = 0,
    val filePath: String,
    val sizeBytes: Long,
    val artworkUrl: String?,
    val addedAt: Long,
    // Настоящие параметры из заголовка файла (AudioProbe) — не то, что обещал сервис.
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val lossless: Boolean = false,
    /** Контейнер обещает lossless, заголовок — нет («MP3 в контейнере FLAC»). */
    val fakeLossless: Boolean = false,
    /** Что было запрошено (id тира), чтобы поймать «просил FLAC, пришло lossy». */
    val requestedQualityId: String? = null,
)

/**
 * История прослушивания — ВСЁ, что игралось, даже если этого нет в библиотеке.
 * «Память» того, что пелось и игралось: на Главной строится галерея по жанрам
 * (как «по жанрам/настроениям» в Apple/Яндексе). Одна строка = один запуск
 * трека; для галереи берём самый свежий по каждому жанру.
 */
/**
 * Локальный радар: за кем следит ТЕЛЕФОН сам, без ПК. Периодический воркер
 * дёргает `ServiceClient.getArtist()` и сравнивает самый свежий релиз с
 * [latestReleaseId]; появился новый — ставит [unseen] и шлёт уведомление.
 */
@Entity(tableName = "watchlist")
data class WatchEntity(
    /** "<serviceId>:<kind>:<artistId|name>". */
    @PrimaryKey val key: String,
    val kind: String,                 // artist | label
    val serviceId: String,
    val artistId: String = "",
    val name: String,
    val coverUrl: String? = null,
    /** id самого свежего СВОЕГО релиза, что телефон уже видел. */
    val latestReleaseId: String = "",
    val latestTitle: String = "",
    val latestUrl: String = "",
    val latestCoverUrl: String? = null,
    val latestDate: String = "",
    /** Есть непросмотренная новинка. */
    val unseen: Boolean = false,
    val lastCheck: Long = 0,
    val addedAt: Long,
)

@Entity(tableName = "play_history")
data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** Жанр из метаданных файла/сервиса; "" — неизвестен. */
    val genre: String = "",
    val serviceId: String = "",
    val artworkUrl: String? = null,
    val playedAt: Long,
)
