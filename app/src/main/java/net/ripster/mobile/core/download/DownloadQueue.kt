package net.ripster.mobile.core.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Дедуп «проверить-и-вставить» — критическая секция. Без сериализации два
    // быстрых тапа по одной кнопке (или тап по треку + тот же трек внутри
    // альбома) уходят в две корутины, обе делают findActive ДО того, как любая
    // из них закоммитила upsert, обе видят «активных нет» и обе вставляют строку.
    // Ровно это на видео 03.09.2026: «Jacob and the Stone» дважды в очереди.
    private val enqueueLock = Mutex()

    // Своя область для действий, у которых нет вызывающей корутины: отмена
    // приходит прямо из обработчика нажатия. SupervisorJob — чтобы одна
    // упавшая отмена не уносила остальные.
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Поставить весь релиз ОДНОЙ группой.
     *
     * Треки идут теми же задачами, что и одиночные, — просто с общим
     * [groupId]. Очередь показывает их одной сворачиваемой строкой, а
     * качаются они по очереди (см. [DownloadWorker.gate]): тринадцать
     * параллельных загрузок на слабом телефоне — это не быстрее, это лаги.
     *
     * Возвращает id группы, либо null, если ставить было нечего.
     */
    suspend fun enqueueRelease(
        title: String,
        tracks: List<Track>,
        forcedQualityId: String? = null,
    ): String? {
        if (tracks.isEmpty()) return null
        // Одинокий трек группой не оформляем: строка «релиз из одного трека»
        // ничего не сообщает, только прячет сам трек за раскрытием.
        if (tracks.size == 1) { enqueue(tracks.first(), forcedQualityId); return null }
        val gid = UUID.randomUUID().toString()
        tracks.forEach { enqueue(it, forcedQualityId, gid, title) }
        return gid
    }

    suspend fun enqueue(
        track: Track,
        forcedQualityId: String? = null,
        groupId: String? = null,
        groupTitle: String? = null,
    ): String = enqueueLock.withLock {
        // Дедуп: повторный тап «Скачать» по тому же треку (или трек альбома,
        // уже стоящий в очереди) не должен плодить вторую строку.
        dao.findActive(track.service.id, track.title, track.artist)?.let { return@withLock it.id }
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
                groupId = groupId,
                groupTitle = groupTitle,
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
        id
    }

    /** Собрать заявку на работу для уже лежащей в базе строки. */
    private fun workFor(id: String): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnlyProvider()) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(20))
            .addTag(TAG)
            .build()
    }

    /**
     * Свести базу с действительностью при запуске.
     *
     * Room помнит состояние, WorkManager выполняет — и эти двое расходятся
     * молча. Процесс убили в момент загрузки (обновление приложения, сборщик
     * памяти, «Остановить» в настройках) — строка так и остаётся RUNNING, а
     * работать над ней уже некому. 05.09.2026 владелец увидел ровно это:
     * девятнадцать треков висели «качается» с 13:44, до вечера, и не двигались
     * ни на процент. Экран говорил «идёт загрузка», хотя не шло ничего.
     *
     * Ни один прежний путь этого не ловил: воркер сам себя воскресить не может,
     * а очередь при старте базу не читала вовсе.
     *
     * Чиним по факту, а не по догадке: спрашиваем WorkManager, жива ли работа.
     * Жива — не трогаем. Нет — возвращаем в QUEUED и ставим работу заново.
     * Именно QUEUED, а не FAILED: ничего не падало, просто некому было делать.
     */
    suspend fun reconcileOnStart(): Int {
        val rows = runCatching { dao.unfinished() }.getOrNull() ?: return 0
        var revived = 0
        for (row in rows) {
            val alive = runCatching {
                wm.getWorkInfosForUniqueWork("dl_${row.id}").get()
                    .any { !it.state.isFinished }
            }.getOrDefault(false)
            if (alive) continue
            dao.setState(row.id, DownloadState.QUEUED.name, System.currentTimeMillis())
            wm.enqueueUniqueWork("dl_${row.id}", ExistingWorkPolicy.REPLACE, workFor(row.id))
            revived++
        }
        if (revived > 0) {
            android.util.Log.i("RipsterDownloads", "reconcile: revived $revived orphaned task(s)")
        }
        return revived
    }

    fun cancel(id: String) {
        wm.cancelUniqueWork("dl_$id")
        // Раньше здесь стояло: «финальное состояние проставит сам воркер, а
        // если он ещё не стартовал — подчистим при следующем наблюдении».
        // Никакой подчистки не было. Задача, отменённая ДО запуска, оставалась
        // в состоянии «в очереди» навсегда, и человек видел: нажал «Отмена» —
        // ничего не произошло. Тестер 06.09.2026: «нажатие кнопки Отмена не
        // приводит к отмене загрузки»; при этом «Очистить всё» работало —
        // потому что оно правит базу напрямую.
        //
        // Теперь состояние проставляем сами и сразу. Для уже идущей задачи
        // воркер поставит своё CANCELLED, поймав отмену, — то же значение,
        // расхождения не будет.
        scope.launch {
            val row = dao.get(id) ?: return@launch
            if (row.state == DownloadState.DONE.name) return@launch   // доделанное не отменяют
            dao.setState(id, DownloadState.CANCELLED.name, System.currentTimeMillis())
        }
    }

    suspend fun retry(id: String): String? {
        val row = dao.get(id) ?: return null
        val track = runCatching { json.decodeFromString(Track.serializer(), row.trackJson) }.getOrNull() ?: return null
        dao.delete(id)
        // Повтор возвращает трек В ЕГО релиз: иначе один упавший трек альбома
        // после повтора выпадал из группы отдельной строкой.
        return enqueue(track, row.forcedQualityId, row.groupId, row.groupTitle)
    }

    suspend fun clearFinished() = dao.clearFinished()

    /** Снести очередь целиком, вместе с упавшими. Отменять активные — на вызывающем. */
    suspend fun clearAll() = dao.clearAll()

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
            groupId = groupId,
            groupTitle = groupTitle,
        )
    }

    companion object {
        const val TAG = "download"
    }
}
