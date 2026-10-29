package com.myvault.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SpaceEntity::class, TagEntity::class, EntryEntity::class, EntryTagCrossRef::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun tagDao(): TagDao
    abstract fun entryDao(): EntryDao

    companion object {
        // Additive migration: never collapse legacy duplicates or alter existing tag links.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tags ADD COLUMN scope TEXT NOT NULL DEFAULT 'global'")
                db.execSQL("ALTER TABLE tags ADD COLUMN ownerSpaceId TEXT")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        /** All data lives only in this on-device SQLite database — nothing is sent anywhere. */
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "myvault.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
