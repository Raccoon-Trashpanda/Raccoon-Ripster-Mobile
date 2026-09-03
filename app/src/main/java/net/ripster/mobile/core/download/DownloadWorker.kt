package net.ripster.mobile.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.db.LibraryEntity
import net.ripster.mobile.core.model.DownloadEvent
import net.ripster.mobile.core.model.DownloadRequest
import net.ripster.mobile.core.model.DownloadState
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.net.NetworkType
import net.ripster.mobile.core.net.RipsterHttp
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.core.storage.TagWriter
import okhttp3.Request
import java.io.File

/**
 * Исполнитель одной загрузки. Живёт в WorkManager — переживает сворачивание
 * и смерть процесса, возобновляется системой. Прогресс пишет в Room, оттуда
 * его читает UI (и будущий «орб» загрузок из ПК-версии).
 *
 * Клиент сервиса доводит дело до готового файла в кэше; перенос в
 * пользовательскую папку (SAF) и теги — следующий шаг Этапа 1b, пока файл
 * остаётся в `cacheDir` и путь виден в очереди.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Обязателен для expedited-работы: WorkManager дёргает его ДО [doWork],
     * а базовый `CoroutineWorker` тут бросает «Not implemented». Заголовок —
     * общий, конкретное имя трека ставится в [doWork] через `setForeground`.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_ID)
        val title = id?.let {
            runCatching { RipsterApp.from(applicationContext).db.downloads().get(it)?.title }.getOrNull()
        }
        return foregroundInfo(title ?: "…")
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val app = RipsterApp.from(applicationContext)
        val dao = app.db.downloads()
        val row = dao.get(id) ?: return Result.failure()

        val track = runCatching { json.decodeFromString(Track.serializer(), row.trackJson) }
            .getOrElse {
                dao.markFailed(id, "corrupt task record", now())
                return Result.failure()
            }

        val client = ServiceRegistry.get(track.service)
        if (client == null) {
            dao.markFailed(id, "no client for ${track.service.id}", now())
            return Result.failure()
        }

        setForeground(foregroundInfo(track.title))
        dao.setState(id, DownloadState.RUNNING.name, now())

        try {
            // Качество зависит от типа сети — как в Apple Music (Wi-Fi/сотовая).
            val onWifi = NetworkType.isOnWifi(applicationContext)
            // Качество ПО СЕРВИСУ (Настройки → Качество → по сервисам) главнее
            // глобального сетевого предпочтения.
            val pref = app.settings.state.value.qualityPrefFor(row.serviceId, onWifi)
            // Что реально запросили — принудительный тир либо верх предпочтения.
            // По нему потом ловим «просил FLAC, пришло lossy» (бейдж Mismatch).
            val requestedQualityId = row.forcedQualityId ?: pref.firstOrNull()
            client.download(
                DownloadRequest(track, qualityPreference = pref, forcedQualityId = row.forcedQualityId),
            ).collect { ev ->
                when (ev) {
                    is DownloadEvent.Progress ->
                        dao.setProgress(id, ev.fraction, ev.downloadedBytes, ev.totalBytes, now())
                    is DownloadEvent.Done -> {
                        val cacheFile = File(ev.filePath)

                        // Теги в файл (в кэше, до переноса). Сбой не критичен —
                        // файл уже скачан. Apple-файл пришёл с ПК уже
                        // протегированный движком — не перезаписываем.
                        if (track.service != net.ripster.mobile.core.model.Service.APPLE) {
                            // Сначала добираем недостающее (жанр, номер, дата,
                            // лейбл, обложка) по ISRC — автономно, на телефоне.
                            // То, что сервис уже дал, не трогается.
                            val full = runCatching {
                                net.ripster.mobile.core.tagger.TrackEnricher.enrich(track)
                            }.getOrDefault(track)
                            runCatching { TagWriter.write(cacheFile, full, fetchArtwork(full)) }
                        }

                        // Настоящие параметры из заголовка файла (не то, что обещал сервис).
                        val probe = net.ripster.mobile.core.storage.AudioProbe.probe(cacheFile)

                        // Apple: имя в задаче — заглушка из слага ссылки. Берём
                        // настоящее из тегов, которые проставил движок на ПК.
                        val tags = if (track.service == net.ripster.mobile.core.model.Service.APPLE)
                            net.ripster.mobile.core.storage.AudioProbe.tags(cacheFile) else null
                        val realTitle = tags?.title ?: track.title
                        val realArtist = tags?.artist ?: track.artist
                        val realAlbum = tags?.album ?: track.albumTitle

                        val sizeBytes = if (cacheFile.exists()) cacheFile.length() else ev.bytes

                        // Если пользователь выбрал папку (SAF) — переносим туда
                        // по шаблону имени. Не получилось — оставляем в кэше и
                        // честно указываем кэш-путь, а не врём про «сохранено».
                        val treeUri = app.settings.state.value.downloadTreeUri
                        val finalPath = if (app.storage.hasTree(treeUri)) {
                            // Файл уже скачан. Сбой переноса в папку не должен
                            // терять успех — тогда просто остаётся в кэше.
                            runCatching {
                                app.storage.moveIntoLibrary(
                                    cacheFile = cacheFile,
                                    treeUri = treeUri,
                                    template = app.settings.state.value.nameTemplate,
                                    track = if (tags != null)
                                        track.copy(title = realTitle, artist = realArtist, albumTitle = realAlbum)
                                    else track,
                                    quality = ev.quality,
                                )
                            }.getOrNull() ?: ev.filePath
                        } else {
                            ev.filePath
                        }

                        dao.markDone(id, finalPath, ev.quality.id, ev.bytes, now())
                        app.db.library().upsert(
                            LibraryEntity(
                                id = id,
                                title = realTitle,
                                artist = realArtist,
                                album = realAlbum,
                                serviceId = track.service.id,
                                container = ev.quality.container,
                                bitrateKbps = probe?.bitrateKbps ?: ev.quality.bitrateKbps,
                                filePath = finalPath,
                                sizeBytes = sizeBytes,
                                artworkUrl = track.artworkUrl,
                                addedAt = now(),
                                sampleRateHz = probe?.sampleRateHz ?: ev.quality.sampleRateHz,
                                bitDepth = probe?.bitDepth ?: ev.quality.bitDepth,
                                lossless = probe?.lossless ?: ev.quality.lossless,
                                fakeLossless = probe?.fakeLossless ?: false,
                                requestedQualityId = requestedQualityId,
                                durationSec = probe?.durationSec ?: 0,
                                label = track.raw["label"]?.takeIf { it.isNotBlank() },
                            )
                        )
                    }
                    is DownloadEvent.Error -> dao.markFailed(id, ev.reason, now())
                    is DownloadEvent.Log -> Unit
                }
            }
        } catch (ce: CancellationException) {
            dao.setState(id, DownloadState.CANCELLED.name, now())
            throw ce
        } catch (t: Throwable) {
            dao.markFailed(id, t.message ?: t.javaClass.simpleName, now())
            return Result.failure()
        }

        return if (dao.get(id)?.state == DownloadState.DONE.name) Result.success() else Result.failure()
    }

    private fun foregroundInfo(title: String): ForegroundInfo {
        ensureChannel(applicationContext)
        val n: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Ripster")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    private fun now() = System.currentTimeMillis()

    /** Обложка для встраивания в тег. null, если ссылки нет или скачать не вышло. */
    private fun fetchArtwork(track: Track): ByteArray? {
        val url = track.artworkUrl?.takeIf { it.startsWith("http") } ?: return null
        return runCatching {
            RipsterHttp.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    companion object {
        const val KEY_ID = "download_id"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 4711

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
