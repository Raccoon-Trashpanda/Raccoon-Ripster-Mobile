package net.ripster.mobile.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    /** Активная (в очереди или качается) задача по тому же треку — для дедупа
     *  повторного тапа «Скачать». */
    @Query(
        "SELECT * FROM downloads WHERE serviceId = :serviceId AND title = :title " +
            "AND artist = :artist AND state IN ('QUEUED', 'RUNNING') LIMIT 1",
    )
    suspend fun findActive(serviceId: String, title: String, artist: String): DownloadEntity?

    @Query("UPDATE downloads SET state = :state, updatedAt = :ts WHERE id = :id")
    suspend fun setState(id: String, state: String, ts: Long)

    @Query(
        "UPDATE downloads SET fraction = :fraction, downloadedBytes = :done, " +
            "totalBytes = :total, state = 'RUNNING', updatedAt = :ts WHERE id = :id"
    )
    suspend fun setProgress(id: String, fraction: Float?, done: Long, total: Long?, ts: Long)

    @Query(
        "UPDATE downloads SET state = 'DONE', filePath = :path, qualityId = :qualityId, " +
            "downloadedBytes = :bytes, fraction = 1.0, errorReason = NULL, updatedAt = :ts WHERE id = :id"
    )
    suspend fun markDone(id: String, path: String, qualityId: String, bytes: Long, ts: Long)

    @Query("UPDATE downloads SET state = 'FAILED', errorReason = :reason, updatedAt = :ts WHERE id = :id")
    suspend fun markFailed(id: String, reason: String, ts: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE state IN ('DONE', 'CANCELLED')")
    suspend fun clearFinished()

    /**
     * «Очистить всё» — именно всё, включая FAILED.
     *
     * 04.09.2026: кнопка звала clearFinished(), а та FAILED не трогает. У кого
     * очередь состояла из упавших задач — а это как раз тот, кому чистить нужнее
     * всего, — кнопка выглядела нерабочей: жмёшь, и ничего не исчезает.
     * Активные задачи вызывающий отменяет ДО этого; запоздалый UPDATE от
     * воркера по удалённой строке затронет 0 записей и вреда не сделает.
     */
    @Query("DELETE FROM downloads")
    suspend fun clearAll()

    /** Снимок последних записей — для отправки активности на ПК. */
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<DownloadEntity>
}

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LibraryEntity)

    @Query("SELECT * FROM library ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<LibraryEntity>

    @Query(
        "SELECT * FROM library WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' " +
            "ORDER BY addedAt DESC"
    )
    fun search(q: String): Flow<List<LibraryEntity>>

    /** Пути всего, что уже заведено. Импорт папки сверяется с этим списком,
     *  чтобы повторный проход не плодил дубли и не тратил время на разбор
     *  файлов, которые уже разобраны. */
    @Query("SELECT filePath FROM library")
    suspend fun allPaths(): List<String>

    /** Забыть запись: файла по этому адресу больше нет. */
    @Query("DELETE FROM library WHERE filePath = :path")
    suspend fun forgetPath(path: String)
}

@Dao
interface WatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WatchEntity)

    @Query("SELECT * FROM watchlist ORDER BY unseen DESC, latestDate DESC, addedAt DESC")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    suspend fun all(): List<WatchEntity>

    @Query("SELECT * FROM watchlist WHERE key = :key")
    suspend fun get(key: String): WatchEntity?

    @Query("SELECT COUNT(*) FROM watchlist WHERE serviceId = :service AND artistId = :artistId AND artistId <> ''")
    suspend fun countFor(service: String, artistId: String): Int

    @Query("SELECT COUNT(*) FROM watchlist WHERE unseen = 1")
    fun unseenCount(): Flow<Int>

    @Query(
        "UPDATE watchlist SET latestReleaseId = :relId, latestTitle = :title, latestUrl = :url, " +
            "latestCoverUrl = :cover, latestDate = :date, unseen = :unseen, lastCheck = :ts WHERE key = :key"
    )
    suspend fun setLatest(
        key: String, relId: String, title: String, url: String,
        cover: String?, date: String, unseen: Boolean, ts: Long,
    )

    @Query("UPDATE watchlist SET lastCheck = :ts WHERE key = :key")
    suspend fun touch(key: String, ts: Long)

    @Query("UPDATE watchlist SET unseen = 0 WHERE key = :key")
    suspend fun markSeen(key: String)

    @Query("UPDATE watchlist SET unseen = 0")
    suspend fun markAllSeen()

    @Query("DELETE FROM watchlist WHERE key = :key")
    suspend fun delete(key: String)
}

@Dao
interface PlayDao {

    @Insert
    suspend fun add(row: PlayEntity)

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 60): Flow<List<PlayEntity>>

    /** Снимок последних прослушиваний — для отправки активности на ПК. */
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<PlayEntity>

    /** Самый свежий трек по каждому жанру — для галереи «Что игралось». */
    @Query(
        "SELECT * FROM play_history WHERE genre <> '' AND rowId IN " +
            "(SELECT MAX(rowId) FROM play_history WHERE genre <> '' GROUP BY genre) " +
            "ORDER BY playedAt DESC"
    )
    fun observeByGenre(): Flow<List<PlayEntity>>

    @Query("SELECT genre, COUNT(*) c FROM play_history WHERE genre <> '' GROUP BY genre ORDER BY c DESC")
    fun observeGenreCounts(): Flow<List<GenreCount>>

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

data class GenreCount(val genre: String, val c: Int)

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE key = :key")
    suspend fun remove(key: String)

    @Query("SELECT key FROM favorites")
    fun keys(): Flow<List<String>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE key = :key)")
    suspend fun isFav(key: String): Boolean
}
