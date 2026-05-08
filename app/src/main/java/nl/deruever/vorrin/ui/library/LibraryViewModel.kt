package nl.deruever.vorrin.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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

    private val _activeBook = MutableStateFlow<Audiobook?>(null)
    val activeBook: StateFlow<Audiobook?> = _activeBook.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.folderUri.collect { uri ->
                _folderUri.value = uri
                if (uri != null) {
                    loadBooks(Uri.parse(uri))
                }
                _isInitializing.value = false
            }
        }
        viewModelScope.launch {
            preferencesRepository.activeBookUri.collect { activeUri ->
                if (activeUri != null) {
                    _activeBook.value = _books.value.find { it.uri == activeUri }
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

    private fun loadBooks(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _books.value = bookRepository.getBooksFromFolder(uri)
            _isLoading.value = false
        }
    }

    fun setActiveBook(book: Audiobook) {
        _activeBook.value = book
        viewModelScope.launch {
            preferencesRepository.saveActiveBookUri(book.uri)
        }
    }
}