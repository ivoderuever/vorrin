package nl.deruever.vorrin.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class BookRepository(private val context: Context) {

    fun getBooksFromFolder(folderUri: Uri): List<Audiobook> {
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        return folder.listFiles()
            .filter { it.isFile && it.name?.endsWith(".m4b", ignoreCase = true) == true }
            .mapNotNull { file ->
                val uri = file.uri.toString()
                val name = file.name?.removeSuffix(".m4b") ?: return@mapNotNull null
                Audiobook(
                    id = uri,
                    title = name,
                    author = "Unknown",
                    uri = uri,
                )
            }
    }
}