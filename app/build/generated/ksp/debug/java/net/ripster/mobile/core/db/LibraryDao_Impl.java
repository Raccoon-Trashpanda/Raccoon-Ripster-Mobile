package net.ripster.mobile.core.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.room.util.StringUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
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
public final class LibraryDao_Impl implements LibraryDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<LibraryEntity> __insertionAdapterOfLibraryEntity;

  public LibraryDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfLibraryEntity = new EntityInsertionAdapter<LibraryEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `library` (`id`,`title`,`artist`,`album`,`label`,`serviceId`,`container`,`bitrateKbps`,`durationSec`,`filePath`,`sizeBytes`,`artworkUrl`,`addedAt`,`sampleRateHz`,`bitDepth`,`lossless`,`fakeLossless`,`requestedQualityId`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final LibraryEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getTitle());
        statement.bindString(3, entity.getArtist());
        if (entity.getAlbum() == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.getAlbum());
        }
        if (entity.getLabel() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getLabel());
        }
        statement.bindString(6, entity.getServiceId());
        statement.bindString(7, entity.getContainer());
        if (entity.getBitrateKbps() == null) {
          statement.bindNull(8);
        } else {
          statement.bindLong(8, entity.getBitrateKbps());
        }
        statement.bindLong(9, entity.getDurationSec());
        statement.bindString(10, entity.getFilePath());
        statement.bindLong(11, entity.getSizeBytes());
        if (entity.getArtworkUrl() == null) {
          statement.bindNull(12);
        } else {
          statement.bindString(12, entity.getArtworkUrl());
        }
        statement.bindLong(13, entity.getAddedAt());
        if (entity.getSampleRateHz() == null) {
          statement.bindNull(14);
        } else {
          statement.bindLong(14, entity.getSampleRateHz());
        }
        if (entity.getBitDepth() == null) {
          statement.bindNull(15);
        } else {
          statement.bindLong(15, entity.getBitDepth());
        }
        final int _tmp = entity.getLossless() ? 1 : 0;
        statement.bindLong(16, _tmp);
        final int _tmp_1 = entity.getFakeLossless() ? 1 : 0;
        statement.bindLong(17, _tmp_1);
        if (entity.getRequestedQualityId() == null) {
          statement.bindNull(18);
        } else {
          statement.bindString(18, entity.getRequestedQualityId());
        }
      }
    };
  }

  @Override
  public Object upsert(final LibraryEntity row, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfLibraryEntity.insert(row);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<LibraryEntity>> observeAll() {
    final String _sql = "SELECT * FROM library ORDER BY addedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"library"}, new Callable<List<LibraryEntity>>() {
      @Override
      @NonNull
      public List<LibraryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfAlbum = CursorUtil.getColumnIndexOrThrow(_cursor, "album");
          final int _cursorIndexOfLabel = CursorUtil.getColumnIndexOrThrow(_cursor, "label");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfContainer = CursorUtil.getColumnIndexOrThrow(_cursor, "container");
          final int _cursorIndexOfBitrateKbps = CursorUtil.getColumnIndexOrThrow(_cursor, "bitrateKbps");
          final int _cursorIndexOfDurationSec = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSec");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "sizeBytes");
          final int _cursorIndexOfArtworkUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "artworkUrl");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final int _cursorIndexOfSampleRateHz = CursorUtil.getColumnIndexOrThrow(_cursor, "sampleRateHz");
          final int _cursorIndexOfBitDepth = CursorUtil.getColumnIndexOrThrow(_cursor, "bitDepth");
          final int _cursorIndexOfLossless = CursorUtil.getColumnIndexOrThrow(_cursor, "lossless");
          final int _cursorIndexOfFakeLossless = CursorUtil.getColumnIndexOrThrow(_cursor, "fakeLossless");
          final int _cursorIndexOfRequestedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "requestedQualityId");
          final List<LibraryEntity> _result = new ArrayList<LibraryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final LibraryEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpAlbum;
            if (_cursor.isNull(_cursorIndexOfAlbum)) {
              _tmpAlbum = null;
            } else {
              _tmpAlbum = _cursor.getString(_cursorIndexOfAlbum);
            }
            final String _tmpLabel;
            if (_cursor.isNull(_cursorIndexOfLabel)) {
              _tmpLabel = null;
            } else {
              _tmpLabel = _cursor.getString(_cursorIndexOfLabel);
            }
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpContainer;
            _tmpContainer = _cursor.getString(_cursorIndexOfContainer);
            final Integer _tmpBitrateKbps;
            if (_cursor.isNull(_cursorIndexOfBitrateKbps)) {
              _tmpBitrateKbps = null;
            } else {
              _tmpBitrateKbps = _cursor.getInt(_cursorIndexOfBitrateKbps);
            }
            final int _tmpDurationSec;
            _tmpDurationSec = _cursor.getInt(_cursorIndexOfDurationSec);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final long _tmpSizeBytes;
            _tmpSizeBytes = _cursor.getLong(_cursorIndexOfSizeBytes);
            final String _tmpArtworkUrl;
            if (_cursor.isNull(_cursorIndexOfArtworkUrl)) {
              _tmpArtworkUrl = null;
            } else {
              _tmpArtworkUrl = _cursor.getString(_cursorIndexOfArtworkUrl);
            }
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            final Integer _tmpSampleRateHz;
            if (_cursor.isNull(_cursorIndexOfSampleRateHz)) {
              _tmpSampleRateHz = null;
            } else {
              _tmpSampleRateHz = _cursor.getInt(_cursorIndexOfSampleRateHz);
            }
            final Integer _tmpBitDepth;
            if (_cursor.isNull(_cursorIndexOfBitDepth)) {
              _tmpBitDepth = null;
            } else {
              _tmpBitDepth = _cursor.getInt(_cursorIndexOfBitDepth);
            }
            final boolean _tmpLossless;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfLossless);
            _tmpLossless = _tmp != 0;
            final boolean _tmpFakeLossless;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfFakeLossless);
            _tmpFakeLossless = _tmp_1 != 0;
            final String _tmpRequestedQualityId;
            if (_cursor.isNull(_cursorIndexOfRequestedQualityId)) {
              _tmpRequestedQualityId = null;
            } else {
              _tmpRequestedQualityId = _cursor.getString(_cursorIndexOfRequestedQualityId);
            }
            _item = new LibraryEntity(_tmpId,_tmpTitle,_tmpArtist,_tmpAlbum,_tmpLabel,_tmpServiceId,_tmpContainer,_tmpBitrateKbps,_tmpDurationSec,_tmpFilePath,_tmpSizeBytes,_tmpArtworkUrl,_tmpAddedAt,_tmpSampleRateHz,_tmpBitDepth,_tmpLossless,_tmpFakeLossless,_tmpRequestedQualityId);
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
  public Object byIds(final List<String> ids,
      final Continuation<? super List<LibraryEntity>> $completion) {
    final StringBuilder _stringBuilder = StringUtil.newStringBuilder();
    _stringBuilder.append("SELECT * FROM library WHERE id IN (");
    final int _inputSize = ids.size();
    StringUtil.appendPlaceholders(_stringBuilder, _inputSize);
    _stringBuilder.append(")");
    final String _sql = _stringBuilder.toString();
    final int _argCount = 0 + _inputSize;
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, _argCount);
    int _argIndex = 1;
    for (String _item : ids) {
      _statement.bindString(_argIndex, _item);
      _argIndex++;
    }
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<LibraryEntity>>() {
      @Override
      @NonNull
      public List<LibraryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfAlbum = CursorUtil.getColumnIndexOrThrow(_cursor, "album");
          final int _cursorIndexOfLabel = CursorUtil.getColumnIndexOrThrow(_cursor, "label");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfContainer = CursorUtil.getColumnIndexOrThrow(_cursor, "container");
          final int _cursorIndexOfBitrateKbps = CursorUtil.getColumnIndexOrThrow(_cursor, "bitrateKbps");
          final int _cursorIndexOfDurationSec = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSec");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "sizeBytes");
          final int _cursorIndexOfArtworkUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "artworkUrl");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final int _cursorIndexOfSampleRateHz = CursorUtil.getColumnIndexOrThrow(_cursor, "sampleRateHz");
          final int _cursorIndexOfBitDepth = CursorUtil.getColumnIndexOrThrow(_cursor, "bitDepth");
          final int _cursorIndexOfLossless = CursorUtil.getColumnIndexOrThrow(_cursor, "lossless");
          final int _cursorIndexOfFakeLossless = CursorUtil.getColumnIndexOrThrow(_cursor, "fakeLossless");
          final int _cursorIndexOfRequestedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "requestedQualityId");
          final List<LibraryEntity> _result = new ArrayList<LibraryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final LibraryEntity _item_1;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpAlbum;
            if (_cursor.isNull(_cursorIndexOfAlbum)) {
              _tmpAlbum = null;
            } else {
              _tmpAlbum = _cursor.getString(_cursorIndexOfAlbum);
            }
            final String _tmpLabel;
            if (_cursor.isNull(_cursorIndexOfLabel)) {
              _tmpLabel = null;
            } else {
              _tmpLabel = _cursor.getString(_cursorIndexOfLabel);
            }
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpContainer;
            _tmpContainer = _cursor.getString(_cursorIndexOfContainer);
            final Integer _tmpBitrateKbps;
            if (_cursor.isNull(_cursorIndexOfBitrateKbps)) {
              _tmpBitrateKbps = null;
            } else {
              _tmpBitrateKbps = _cursor.getInt(_cursorIndexOfBitrateKbps);
            }
            final int _tmpDurationSec;
            _tmpDurationSec = _cursor.getInt(_cursorIndexOfDurationSec);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final long _tmpSizeBytes;
            _tmpSizeBytes = _cursor.getLong(_cursorIndexOfSizeBytes);
            final String _tmpArtworkUrl;
            if (_cursor.isNull(_cursorIndexOfArtworkUrl)) {
              _tmpArtworkUrl = null;
            } else {
              _tmpArtworkUrl = _cursor.getString(_cursorIndexOfArtworkUrl);
            }
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            final Integer _tmpSampleRateHz;
            if (_cursor.isNull(_cursorIndexOfSampleRateHz)) {
              _tmpSampleRateHz = null;
            } else {
              _tmpSampleRateHz = _cursor.getInt(_cursorIndexOfSampleRateHz);
            }
            final Integer _tmpBitDepth;
            if (_cursor.isNull(_cursorIndexOfBitDepth)) {
              _tmpBitDepth = null;
            } else {
              _tmpBitDepth = _cursor.getInt(_cursorIndexOfBitDepth);
            }
            final boolean _tmpLossless;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfLossless);
            _tmpLossless = _tmp != 0;
            final boolean _tmpFakeLossless;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfFakeLossless);
            _tmpFakeLossless = _tmp_1 != 0;
            final String _tmpRequestedQualityId;
            if (_cursor.isNull(_cursorIndexOfRequestedQualityId)) {
              _tmpRequestedQualityId = null;
            } else {
              _tmpRequestedQualityId = _cursor.getString(_cursorIndexOfRequestedQualityId);
            }
            _item_1 = new LibraryEntity(_tmpId,_tmpTitle,_tmpArtist,_tmpAlbum,_tmpLabel,_tmpServiceId,_tmpContainer,_tmpBitrateKbps,_tmpDurationSec,_tmpFilePath,_tmpSizeBytes,_tmpArtworkUrl,_tmpAddedAt,_tmpSampleRateHz,_tmpBitDepth,_tmpLossless,_tmpFakeLossless,_tmpRequestedQualityId);
            _result.add(_item_1);
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
  public Flow<List<LibraryEntity>> search(final String q) {
    final String _sql = "SELECT * FROM library WHERE title LIKE '%' || ? || '%' OR artist LIKE '%' || ? || '%' ORDER BY addedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindString(_argIndex, q);
    _argIndex = 2;
    _statement.bindString(_argIndex, q);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"library"}, new Callable<List<LibraryEntity>>() {
      @Override
      @NonNull
      public List<LibraryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfArtist = CursorUtil.getColumnIndexOrThrow(_cursor, "artist");
          final int _cursorIndexOfAlbum = CursorUtil.getColumnIndexOrThrow(_cursor, "album");
          final int _cursorIndexOfLabel = CursorUtil.getColumnIndexOrThrow(_cursor, "label");
          final int _cursorIndexOfServiceId = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceId");
          final int _cursorIndexOfContainer = CursorUtil.getColumnIndexOrThrow(_cursor, "container");
          final int _cursorIndexOfBitrateKbps = CursorUtil.getColumnIndexOrThrow(_cursor, "bitrateKbps");
          final int _cursorIndexOfDurationSec = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSec");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "sizeBytes");
          final int _cursorIndexOfArtworkUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "artworkUrl");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final int _cursorIndexOfSampleRateHz = CursorUtil.getColumnIndexOrThrow(_cursor, "sampleRateHz");
          final int _cursorIndexOfBitDepth = CursorUtil.getColumnIndexOrThrow(_cursor, "bitDepth");
          final int _cursorIndexOfLossless = CursorUtil.getColumnIndexOrThrow(_cursor, "lossless");
          final int _cursorIndexOfFakeLossless = CursorUtil.getColumnIndexOrThrow(_cursor, "fakeLossless");
          final int _cursorIndexOfRequestedQualityId = CursorUtil.getColumnIndexOrThrow(_cursor, "requestedQualityId");
          final List<LibraryEntity> _result = new ArrayList<LibraryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final LibraryEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpArtist;
            _tmpArtist = _cursor.getString(_cursorIndexOfArtist);
            final String _tmpAlbum;
            if (_cursor.isNull(_cursorIndexOfAlbum)) {
              _tmpAlbum = null;
            } else {
              _tmpAlbum = _cursor.getString(_cursorIndexOfAlbum);
            }
            final String _tmpLabel;
            if (_cursor.isNull(_cursorIndexOfLabel)) {
              _tmpLabel = null;
            } else {
              _tmpLabel = _cursor.getString(_cursorIndexOfLabel);
            }
            final String _tmpServiceId;
            _tmpServiceId = _cursor.getString(_cursorIndexOfServiceId);
            final String _tmpContainer;
            _tmpContainer = _cursor.getString(_cursorIndexOfContainer);
            final Integer _tmpBitrateKbps;
            if (_cursor.isNull(_cursorIndexOfBitrateKbps)) {
              _tmpBitrateKbps = null;
            } else {
              _tmpBitrateKbps = _cursor.getInt(_cursorIndexOfBitrateKbps);
            }
            final int _tmpDurationSec;
            _tmpDurationSec = _cursor.getInt(_cursorIndexOfDurationSec);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final long _tmpSizeBytes;
            _tmpSizeBytes = _cursor.getLong(_cursorIndexOfSizeBytes);
            final String _tmpArtworkUrl;
            if (_cursor.isNull(_cursorIndexOfArtworkUrl)) {
              _tmpArtworkUrl = null;
            } else {
              _tmpArtworkUrl = _cursor.getString(_cursorIndexOfArtworkUrl);
            }
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            final Integer _tmpSampleRateHz;
            if (_cursor.isNull(_cursorIndexOfSampleRateHz)) {
              _tmpSampleRateHz = null;
            } else {
              _tmpSampleRateHz = _cursor.getInt(_cursorIndexOfSampleRateHz);
            }
            final Integer _tmpBitDepth;
            if (_cursor.isNull(_cursorIndexOfBitDepth)) {
              _tmpBitDepth = null;
            } else {
              _tmpBitDepth = _cursor.getInt(_cursorIndexOfBitDepth);
            }
            final boolean _tmpLossless;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfLossless);
            _tmpLossless = _tmp != 0;
            final boolean _tmpFakeLossless;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfFakeLossless);
            _tmpFakeLossless = _tmp_1 != 0;
            final String _tmpRequestedQualityId;
            if (_cursor.isNull(_cursorIndexOfRequestedQualityId)) {
              _tmpRequestedQualityId = null;
            } else {
              _tmpRequestedQualityId = _cursor.getString(_cursorIndexOfRequestedQualityId);
            }
            _item = new LibraryEntity(_tmpId,_tmpTitle,_tmpArtist,_tmpAlbum,_tmpLabel,_tmpServiceId,_tmpContainer,_tmpBitrateKbps,_tmpDurationSec,_tmpFilePath,_tmpSizeBytes,_tmpArtworkUrl,_tmpAddedAt,_tmpSampleRateHz,_tmpBitDepth,_tmpLossless,_tmpFakeLossless,_tmpRequestedQualityId);
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

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
