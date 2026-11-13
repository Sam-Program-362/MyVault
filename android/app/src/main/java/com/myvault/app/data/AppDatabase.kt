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
                // Install the guard at the same upgrade boundary as scope metadata; v1-to-v3
                // and any intermediate v1-to-v2 upgrade must never expose writable scope rows.
                installScopeGuards(db)
            }
        }

        // Fail closed on identity mutations: SQLite lower() is ASCII-only and
        // cannot enforce the agreed Unicode 15.1 key. Preserve legacy rows and
        // allow metadata-only edits until a shared DB-verifiable implementation exists.
        fun installScopeGuards(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tag_scope_insert BEFORE INSERT ON tags BEGIN
                SELECT RAISE(ABORT, 'Tag creation is paused until Unicode 15.1 uniqueness is enforced on every platform');
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tag_scope_update BEFORE UPDATE ON tags
                WHEN OLD.name IS NOT NEW.name OR OLD.scope IS NOT NEW.scope OR OLD.ownerSpaceId IS NOT NEW.ownerSpaceId BEGIN
                SELECT RAISE(ABORT, 'Tag name and scope changes are paused until Unicode 15.1 uniqueness is enforced on every platform');
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tag_link_insert BEFORE INSERT ON entry_tags BEGIN
                SELECT CASE WHEN EXISTS (SELECT 1 FROM tags t JOIN entries e ON e.id = NEW.entryId WHERE t.id = NEW.tagId AND t.scope = 'local' AND t.ownerSpaceId IS NOT e.spaceId) THEN RAISE(ABORT, 'Tag belongs to another Space') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS tag_link_update BEFORE UPDATE ON entry_tags BEGIN
                SELECT CASE WHEN EXISTS (SELECT 1 FROM tags t JOIN entries e ON e.id = NEW.entryId WHERE t.id = NEW.tagId AND t.scope = 'local' AND t.ownerSpaceId IS NOT e.spaceId) THEN RAISE(ABORT, 'Tag belongs to another Space') END;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS space_delete_local_tags BEFORE DELETE ON spaces BEGIN
                DELETE FROM tags WHERE scope = 'local' AND ownerSpaceId = OLD.id;
            END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS entry_space_update BEFORE UPDATE OF spaceId ON entries BEGIN
                SELECT CASE WHEN EXISTS (SELECT 1 FROM entry_tags et JOIN tags t ON t.id = et.tagId WHERE et.entryId = NEW.id AND t.scope = 'local' AND t.ownerSpaceId IS NOT NEW.spaceId) THEN RAISE(ABORT, 'Entry has a local tag from another Space') END;
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
