package net.ripster.mobile.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadEntity::class, LibraryEntity::class, PlayEntity::class,
        WatchEntity::class, FavoriteEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class RipsterDb : RoomDatabase() {

    abstract fun downloads(): DownloadDao
    abstract fun library(): LibraryDao
    abstract fun plays(): PlayDao
    abstract fun watch(): WatchDao
    abstract fun favorites(): FavoriteDao

    companion object {
        fun build(context: Context): RipsterDb =
            Room.databaseBuilder(context, RipsterDb::class.java, "ripster.db")
                // Пока схема одна (v1); миграции появятся, когда появится v2.
                .fallbackToDestructiveMigration()
                .build()
    }
}
