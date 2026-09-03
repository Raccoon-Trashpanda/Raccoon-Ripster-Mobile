package net.ripster.mobile.core.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import net.ripster.mobile.core.db.DownloadDao
import net.ripster.mobile.core.db.DownloadEntity
import net.ripster.mobile.core.model.DownloadItem
import net.ripster.mobile.core.model.DownloadState
import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Track
import java.time.Duration
import java.util.UUID

/**
 * Постановка загрузок в очередь и наблюдение за ней. Room — источник правды
 * о состоянии, WorkManager — исполнитель. Одна задача = одна уникальная
 * работа `dl_<id>`, так её можно адресно отменить.
 */
class DownloadQueue(
    private val context: Context,
    private val dao: DownloadDao,
    private val wifiOnlyProvider: () -> Boolean,
) {
    private val wm get() = WorkManager.getInstance(context)
    private val json = Json { encodeDefaults = true }

    suspend fun enqueue(track: Track, forcedQualityId: String? = null): String {
        // Дедуп: повторный тап «Скачать» по тому же треку (или трек альбома,
        // уже стоящий в очереди) не должен плодить вторую строку.
        dao.findActive(track.service.id, track.title, track.artist)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val nowTs = System.currentTimeMillis()
        dao.upsert(
            DownloadEntity(
                id = id,
                serviceId = track.service.id,
                trackJson = json.encodeToString(Track.serializer(), track),
                title = track.title,
                artist = track.artist,
                state = DownloadState.QUEUED.name,
                fraction = null,
                downloadedBytes = 0,
                totalBytes = null,
                filePath = null,
                errorReason = null,
                qualityId = null,
                forcedQualityId = forcedQualityId,
                createdAt = nowTs,
                updatedAt = nowTs,
            )
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnlyProvider()) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(20))
            .addTag(TAG)
            .build()

        wm.enqueueUniqueWork("dl_$id", ExistingWorkPolicy.KEEP, req)
        return id
    }

    fun cancel(id: String) {
        wm.cancelUniqueWork("dl_$id")
        // Финальное состояние CANCELLED проставит сам воркер, поймав отмену;
        // если он ещё не стартовал — подчистим здесь при следующем наблюдении.
    }

    suspend fun retry(id: String): String? {
        val row = dao.get(id) ?: return null
        val track = runCatching { json.decodeFromString(Track.serializer(), row.trackJson) }.getOrNull() ?: return null
        dao.delete(id)
        return enqueue(track, row.forcedQualityId)
    }

    suspend fun clearFinished() = dao.clearFinished()

    fun observeQueue(): Flow<List<DownloadItem>> =
        dao.observeAll().map { list -> list.map { it.toItem() } }

    fun observe(id: String): Flow<DownloadItem?> =
        dao.observe(id).map { it?.toItem() }

    private fun DownloadEntity.toItem(): DownloadItem {
        val track = runCatching { json.decodeFromString(Track.serializer(), trackJson) }.getOrNull()
            ?: Track(id = serviceId, title = title, artist = artist, service = net.ripster.mobile.core.model.Service.byId(serviceId) ?: net.ripster.mobile.core.model.Service.SOUNDCLOUD)
        return DownloadItem(
            id = id,
            track = track,
            state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.QUEUED),
            fraction = fraction,
            filePath = filePath,
            errorReason = errorReason,
            quality = qualityId?.let { QualityTier(it, it, lossless = false, container = "") },
        )
    }

    companion object {
        const val TAG = "download"
    }
}
