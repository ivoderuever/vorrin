package nl.deruever.vorrin.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import nl.deruever.vorrin.data.BookStatus

data class BookWithChapters(
    @Embedded val book: BookEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId"
    )
    val chapters: List<ChapterEntity>
)

@Dao
interface BookDao {

    @Transaction
    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllBooksWithChapters(): Flow<List<BookWithChapters>>

    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getChaptersForBook(bookId: Int): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("SELECT uri FROM books")
    suspend fun getAllBookUris(): List<String>

    @Query("UPDATE books SET lastPosition = :position WHERE id = :id")
    suspend fun updatePosition(id: Int, position: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Int)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: Int)

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun getBookByUri(uri: String): BookEntity?

    @Query("UPDATE books SET lastPosition = :position WHERE uri = :uri")
    suspend fun updatePosition(uri: String, position: Long)

    @Query("UPDATE books SET status = :status WHERE uri = :uri")
    suspend fun updateStatus(uri: String, status: BookStatus)
}