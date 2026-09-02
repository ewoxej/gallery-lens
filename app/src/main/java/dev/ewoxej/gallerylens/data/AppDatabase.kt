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
    version = 4,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vision-search.db",
                ).addMigrations(MIGRATION_3_4)
                    // Backstop for older/dev schema jumps without a written migration.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
