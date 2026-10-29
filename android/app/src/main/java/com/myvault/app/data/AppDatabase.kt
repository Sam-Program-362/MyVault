package com.myvault.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SpaceEntity::class, TagEntity::class, EntryEntity::class, EntryTagCrossRef::class],
    version = 3,
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

        // Existing duplicate names remain untouched; triggers reject only new conflicts.
        fun installScopeGuards(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tag_scope_insert BEFORE INSERT ON tags BEGIN
                SELECT CASE WHEN trim(NEW.name) = '' OR NOT ((NEW.scope = 'global' AND NEW.ownerSpaceId IS NULL) OR (NEW.scope = 'local' AND NEW.ownerSpaceId IS NOT NULL AND EXISTS (SELECT 1 FROM spaces WHERE id = NEW.ownerSpaceId))) THEN RAISE(ABORT, 'Invalid tag scope or owner') END;
                SELECT CASE WHEN EXISTS (SELECT 1 FROM tags t WHERE lower(t.name) = lower(NEW.name) AND t.scope = NEW.scope AND t.ownerSpaceId IS NEW.ownerSpaceId AND t.id != NEW.id) THEN RAISE(ABORT, 'Tag name already exists in this scope') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tag_scope_update BEFORE UPDATE ON tags BEGIN
                SELECT CASE WHEN trim(NEW.name) = '' OR NOT ((NEW.scope = 'global' AND NEW.ownerSpaceId IS NULL) OR (NEW.scope = 'local' AND NEW.ownerSpaceId IS NOT NULL AND EXISTS (SELECT 1 FROM spaces WHERE id = NEW.ownerSpaceId))) THEN RAISE(ABORT, 'Invalid tag scope or owner') END;
                SELECT CASE WHEN (lower(OLD.name) != lower(NEW.name) OR OLD.scope != NEW.scope OR OLD.ownerSpaceId IS NOT NEW.ownerSpaceId) AND EXISTS (SELECT 1 FROM tags t WHERE lower(t.name) = lower(NEW.name) AND t.scope = NEW.scope AND t.ownerSpaceId IS NEW.ownerSpaceId AND t.id != NEW.id) THEN RAISE(ABORT, 'Tag name already exists in this scope') END;
                SELECT CASE WHEN NEW.scope = 'local' AND EXISTS (SELECT 1 FROM entry_tags et JOIN entries e ON e.id = et.entryId WHERE et.tagId = NEW.id AND e.spaceId != NEW.ownerSpaceId) THEN RAISE(ABORT, 'Tag is attached in another Space') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tag_link_insert BEFORE INSERT ON entry_tags BEGIN
                SELECT CASE WHEN EXISTS (SELECT 1 FROM tags t JOIN entries e ON e.id = NEW.entryId WHERE t.id = NEW.tagId AND t.scope = 'local' AND t.ownerSpaceId != e.spaceId) THEN RAISE(ABORT, 'Tag belongs to another Space') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS entry_space_update BEFORE UPDATE OF spaceId ON entries BEGIN
                SELECT CASE WHEN EXISTS (SELECT 1 FROM entry_tags et JOIN tags t ON t.id = et.tagId WHERE et.entryId = NEW.id AND t.scope = 'local' AND t.ownerSpaceId != NEW.spaceId) THEN RAISE(ABORT, 'Entry has a local tag from another Space') END;
            END""")
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = installScopeGuards(db)
        }
        val SCOPE_GUARD_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = installScopeGuards(db)
        }

        @Volatile private var instance: AppDatabase? = null

        /** All data lives only in this on-device SQLite database — nothing is sent anywhere. */
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "myvault.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).addCallback(SCOPE_GUARD_CALLBACK).build().also { instance = it }
            }
    }
}
