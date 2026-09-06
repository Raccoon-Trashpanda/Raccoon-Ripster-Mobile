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
import java.lang.Integer;
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
public final class WatchDao_Impl implements WatchDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<WatchEntity> __insertionAdapterOfWatchEntity;

  private final SharedSQLiteStatement __preparedStmtOfSetLatest;

  private final SharedSQLiteStatement __preparedStmtOfTouch;

  private final SharedSQLiteStatement __preparedStmtOfMarkSeen;

  private final SharedSQLiteStatement __preparedStmtOfMarkAllSeen;

  private final SharedSQLiteStatement __preparedStmtOfDelete;

  public WatchDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfWatchEntity = new EntityInsertionAdapter<WatchEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `watchlist` (`key`,`kind`,`serviceId`,`artistId`,`name`,`coverUrl`,`latestReleaseId`,`latestTitle`,`latestUrl`,`latestCoverUrl`,`latestDate`,`unseen`,`lastCheck`,`addedAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final WatchEntity entity) {
        statement.bindString(1, entity.getKey());
        statement.bindString(2, entity.getKind());
        statement.bindString(3, entity.getServiceId());
        statement.bindString(4, entity.getArtistId());
        statement.bindString(5, entity.getName());
        if (entity.getCoverUrl() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getCoverUrl());
        }
        statement.bindString(7, entity.getLatestReleaseId());
        statement.bindString(8, entity.getLatestTitle());
        statement.bindString(9, entity.getLatestUrl());
        if (entity.getLatestCoverUrl() == null) {
          statement.bindNull(10);
        } else {
          statement.bindString(10, entity.getLatestCoverUrl());
        }
        statement.bindString(11, entity.getLatestDate());
        final int _tmp = entity.getUnseen() ? 1 : 0;
        statement.bindLong(12, _tmp);
        statement.bindLong(13, entity.getLastCheck());
        statement.bindLong(14, entity.getAddedAt());
      }
    };
    this.__preparedStmtOfSetLatest = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE watchlist SET latestReleaseId = ?, latestTitle = ?, latestUrl = ?, latestCoverUrl = ?, latestDate = ?, unseen = ?, lastCheck = ? WHERE key = ?";
        return _query;
      }
    };
    this.__preparedStmtOfTouch = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE watchlist SET lastCheck = ? WHERE key = ?";
        return _query;
      }
    };
    this.__preparedStmtOfMarkSeen = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE watchlist SET unseen = 0 WHERE key = ?";
        return _query;
      }
    };
    this.__preparedStmtOfMarkAllSeen = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE watchlist SET unseen = 0";
        return _query;
      }
    };
    this.__preparedStmtOfDelete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM watchlist WHERE key = ?";
        return _query;
      }
    };
  }

  @Override
  public Object upsert(final WatchEntity row, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfWatchEntity.insert(row);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object setLatest(final String key, final String relId, final String title,
      final String url, final String cover, final String date, final boolean unseen, final long ts,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetLatest.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, relId);
        _argIndex = 2;
        _stmt.bindString(_argIndex, title);
        _argIndex = 3;
        _stmt.bindString(_argIndex, url);
        _argIndex = 4;
        if (cover == null) {
          _stmt.bindNull(_argIndex);
        } else {
          _stmt.bindString(_argIndex, cover);
        }
        _argIndex = 5;
        _stmt.bindString(_argIndex, date);
        _argIndex = 6;
        final int _tmp = unseen ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp);
        _argIndex = 7;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 8;
        _stmt.bindString(_argIndex, key);
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
          __preparedStmtOfSetLatest.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object touch(final String key, final long ts,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfTouch.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, ts);
        _argIndex = 2;
        _stmt.bindString(_argIndex, key);
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
          __preparedStmtOfTouch.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object markSeen(final String key, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfMarkSeen.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, key);
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
          __preparedStmtOfMarkSeen.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object markAllSeen(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfMarkAllSeen.acquire();
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
          __preparedStmtOfMarkAllSeen.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final String key, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDelete.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, key);
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
  public Flow<List<WatchEntity>> observeAll() {
    final String _sql = "SELECT * FROM watchlist ORDER BY unseen DESC, latestDate DESC, addedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"watchlist"}, new Callable<List<WatchEntity>>() {
      @Override
      @NonNull
      public List<WatchEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfKey = CursorUtil.getColumnIndexOrThrow(_cursor, "key");
          final int _cursorIndexOfKind = CursorUtil.getColumnIndexOrThrow(_cursor, "kind");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfArtistId = CursorUtil.getColumnIndexOrThrow(_cursor, "artistId");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfCoverUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "coverUrl");
          final int _cursorIndexOfLatestReleaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "latestReleaseId");
          final int _cursorIndexOfLatestTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "latestTitle");
          final int _cursorIndexOfLatestUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "latestUrl");
          final int _cursorIndexOfLatestCoverUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "latestCoverUrl");
          final int _cursorIndexOfLatestDate = CursorUtil.getColumnIndexOrThrow(_cursor, "latestDate");
          final int _cursorIndexOfUnseen = CursorUtil.getColumnIndexOrThrow(_cursor, "unseen");
          final int _cursorIndexOfLastCheck = CursorUtil.getColumnIndexOrThrow(_cursor, "lastCheck");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final List<WatchEntity> _result = new ArrayList<WatchEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final WatchEntity _item;
            final String _tmpKey;
            _tmpKey = _cursor.getString(_cursorIndexOfKey);
            final String _tmpKind;
            _tmpKind = _cursor.getString(_cursorIndexOfKind);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpArtistId;
            _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpCoverUrl;
            if (_cursor.isNull(_cursorIndexOfCoverUrl)) {
              _tmpCoverUrl = null;
            } else {
              _tmpCoverUrl = _cursor.getString(_cursorIndexOfCoverUrl);
            }
            final String _tmpLatestReleaseId;
            _tmpLatestReleaseId = _cursor.getString(_cursorIndexOfLatestReleaseId);
            final String _tmpLatestTitle;
            _tmpLatestTitle = _cursor.getString(_cursorIndexOfLatestTitle);
            final String _tmpLatestUrl;
            _tmpLatestUrl = _cursor.getString(_cursorIndexOfLatestUrl);
            final String _tmpLatestCoverUrl;
            if (_cursor.isNull(_cursorIndexOfLatestCoverUrl)) {
              _tmpLatestCoverUrl = null;
            } else {
              _tmpLatestCoverUrl = _cursor.getString(_cursorIndexOfLatestCoverUrl);
            }
            final String _tmpLatestDate;
            _tmpLatestDate = _cursor.getString(_cursorIndexOfLatestDate);
            final boolean _tmpUnseen;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfUnseen);
            _tmpUnseen = _tmp != 0;
            final long _tmpLastCheck;
            _tmpLastCheck = _cursor.getLong(_cursorIndexOfLastCheck);
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            _item = new WatchEntity(_tmpKey,_tmpKind,_tmpServiceId,_tmpArtistId,_tmpName,_tmpCoverUrl,_tmpLatestReleaseId,_tmpLatestTitle,_tmpLatestUrl,_tmpLatestCoverUrl,_tmpLatestDate,_tmpUnseen,_tmpLastCheck,_tmpAddedAt);
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
  public Object all(final Continuation<? super List<WatchEntity>> $completion) {
    final String _sql = "SELECT * FROM watchlist ORDER BY addedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<WatchEntity>>() {
      @Override
      @NonNull
      public List<WatchEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfKey = CursorUtil.getColumnIndexOrThrow(_cursor, "key");
          final int _cursorIndexOfKind = CursorUtil.getColumnIndexOrThrow(_cursor, "kind");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfArtistId = CursorUtil.getColumnIndexOrThrow(_cursor, "artistId");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfCoverUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "coverUrl");
          final int _cursorIndexOfLatestReleaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "latestReleaseId");
          final int _cursorIndexOfLatestTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "latestTitle");
          final int _cursorIndexOfLatestUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "latestUrl");
          final int _cursorIndexOfLatestCoverUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "latestCoverUrl");
          final int _cursorIndexOfLatestDate = CursorUtil.getColumnIndexOrThrow(_cursor, "latestDate");
          final int _cursorIndexOfUnseen = CursorUtil.getColumnIndexOrThrow(_cursor, "unseen");
          final int _cursorIndexOfLastCheck = CursorUtil.getColumnIndexOrThrow(_cursor, "lastCheck");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final List<WatchEntity> _result = new ArrayList<WatchEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final WatchEntity _item;
            final String _tmpKey;
            _tmpKey = _cursor.getString(_cursorIndexOfKey);
            final String _tmpKind;
            _tmpKind = _cursor.getString(_cursorIndexOfKind);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpArtistId;
            _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpCoverUrl;
            if (_cursor.isNull(_cursorIndexOfCoverUrl)) {
              _tmpCoverUrl = null;
            } else {
              _tmpCoverUrl = _cursor.getString(_cursorIndexOfCoverUrl);
            }
            final String _tmpLatestReleaseId;
            _tmpLatestReleaseId = _cursor.getString(_cursorIndexOfLatestReleaseId);
            final String _tmpLatestTitle;
            _tmpLatestTitle = _cursor.getString(_cursorIndexOfLatestTitle);
            final String _tmpLatestUrl;
            _tmpLatestUrl = _cursor.getString(_cursorIndexOfLatestUrl);
            final String _tmpLatestCoverUrl;
            if (_cursor.isNull(_cursorIndexOfLatestCoverUrl)) {
              _tmpLatestCoverUrl = null;
            } else {
              _tmpLatestCoverUrl = _cursor.getString(_cursorIndexOfLatestCoverUrl);
            }
            final String _tmpLatestDate;
            _tmpLatestDate = _cursor.getString(_cursorIndexOfLatestDate);
            final boolean _tmpUnseen;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfUnseen);
            _tmpUnseen = _tmp != 0;
            final long _tmpLastCheck;
            _tmpLastCheck = _cursor.getLong(_cursorIndexOfLastCheck);
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            _item = new WatchEntity(_tmpKey,_tmpKind,_tmpServiceId,_tmpArtistId,_tmpName,_tmpCoverUrl,_tmpLatestReleaseId,_tmpLatestTitle,_tmpLatestUrl,_tmpLatestCoverUrl,_tmpLatestDate,_tmpUnseen,_tmpLastCheck,_tmpAddedAt);
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

  @Override
  public Object get(final String key, final Continuation<? super WatchEntity> $completion) {
    final String _sql = "SELECT * FROM watchlist WHERE key = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, key);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<WatchEntity>() {
      @Override
      @Nullable
      public WatchEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfKey = CursorUtil.getColumnIndexOrThrow(_cursor, "key");
          final int _cursorIndexOfKind = CursorUtil.getColumnIndexOrThrow(_cursor, "kind");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfArtistId = CursorUtil.getColumnIndexOrThrow(_cursor, "artistId");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfCoverUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "coverUrl");
          final int _cursorIndexOfLatestReleaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "latestReleaseId");
          final int _cursorIndexOfLatestTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "latestTitle");
          final int _cursorIndexOfLatestUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "latestUrl");
          final int _cursorIndexOfLatestCoverUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "latestCoverUrl");
          final int _cursorIndexOfLatestDate = CursorUtil.getColumnIndexOrThrow(_cursor, "latestDate");
          final int _cursorIndexOfUnseen = CursorUtil.getColumnIndexOrThrow(_cursor, "unseen");
          final int _cursorIndexOfLastCheck = CursorUtil.getColumnIndexOrThrow(_cursor, "lastCheck");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final WatchEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpKey;
            _tmpKey = _cursor.getString(_cursorIndexOfKey);
            final String _tmpKind;
            _tmpKind = _cursor.getString(_cursorIndexOfKind);
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpArtistId;
            _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpCoverUrl;
            if (_cursor.isNull(_cursorIndexOfCoverUrl)) {
              _tmpCoverUrl = null;
            } else {
              _tmpCoverUrl = _cursor.getString(_cursorIndexOfCoverUrl);
            }
            final String _tmpLatestReleaseId;
            _tmpLatestReleaseId = _cursor.getString(_cursorIndexOfLatestReleaseId);
            final String _tmpLatestTitle;
            _tmpLatestTitle = _cursor.getString(_cursorIndexOfLatestTitle);
            final String _tmpLatestUrl;
            _tmpLatestUrl = _cursor.getString(_cursorIndexOfLatestUrl);
            final String _tmpLatestCoverUrl;
            if (_cursor.isNull(_cursorIndexOfLatestCoverUrl)) {
              _tmpLatestCoverUrl = null;
            } else {
              _tmpLatestCoverUrl = _cursor.getString(_cursorIndexOfLatestCoverUrl);
            }
            final String _tmpLatestDate;
            _tmpLatestDate = _cursor.getString(_cursorIndexOfLatestDate);
            final boolean _tmpUnseen;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfUnseen);
            _tmpUnseen = _tmp != 0;
            final long _tmpLastCheck;
            _tmpLastCheck = _cursor.getLong(_cursorIndexOfLastCheck);
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            _result = new WatchEntity(_tmpKey,_tmpKind,_tmpServiceId,_tmpArtistId,_tmpName,_tmpCoverUrl,_tmpLatestReleaseId,_tmpLatestTitle,_tmpLatestUrl,_tmpLatestCoverUrl,_tmpLatestDate,_tmpUnseen,_tmpLastCheck,_tmpAddedAt);
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
  public Object countFor(final String service, final String artistId,
      final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM watchlist WHERE serviceId = ? AND artistId = ? AND artistId <> ''";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindString(_argIndex, service);
    _argIndex = 2;
    _statement.bindString(_argIndex, artistId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
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
  public Flow<Integer> unseenCount() {
    final String _sql = "SELECT COUNT(*) FROM watchlist WHERE unseen = 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"watchlist"}, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
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

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
