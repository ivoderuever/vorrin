package nl.deruever.vorrin.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

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
}