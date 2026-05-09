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
import nl.deruever.vorrin.data.db.VorrinDatabase

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = PreferencesRepository(application)
    private val bookRepository = BookRepository(
        context = application,
        bookDao = VorrinDatabase.getInstance(application).bookDao()
    )

    private val _folderUri = MutableStateFlow<String?>(null)
    val folderUri: StateFlow<String?> = _folderUri.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _activeBook = MutableStateFlow<Audiobook?>(null)
    val activeBook: StateFlow<Audiobook?> = _activeBook.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    // Books come directly from the database as a Flow
    private val _books = MutableStateFlow<List<Audiobook>>(emptyList())
    val books: StateFlow<List<Audiobook>> = _books.asStateFlow()

    private var currentActiveUri: String? = null

    init {
        viewModelScope.launch {
            // Observe books from database
            bookRepository.getBooksFlow().collect { books ->
                _books.value = books
                // Update active book if its data changed in DB
                if (currentActiveUri != null) {
                    _activeBook.value = books.find { it.uri == currentActiveUri }
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.folderUri
                .collect { uri ->
                    if (_folderUri.value != uri) {
                        _folderUri.value = uri
                        if (uri != null) {
                            syncBooks(Uri.parse(uri))
                        }
                    }
                    _isInitializing.value = false
                }
        }

        viewModelScope.launch {
            preferencesRepository.activeBookUri.collect { activeUri ->
                currentActiveUri = activeUri
                if (activeUri != null) {
                    _activeBook.value = _books.value.find { it.uri == activeUri }
                }
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            preferencesRepository.saveFolderUri(uri.toString())
        }
    }

    private suspend fun syncBooks(uri: Uri) {
        _isLoading.value = true
        bookRepository.syncFolder(uri)
        _isLoading.value = false
    }

    fun setActiveBook(book: Audiobook) {
        _activeBook.value = book
        viewModelScope.launch {
            preferencesRepository.saveActiveBookUri(book.uri)
        }
    }
}