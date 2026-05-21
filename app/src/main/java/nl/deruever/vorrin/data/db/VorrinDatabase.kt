// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
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
    version = 3,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN dateAdded INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
            }
        }

        fun getInstance(context: Context): VorrinDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    VorrinDatabase::class.java,
                    "vorrin_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
        }
    }
}