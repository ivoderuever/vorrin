package nl.deruever.vorrin.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.BookRepository
import nl.deruever.vorrin.data.PreferencesRepository

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = PreferencesRepository(application)
    private val bookRepository = BookRepository(application)

    private val _books = MutableStateFlow<List<Audiobook>>(emptyList())
    val books: StateFlow<List<Audiobook>> = _books.asStateFlow()

    private val _folderUri = MutableStateFlow<String?>(null)
    val folderUri: StateFlow<String?> = _folderUri.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.folderUri.collect { uri ->
                _folderUri.value = uri
                if (uri != null) {
                    loadBooks(Uri.parse(uri))
                }
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            preferencesRepository.saveFolderUri(uri.toString())
            loadBooks(uri)
        }
    }

//    private fun loadBooks(uri: Uri) {
//        viewModelScope.launch {
//            _isLoading.value = true
//            _books.value = bookRepository.getBooksFromFolder(uri)
//            _isLoading.value = false
//        }
//    }

    private fun loadBooks(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            val found = bookRepository.getBooksFromFolder(uri)
            android.util.Log.d("Vorrin", "Found ${found.size} books in folder")
            found.forEach { android.util.Log.d("Vorrin", "Book: ${it.title}") }
            _books.value = found
            _isLoading.value = false
        }
    }
}