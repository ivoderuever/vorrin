package nl.deruever.vorrin.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import nl.deruever.vorrin.data.BookStatus

@Entity(
    tableName = "books",
    indices = [Index(value = ["uri"], unique = true)]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val uri: String,
    val title: String,
    val author: String,
    val duration: Long,
    val coverArt: ByteArray?,
    val lastPosition: Long = 0L,
    val totalListened: Long = 0L,
    val status: BookStatus = BookStatus.UNREAD,
    val dateAdded: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chapters")
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val bookId: Int,
    val index: Int,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)