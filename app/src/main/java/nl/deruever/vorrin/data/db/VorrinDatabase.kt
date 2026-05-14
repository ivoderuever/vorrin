package nl.deruever.vorrin.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class VorrinDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: VorrinDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN status TEXT NOT NULL DEFAULT 'UNREAD'")
                db.execSQL("UPDATE books SET status = 'FINISHED' WHERE duration > 0 AND lastPosition >= duration")
                db.execSQL("UPDATE books SET status = 'IN_PROGRESS' WHERE lastPosition > 0 AND (duration = 0 OR lastPosition < duration)")
            }
        }

        fun getInstance(context: Context): VorrinDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    VorrinDatabase::class.java,
                    "vorrin_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}