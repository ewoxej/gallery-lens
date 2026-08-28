package dev.ewoxej.gallerylens.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun toStatus(value: String): PhotoStatus = PhotoStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(status: PhotoStatus): String = status.name
}

@Database(
    entities = [PhotoEntity::class, PhotoFts::class],
    // v3: FTS tokenizer gained remove_diacritics=1 (diacritic-insensitive search).
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : androidx.room.RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vision-search.db",
                    // Schema grows during early dev; a wipe just triggers a
                    // reindex (the source of truth is the gallery, not the DB).
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
