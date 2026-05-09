package nl.deruever.vorrin.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.deruever.vorrin.data.db.BookDao
import nl.deruever.vorrin.data.db.BookEntity
import nl.deruever.vorrin.data.db.ChapterEntity
import java.nio.ByteBuffer

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao
) {
    private val syncMutex = Mutex()

    // Observe all books from database as a Flow
    fun getBooksFlow(): Flow<List<Audiobook>> {
        return bookDao.getAllBooks().map { entities ->
            entities.map { entity ->
                // Note: This is still doing N+1 queries. 
                // In a future update, consider using Room @Relation for better performance.
                val chapters = bookDao.getChaptersForBook(entity.id)
                entity.toAudiobook(chapters)
            }
        }
    }

    // Scan folder, index new books, remove deleted ones
    suspend fun syncFolder(folderUri: Uri) = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext

            val filesOnDisk = folder.listFiles()
                .filter { it.isFile && it.name?.lowercase()?.endsWith(".m4b") == true }
                .associate { it.uri.toString() to it }

            val urisInDb = bookDao.getAllBookUris().toSet()

            // Remove deleted books
            urisInDb.filterNot { filesOnDisk.containsKey(it) }.forEach { uri ->
                val book = bookDao.getBookByUri(uri)
                if (book != null) {
                    bookDao.deleteChaptersForBook(book.id)
                    bookDao.deleteBook(book.id)
                }
            }

            // Index new books
            filesOnDisk.filterNot { urisInDb.contains(it.key) }.forEach { (uriString, _) ->
                indexBook(Uri.parse(uriString))
            }
        }
    }

    private suspend fun indexBook(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.removeSuffix(".m4b") ?: "Unknown"
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: "Unknown"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val coverArt = retriever.embeddedPicture

            val entity = BookEntity(
                uri = uri.toString(),
                title = title,
                author = author,
                duration = duration,
                coverArt = coverArt,
            )

            bookDao.insertBook(entity)

            // Get the inserted book to get its id
            val inserted = bookDao.getBookByUri(uri.toString()) ?: return

            // Extract and store chapters
            val chapters = extractChapters(uri, inserted.id, duration)
            if (chapters.isNotEmpty()) {
                bookDao.insertChapters(chapters)
            }

        } catch (e: Exception) {
            android.util.Log.e("Vorrin", "Failed to index book: $uri", e)
        } finally {
            retriever.release()
        }
    }

    private suspend fun extractChapters(
        uri: Uri,
        bookId: Int,
        totalDuration: Long
    ): List<ChapterEntity> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            val chapterTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val mime = extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME) ?: ""
                mime == "text/3gpp-tt" || mime == "text/plain" ||
                        mime == "application/x-quicktime-text"
            } ?: return@withContext emptyList()

            extractor.selectTrack(chapterTrackIndex)

            val rawChapters = mutableListOf<Pair<Long, String>>()
            val buffer = ByteBuffer.allocate(4096)

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val startMs = extractor.sampleTime / 1000L
                val bytes = ByteArray(size)
                buffer.rewind()
                buffer.get(bytes, 0, size)
                val title = parseQtTextSample(bytes)

                if (title.isNotBlank()) {
                    rawChapters.add(startMs to title)
                }
                extractor.advance()
            }

            rawChapters.mapIndexed { index, (startMs, title) ->
                ChapterEntity(
                    bookId = bookId,
                    index = index,
                    title = title,
                    startTimeMs = startMs,
                    endTimeMs = rawChapters.getOrNull(index + 1)?.first ?: totalDuration
                )
            }

        } catch (e: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun parseQtTextSample(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val len = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (len <= 0 || bytes.size < 2 + len) return ""
        return String(bytes, 2, len, Charsets.UTF_8)
    }
}

// Extension functions to convert between DB entities and domain models
fun BookEntity.toAudiobook(chapters: List<ChapterEntity>): Audiobook {
    return Audiobook(
        id = id.toString(),
        uri = uri,
        title = title,
        author = author,
        duration = duration,
        coverArt = coverArt,
        lastPosition = lastPosition,
        totalListened = totalListened,
        chapters = chapters.map { it.toChapter() }
    )
}

fun ChapterEntity.toChapter(): Chapter {
    return Chapter(
        index = index,
        title = title,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs
    )
}