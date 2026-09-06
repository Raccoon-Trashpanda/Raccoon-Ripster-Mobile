package net.ripster.mobile.core.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Float;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class DownloadDao_Impl implements DownloadDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<DownloadEntity> __insertionAdapterOfDownloadEntity;

  private final SharedSQLiteStatement __preparedStmtOfSetState;

  private final SharedSQLiteStatement __preparedStmtOfSetProgress;

  private final SharedSQLiteStatement __preparedStmtOfMarkDone;

  private final SharedSQLiteStatement __preparedStmtOfMarkFailed;

  private final SharedSQLiteStatement __preparedStmtOfDelete;

  private final SharedSQLiteStatement __preparedStmtOfClearFinished;

  public DownloadDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfDownloadEntity = new EntityInsertionAdapter<DownloadEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `downloads` (`id`,`serviceId`,`trackJson`,`title`,`artist`,`state`,`fraction`,`downloadedBytes`,`totalBytes`,`filePath`,`errorReason`,`qualityId`,`forcedQualityId`,`createdAt`,`updatedAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final DownloadEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getServiceId());
        statement.bindString(3, entity.getTrackJson());
        statement.bindString(4, entity.getTitle());
        statement.bindString(5, entity.getArtist());
        statement.bindString(6, entity.getState());
        if (entity.getFraction() == null) {
          statement.bindNull(7);
        } else {
          statement.bindDouble(7, entity.getFraction());
        }
        statement.bindLong(8, entity.getDownloadedBytes());
        if (entity.getTotalBytes() == null) {
          statement.bindNull(9);
        } else {
          statement.bindLong(9, entity.getTotalBytes());
        }
        if (entity.getFilePath() == null) {
          statement.bindNull(10);
        } else {
          statement.bindString(10, entity.getFilePath());
        }
        if (entity.getErrorReason() == null) {
          statement.bindNull(11);
        } else {
          statement.bindString(11, entity.getErrorReason());
        }
        if (entity.getQualityId() == null) {
          statement.bindNull(12);
        } else {
          statement.bindString(12, entity.getQualityId());
        }
        if (entity.getForcedQualityId() == null) {
          statement.bindNull(13);
        } else {
          statement.bindString(13, entity.getForcedQualityId());
        }
        statement.bindLong(14, entity.getCreatedAt());
        statement.bindLong(15, entity.getUpdatedAt());
      }
    };
    this.__preparedStmtOfSetState = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE downloads SET state = ?, updatedAt = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfSetProgress = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE downloads SET fraction = ?, downloadedBytes = ?, totalBytes = ?, state = 'RUNNING', updatedAt = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfMarkDone = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE downloads SET state = 'DONE', filePath = ?, qualityId = ?, downloadedBytes = ?, fraction = 1.0, errorReason = NULL, updatedAt = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfMarkFailed = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE downloads SET state = 'FAILED', errorReason = ?, updatedAt = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDelete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM downloads WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfClearFinished = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM downloads WHERE state IN ('DONE', 'CANCELLED')";
        return _query;
      }
    };
  }

  @Override
  public Object upsert(final DownloadEntity row, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfDownloadEntity.insert(row);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object setState(final String id, final String state, final long ts,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetState.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, state);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 3;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfSetState.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object setProgress(final String id, final Float fraction, final long done,
      final Long total, final long ts, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetProgress.acquire();
        int _argIndex = 1;
        if (fraction == null) {
          _stmt.bindNull(_argIndex);
        } else {
          _stmt.bindDouble(_argIndex, fraction);
        }
        _argIndex = 2;
        _stmt.bindLong(_argIndex, done);
        _argIndex = 3;
        if (total == null) {
          _stmt.bindNull(_argIndex);
        } else {
          _stmt.bindLong(_argIndex, total);
        }
        _argIndex = 4;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 5;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfSetProgress.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object markDone(final String id, final String path, final String qualityId,
      final long bytes, final long ts, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfMarkDone.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, path);
        _argIndex = 2;
        _stmt.bindString(_argIndex, qualityId);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, bytes);
        _argIndex = 4;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 5;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfMarkDone.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object markFailed(final String id, final String reason, final long ts,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfMarkFailed.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, reason);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 3;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfMarkFailed.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final String id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDelete.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDelete.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearFinished(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearFinished.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearFinished.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<DownloadEntity>> observeAll() {
    final String _sql = "SELECT * FROM downloads ORDER BY createdAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"downloads"}, new Callable<List<DownloadEntity>>() {
      @Override
      @NonNull
      public List<DownloadEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfTrackJson = CursorUtil.getColumnIndexOrThrow(_cursor, "trackJson");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfFraction = CursorUtil.getColumnIndexOrThrow(_cursor, "fraction");
          final int _cursorIndexOfDownloadedBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedBytes");
          final int _cursorIndexOfTotalBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "totalBytes");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfErrorReason = CursorUtil.getColumnIndexOrThrow(_cursor, "errorReason");
          final int _cursorIndexOfQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "qualityId");
          final int _cursorIndexOfForcedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "forcedQualityId");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final List<DownloadEntity> _result = new ArrayList<DownloadEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final DownloadEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpTrackJson;
            _tmpTrackJson = _cursor.getString(_cursorIndexOfTrackJson);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final Float _tmpFraction;
            if (_cursor.isNull(_cursorIndexOfFraction)) {
              _tmpFraction = null;
            } else {
              _tmpFraction = _cursor.getFloat(_cursorIndexOfFraction);
            }
            final long _tmpDownloadedBytes;
            _tmpDownloadedBytes = _cursor.getLong(_cursorIndexOfDownloadedBytes);
            final Long _tmpTotalBytes;
            if (_cursor.isNull(_cursorIndexOfTotalBytes)) {
              _tmpTotalBytes = null;
            } else {
              _tmpTotalBytes = _cursor.getLong(_cursorIndexOfTotalBytes);
            }
            final String _tmpFilePath;
            if (_cursor.isNull(_cursorIndexOfFilePath)) {
              _tmpFilePath = null;
            } else {
              _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            }
            final String _tmpErrorReason;
            if (_cursor.isNull(_cursorIndexOfErrorReason)) {
              _tmpErrorReason = null;
            } else {
              _tmpErrorReason = _cursor.getString(_cursorIndexOfErrorReason);
            }
            final String _tmpQualityId;
            if (_cursor.isNull(_cursorIndexOfQualityId)) {
              _tmpQualityId = null;
            } else {
              _tmpQualityId = _cursor.getString(_cursorIndexOfQualityId);
            }
            final String _tmpForcedQualityId;
            if (_cursor.isNull(_cursorIndexOfForcedQualityId)) {
              _tmpForcedQualityId = null;
            } else {
              _tmpForcedQualityId = _cursor.getString(_cursorIndexOfForcedQualityId);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _item = new DownloadEntity(_tmpId,_tmpServiceId,_tmpTrackJson,_tmpTitle,_tmpArtist,_tmpState,_tmpFraction,_tmpDownloadedBytes,_tmpTotalBytes,_tmpFilePath,_tmpErrorReason,_tmpQualityId,_tmpForcedQualityId,_tmpCreatedAt,_tmpUpdatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<DownloadEntity> observe(final String id) {
    final String _sql = "SELECT * FROM downloads WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, id);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"downloads"}, new Callable<DownloadEntity>() {
      @Override
      @Nullable
      public DownloadEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfTrackJson = CursorUtil.getColumnIndexOrThrow(_cursor, "trackJson");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfFraction = CursorUtil.getColumnIndexOrThrow(_cursor, "fraction");
          final int _cursorIndexOfDownloadedBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedBytes");
          final int _cursorIndexOfTotalBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "totalBytes");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfErrorReason = CursorUtil.getColumnIndexOrThrow(_cursor, "errorReason");
          final int _cursorIndexOfQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "qualityId");
          final int _cursorIndexOfForcedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "forcedQualityId");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final DownloadEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpTrackJson;
            _tmpTrackJson = _cursor.getString(_cursorIndexOfTrackJson);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final Float _tmpFraction;
            if (_cursor.isNull(_cursorIndexOfFraction)) {
              _tmpFraction = null;
            } else {
              _tmpFraction = _cursor.getFloat(_cursorIndexOfFraction);
            }
            final long _tmpDownloadedBytes;
            _tmpDownloadedBytes = _cursor.getLong(_cursorIndexOfDownloadedBytes);
            final Long _tmpTotalBytes;
            if (_cursor.isNull(_cursorIndexOfTotalBytes)) {
              _tmpTotalBytes = null;
            } else {
              _tmpTotalBytes = _cursor.getLong(_cursorIndexOfTotalBytes);
            }
            final String _tmpFilePath;
            if (_cursor.isNull(_cursorIndexOfFilePath)) {
              _tmpFilePath = null;
            } else {
              _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            }
            final String _tmpErrorReason;
            if (_cursor.isNull(_cursorIndexOfErrorReason)) {
              _tmpErrorReason = null;
            } else {
              _tmpErrorReason = _cursor.getString(_cursorIndexOfErrorReason);
            }
            final String _tmpQualityId;
            if (_cursor.isNull(_cursorIndexOfQualityId)) {
              _tmpQualityId = null;
            } else {
              _tmpQualityId = _cursor.getString(_cursorIndexOfQualityId);
            }
            final String _tmpForcedQualityId;
            if (_cursor.isNull(_cursorIndexOfForcedQualityId)) {
              _tmpForcedQualityId = null;
            } else {
              _tmpForcedQualityId = _cursor.getString(_cursorIndexOfForcedQualityId);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _result = new DownloadEntity(_tmpId,_tmpServiceId,_tmpTrackJson,_tmpTitle,_tmpArtist,_tmpState,_tmpFraction,_tmpDownloadedBytes,_tmpTotalBytes,_tmpFilePath,_tmpErrorReason,_tmpQualityId,_tmpForcedQualityId,_tmpCreatedAt,_tmpUpdatedAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object get(final String id, final Continuation<? super DownloadEntity> $completion) {
    final String _sql = "SELECT * FROM downloads WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<DownloadEntity>() {
      @Override
      @Nullable
      public DownloadEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfTrackJson = CursorUtil.getColumnIndexOrThrow(_cursor, "trackJson");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfFraction = CursorUtil.getColumnIndexOrThrow(_cursor, "fraction");
          final int _cursorIndexOfDownloadedBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedBytes");
          final int _cursorIndexOfTotalBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "totalBytes");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfErrorReason = CursorUtil.getColumnIndexOrThrow(_cursor, "errorReason");
          final int _cursorIndexOfQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "qualityId");
          final int _cursorIndexOfForcedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "forcedQualityId");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final DownloadEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpTrackJson;
            _tmpTrackJson = _cursor.getString(_cursorIndexOfTrackJson);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final Float _tmpFraction;
            if (_cursor.isNull(_cursorIndexOfFraction)) {
              _tmpFraction = null;
            } else {
              _tmpFraction = _cursor.getFloat(_cursorIndexOfFraction);
            }
            final long _tmpDownloadedBytes;
            _tmpDownloadedBytes = _cursor.getLong(_cursorIndexOfDownloadedBytes);
            final Long _tmpTotalBytes;
            if (_cursor.isNull(_cursorIndexOfTotalBytes)) {
              _tmpTotalBytes = null;
            } else {
              _tmpTotalBytes = _cursor.getLong(_cursorIndexOfTotalBytes);
            }
            final String _tmpFilePath;
            if (_cursor.isNull(_cursorIndexOfFilePath)) {
              _tmpFilePath = null;
            } else {
              _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            }
            final String _tmpErrorReason;
            if (_cursor.isNull(_cursorIndexOfErrorReason)) {
              _tmpErrorReason = null;
            } else {
              _tmpErrorReason = _cursor.getString(_cursorIndexOfErrorReason);
            }
            final String _tmpQualityId;
            if (_cursor.isNull(_cursorIndexOfQualityId)) {
              _tmpQualityId = null;
            } else {
              _tmpQualityId = _cursor.getString(_cursorIndexOfQualityId);
            }
            final String _tmpForcedQualityId;
            if (_cursor.isNull(_cursorIndexOfForcedQualityId)) {
              _tmpForcedQualityId = null;
            } else {
              _tmpForcedQualityId = _cursor.getString(_cursorIndexOfForcedQualityId);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _result = new DownloadEntity(_tmpId,_tmpServiceId,_tmpTrackJson,_tmpTitle,_tmpArtist,_tmpState,_tmpFraction,_tmpDownloadedBytes,_tmpTotalBytes,_tmpFilePath,_tmpErrorReason,_tmpQualityId,_tmpForcedQualityId,_tmpCreatedAt,_tmpUpdatedAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object findActive(final String serviceId, final String title, final String artist,
      final Continuation<? super DownloadEntity> $completion) {
    final String _sql = "SELECT * FROM downloads WHERE serviceId = ? AND title = ? AND artist = ? AND state IN ('QUEUED', 'RUNNING') LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 3);
    int _argIndex = 1;
    _statement.bindString(_argIndex, serviceId);
    _argIndex = 2;
    _statement.bindString(_argIndex, title);
    _argIndex = 3;
    _statement.bindString(_argIndex, artist);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<DownloadEntity>() {
      @Override
      @Nullable
      public DownloadEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfTrackJson = CursorUtil.getColumnIndexOrThrow(_cursor, "trackJson");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfFraction = CursorUtil.getColumnIndexOrThrow(_cursor, "fraction");
          final int _cursorIndexOfDownloadedBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedBytes");
          final int _cursorIndexOfTotalBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "totalBytes");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfErrorReason = CursorUtil.getColumnIndexOrThrow(_cursor, "errorReason");
          final int _cursorIndexOfQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "qualityId");
          final int _cursorIndexOfForcedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "forcedQualityId");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final DownloadEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpTrackJson;
            _tmpTrackJson = _cursor.getString(_cursorIndexOfTrackJson);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final Float _tmpFraction;
            if (_cursor.isNull(_cursorIndexOfFraction)) {
              _tmpFraction = null;
            } else {
              _tmpFraction = _cursor.getFloat(_cursorIndexOfFraction);
            }
            final long _tmpDownloadedBytes;
            _tmpDownloadedBytes = _cursor.getLong(_cursorIndexOfDownloadedBytes);
            final Long _tmpTotalBytes;
            if (_cursor.isNull(_cursorIndexOfTotalBytes)) {
              _tmpTotalBytes = null;
            } else {
              _tmpTotalBytes = _cursor.getLong(_cursorIndexOfTotalBytes);
            }
            final String _tmpFilePath;
            if (_cursor.isNull(_cursorIndexOfFilePath)) {
              _tmpFilePath = null;
            } else {
              _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            }
            final String _tmpErrorReason;
            if (_cursor.isNull(_cursorIndexOfErrorReason)) {
              _tmpErrorReason = null;
            } else {
              _tmpErrorReason = _cursor.getString(_cursorIndexOfErrorReason);
            }
            final String _tmpQualityId;
            if (_cursor.isNull(_cursorIndexOfQualityId)) {
              _tmpQualityId = null;
            } else {
              _tmpQualityId = _cursor.getString(_cursorIndexOfQualityId);
            }
            final String _tmpForcedQualityId;
            if (_cursor.isNull(_cursorIndexOfForcedQualityId)) {
              _tmpForcedQualityId = null;
            } else {
              _tmpForcedQualityId = _cursor.getString(_cursorIndexOfForcedQualityId);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _result = new DownloadEntity(_tmpId,_tmpServiceId,_tmpTrackJson,_tmpTitle,_tmpArtist,_tmpState,_tmpFraction,_tmpDownloadedBytes,_tmpTotalBytes,_tmpFilePath,_tmpErrorReason,_tmpQualityId,_tmpForcedQualityId,_tmpCreatedAt,_tmpUpdatedAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object recent(final int limit,
      final Continuation<? super List<DownloadEntity>> $completion) {
    final String _sql = "SELECT * FROM downloads ORDER BY createdAt DESC LIMIT ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, limit);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<DownloadEntity>>() {
      @Override
      @NonNull
      public List<DownloadEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfTrackJson = CursorUtil.getColumnIndexOrThrow(_cursor, "trackJson");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfFraction = CursorUtil.getColumnIndexOrThrow(_cursor, "fraction");
          final int _cursorIndexOfDownloadedBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedBytes");
          final int _cursorIndexOfTotalBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "totalBytes");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfErrorReason = CursorUtil.getColumnIndexOrThrow(_cursor, "errorReason");
          final int _cursorIndexOfQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "qualityId");
          final int _cursorIndexOfForcedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "forcedQualityId");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final List<DownloadEntity> _result = new ArrayList<DownloadEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final DownloadEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpTrackJson;
            _tmpTrackJson = _cursor.getString(_cursorIndexOfTrackJson);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final Float _tmpFraction;
            if (_cursor.isNull(_cursorIndexOfFraction)) {
              _tmpFraction = null;
            } else {
              _tmpFraction = _cursor.getFloat(_cursorIndexOfFraction);
            }
            final long _tmpDownloadedBytes;
            _tmpDownloadedBytes = _cursor.getLong(_cursorIndexOfDownloadedBytes);
            final Long _tmpTotalBytes;
            if (_cursor.isNull(_cursorIndexOfTotalBytes)) {
              _tmpTotalBytes = null;
            } else {
              _tmpTotalBytes = _cursor.getLong(_cursorIndexOfTotalBytes);
            }
            final String _tmpFilePath;
            if (_cursor.isNull(_cursorIndexOfFilePath)) {
              _tmpFilePath = null;
            } else {
              _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            }
            final String _tmpErrorReason;
            if (_cursor.isNull(_cursorIndexOfErrorReason)) {
              _tmpErrorReason = null;
            } else {
              _tmpErrorReason = _cursor.getString(_cursorIndexOfErrorReason);
            }
            final String _tmpQualityId;
            if (_cursor.isNull(_cursorIndexOfQualityId)) {
              _tmpQualityId = null;
            } else {
              _tmpQualityId = _cursor.getString(_cursorIndexOfQualityId);
            }
            final String _tmpForcedQualityId;
            if (_cursor.isNull(_cursorIndexOfForcedQualityId)) {
              _tmpForcedQualityId = null;
            } else {
              _tmpForcedQualityId = _cursor.getString(_cursorIndexOfForcedQualityId);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            _item = new DownloadEntity(_tmpId,_tmpServiceId,_tmpTrackJson,_tmpTitle,_tmpArtist,_tmpState,_tmpFraction,_tmpDownloadedBytes,_tmpTotalBytes,_tmpFilePath,_tmpErrorReason,_tmpQualityId,_tmpForcedQualityId,_tmpCreatedAt,_tmpUpdatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
