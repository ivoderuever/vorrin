package nl.deruever.vorrin.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val _books = MutableStateFlow<List<Audiobook>>(emptyList())
    val books: StateFlow<List<Audiobook>> = _books.asStateFlow()

    private val _selectedBookUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedBookUris: StateFlow<Set<String>> = _selectedBookUris.asStateFlow()

    private var currentActiveUri: String? = null

    init {
        viewModelScope.launch {
            val activeUri = preferencesRepository.activeBookUri.first()
            bookRepository.prewarmAllBooks(priorityUri = activeUri)
        }

        viewModelScope.launch {
            bookRepository.getBooksFlow().collect { books ->
                _books.value = books
                if (currentActiveUri != null) {
                    _activeBook.value = books.find { it.uri == currentActiveUri }
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.folderUri
                .collect { uri ->
                    _isInitializing.value = false
                    if (_folderUri.value != uri) {
                        _folderUri.value = uri
                        if (uri != null) {
                            syncBooks(Uri.parse(uri))
                        }
                    }
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

    fun toggleBookSelection(book: Audiobook) {
        _selectedBookUris.value = _selectedBookUris.value.toMutableSet().apply {
            if (!add(book.uri)) remove(book.uri)
        }
    }

    fun clearSelection() {
        _selectedBookUris.value = emptySet()
    }

    fun markSelectedAsFinished() {
        viewModelScope.launch {
            bookRepository.markBooksAsFinished(_selectedBookUris.value.toList())
            clearSelection()
        }
    }

    fun markSelectedAsUnread() {
        viewModelScope.launch {
            bookRepository.markBooksAsUnread(_selectedBookUris.value.toList())
            clearSelection()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val uri = _folderUri.value ?: return@launch
            _isLoading.value = true
            val minDelay = launch { delay(1_500) }
            bookRepository.syncFolder(Uri.parse(uri))
            minDelay.join()
            _isLoading.value = false
        }
    }
}