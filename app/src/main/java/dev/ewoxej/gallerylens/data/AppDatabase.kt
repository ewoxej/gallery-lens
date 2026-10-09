package dev.ewoxej.gallerylens.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun toStatus(value: String): PhotoStatus = PhotoStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(status: PhotoStatus): String = status.name
}

@Database(
    entities = [PhotoEntity::class, PhotoFts::class],
    // v3: FTS tokenizer gained remove_diacritics=1 (diacritic-insensitive search).
    // v4: per-photo `included` flag for the album filter.
    // v5: dropped the Claude cloud pipeline — retire its CLOUD_* statuses.
    // v6: per-photo `ignored` flag; purge OCR.space's "no text" placeholder rows.
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : androidx.room.RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // Additive column: preserve existing OCR/cloud results (a wipe would force
        // a full — and, in cloud mode, paid — reindex).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN included INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Cloud pipeline removed: send its in-flight rows back to the normal queue
        // so PhotoStatus.valueOf never meets a now-deleted CLOUD_* name.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE photos SET status = 'PENDING' WHERE status IN ('CLOUD_PENDING','CLOUD_SUBMITTED')")
            }
        }

        // Add the per-photo ignore flag, and fix rows that stored OCR.space's
        // "*[No text detected]*" placeholder as if it were real text.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN ignored INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "DELETE FROM photo_fts WHERE rowid IN " +
                        "(SELECT id FROM photos WHERE ocrText = '*[No text detected]*')",
                )
                db.execSQL(
                    "UPDATE photos SET status = 'NO_TEXT', ocrText = NULL, ocrWidth = NULL, " +
                        "ocrHeight = NULL, blocksJson = NULL WHERE ocrText = '*[No text detected]*'",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vision-search.db",
                ).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // Backstop for older/dev schema jumps without a written migration.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
