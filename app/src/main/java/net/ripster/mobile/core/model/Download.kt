package net.ripster.mobile.core.model

/** Запрос на скачивание одного трека. Альбом → N таких, как `resolver.py` на десктопе. */
data class DownloadRequest(
    val track: Track,
    val qualityPreference: List<String> = QualityTier.DEFAULT_PREFERENCE,
    /** Если пользователь/настройка зафиксировали конкретный tier — он тут, и preference игнорируется. */
    val forcedQualityId: String? = null,
)

/**
 * События загрузки — прямой аналог десктопного `iter_events()`, который yield-ит
 * `{"type": "progress"|"log"|"done"|"error", ...}`. Клиент отдаёт `Flow<DownloadEvent>`,
 * очередь их разбирает и обновляет UI.
 */
sealed interface DownloadEvent {
    /** Доля 0..1, либо null когда сервис не сообщает размер — тогда UI крутит indeterminate, а не рисует выдуманный %. */
    data class Progress(val fraction: Float?, val downloadedBytes: Long, val totalBytes: Long?) : DownloadEvent
    data class Log(val line: String) : DownloadEvent
    data class Done(val filePath: String, val quality: QualityTier, val bytes: Long) : DownloadEvent
    data class Error(val reason: String, val cause: Throwable? = null) : DownloadEvent
}

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** Строка очереди — то, что живёт в БД и рисуется на экране «Загрузки». */
data class DownloadItem(
    val id: String,
    val track: Track,
    val state: DownloadState,
    val fraction: Float? = null,
    val filePath: String? = null,
    val errorReason: String? = null,
    val quality: QualityTier? = null,
)
