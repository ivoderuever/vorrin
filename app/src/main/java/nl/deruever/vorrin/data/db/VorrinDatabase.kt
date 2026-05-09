package nl.deruever.vorrin.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 1,
    exportSchema = false
)
abstract class VorrinDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: VorrinDatabase? = null

        fun getInstance(context: Context): VorrinDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    VorrinDatabase::class.java,
                    "vorrin_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}