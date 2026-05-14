package nl.deruever.vorrin.data.db

import androidx.room.TypeConverter
import nl.deruever.vorrin.data.BookStatus

class Converters {
    @TypeConverter
    fun fromBookStatus(status: BookStatus): String = status.name

    @TypeConverter
    fun toBookStatus(value: String): BookStatus = BookStatus.valueOf(value)
}
