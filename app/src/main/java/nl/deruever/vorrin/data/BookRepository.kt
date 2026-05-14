package nl.deruever.vorrin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.deruever.vorrin.data.db.BookDao
import nl.deruever.vorrin.data.db.BookEntity
import nl.deruever.vorrin.data.db.ChapterEntity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao
) {
    private val syncMutex = Mutex()

    fun getBooksFlow(): Flow<List<Audiobook>> {
        return bookDao.getAllBooksWithChapters().map { list ->
            list.map { it.book.toAudiobook(it.chapters.sortedBy { c -> c.index }) }
        }
    }

    suspend fun prewarmAllBooks() {
        bookDao.getAllBookUris().forEach { uriString ->
            prewarmCache(Uri.parse(uriString))
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
            
            // Downscale cover art during indexing to save space and avoid Bluetooth timeouts
            val rawCoverArt = retriever.embeddedPicture
            val coverArt = rawCoverArt?.let { data ->
                try {
                    if (data.size > 100_000) {
                        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                        if (bitmap != null && (bitmap.width > 600 || bitmap.height > 600)) {
                            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val width = if (ratio > 1) 600 else (600 * ratio).toInt()
                            val height = if (ratio > 1) (600 / ratio).toInt() else 600
                            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                            val stream = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                            stream.toByteArray()
                        } else data
                    } else data
                } catch (e: Exception) {
                    data
                }
            }

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

            // Pre-warm the ExoPlayer cache with the file's head and tail bytes so
            // the moov atom (MP4 container index) is cached for instant future opens.
            prewarmCache(uri)

        } catch (e: Exception) {
            android.util.Log.e("Vorrin", "Failed to index book: $uri", e)
        } finally {
            retriever.release()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun prewarmCache(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val fileSize = context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize }
                ?.takeIf { it > 0 } ?: return@withContext

            val cache = BookCache.get(context)
            val upstreamFactory = DefaultDataSource.Factory(context)
            val buf = ByteArray(64 * 1024)

            val headSize = minOf(512 * 1024L, fileSize)
            val tailOffset = maxOf(0L, fileSize - 4 * 1024 * 1024L)
            val tailSize = fileSize - tailOffset

            // Read head (ftyp box) and tail (where moov usually lives).
            // CacheDataSource writes each byte it reads from upstream into the cache,
            // so simply draining the read loop is enough to pre-populate it.
            for (spec in listOf(
                DataSpec(uri, 0L, headSize),
                DataSpec(uri, tailOffset, tailSize)
            )) {
                val dataSource = CacheDataSource(
                    cache,
                    upstreamFactory.createDataSource(),
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                )
                try {
                    dataSource.open(spec)
                    while (dataSource.read(buf, 0, buf.size) != -1) { /* drain */ }
                } finally {
                    dataSource.close()
                }
            }
        } catch (e: Exception) {
            // Pre-warming is best-effort; a failure here doesn't affect playback
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
        status = status,
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