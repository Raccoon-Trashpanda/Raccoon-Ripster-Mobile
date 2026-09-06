package net.ripster.mobile.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadEntity::class, LibraryEntity::class, PlayEntity::class,
        WatchEntity::class, FavoriteEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class RipsterDb : RoomDatabase() {

    abstract fun downloads(): DownloadDao
    abstract fun library(): LibraryDao
    abstract fun plays(): PlayDao
    abstract fun watch(): WatchDao
    abstract fun favorites(): FavoriteDao

    companion object {
        /**
         * v7 → v8: у задачи появились `groupId`/`groupTitle` (альбом одной
         * строкой в очереди).
         *
         * Написано руками, а не через fallbackToDestructiveMigration: тот
         * сносит ВСЮ базу, включая фонотеку и историю. Потерять чужие
         * скачанные треки ради двух новых колонок — не обновление, а авария.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN groupId TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN groupTitle TEXT")
            }
        }

        fun build(context: Context): RipsterDb =
            Room.databaseBuilder(context, RipsterDb::class.java, "ripster.db")
                .addMigrations(MIGRATION_7_8)
                // Запасной ход для схем СТАРШЕ v7, для которых миграций мы не
                // писали: там либо пусто, либо давно неактуально.
                .fallbackToDestructiveMigration()
                .build()
    }
}
