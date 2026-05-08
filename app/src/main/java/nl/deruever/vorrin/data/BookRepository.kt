package nl.deruever.vorrin.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class BookRepository(private val context: Context) {

    fun getBooksFromFolder(folderUri: Uri): List<Audiobook> {
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()

        return folder.listFiles()
            .filter { it.isFile && it.name?.lowercase()?.endsWith(".m4b") == true }
            .mapNotNull { file ->
                val uri = file.uri
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?: file.name?.removeSuffix(".m4b") ?: return@mapNotNull null
                    val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: "Unknown"
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val coverArt = retriever.embeddedPicture

                    Audiobook(
                        id = uri.toString(),
                        title = title,
                        author = author,
                        uri = uri.toString(),
                        duration = durationMs,
                        coverArt = coverArt
                    )
                } catch (e: Exception) {
                    android.util.Log.e("Vorrin", "Failed to read metadata for ${file.name}", e)
                    null
                } finally {
                    retriever.release()
                }
            }
    }
}