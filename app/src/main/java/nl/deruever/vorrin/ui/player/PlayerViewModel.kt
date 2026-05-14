package nl.deruever.vorrin.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.BookStatus
import nl.deruever.vorrin.data.db.VorrinDatabase
import nl.deruever.vorrin.service.AudiobookService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val bookDao = VorrinDatabase.getInstance(application).bookDao()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var currentBookUri: String? = null
    private var positionUpdateJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    fun connect(book: Audiobook) {
        if (book.uri.isBlank() || book.uri == currentBookUri) return
        currentBookUri = book.uri
        positionUpdateJob?.cancel()

        if (book.status == BookStatus.UNREAD) {
            viewModelScope.launch { bookDao.updateStatus(book.uri, BookStatus.IN_PROGRESS) }
        }

        _isReady.value = false
        _isPlaying.value = false
        _currentPositionMs.value = book.lastPosition
        _duration.value = book.duration

        val context = getApplication<Application>()

        if (controller != null) {
            // Already connected — just load the new book
            loadBookIntoService(context, book)
            return
        }

        val sessionToken = SessionToken(
            context,
            ComponentName(context, AudiobookService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupListener()
            loadBookIntoService(context, book)
        }, MoreExecutors.directExecutor())
    }

    private fun loadBookIntoService(context: Context, book: Audiobook) {
        val ctrl = controller ?: return
        AudiobookService.pendingBook = book

        // If ExoPlayer is already loaded and buffered for this book, skip re-preparing.
        // This is the common case when the service survived a task removal.
        val loadedUri = ctrl.currentMediaItem?.localConfiguration?.uri?.toString()
        val state = ctrl.playbackState
        val alreadyLoaded = loadedUri == book.uri && state != Player.STATE_IDLE

        if (alreadyLoaded) {
            _isReady.value = state == Player.STATE_READY
            _duration.value = ctrl.duration.takeIf { it > 0 } ?: book.duration
            _currentPositionMs.value = ctrl.currentPosition.takeIf { it > 0 } ?: book.lastPosition
            _isPlaying.value = ctrl.isPlaying
            if (ctrl.isPlaying) startPositionUpdates()
            return
        }

        context.startService(Intent(context, AudiobookService::class.java))

        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setArtworkData(
                book.coverArt,
                androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER
            )
            .build()

        ctrl.setMediaItem(
            androidx.media3.common.MediaItem.Builder()
                .setUri(android.net.Uri.parse(book.uri))
                .setMediaMetadata(metadata)
                .build()
        )
        ctrl.prepare()
        ctrl.playWhenReady = false
    }

    private fun setupListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startPositionUpdates()
                else {
                    _currentPositionMs.value = controller?.currentPosition ?: 0L
                    savePosition()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    _isReady.value = true
                    _duration.value = controller?.duration ?: 0L
                    _currentPositionMs.value = AudiobookService.pendingBook?.lastPosition ?: 0L
                }
                if (state == Player.STATE_ENDED) {
                    val uri = currentBookUri ?: return
                    viewModelScope.launch { bookDao.updateStatus(uri, BookStatus.FINISHED) }
                }
            }
        })

        // Sync current state immediately — the service may already be in STATE_READY
        // if it survived a task removal (no re-prepare needed).
        if (controller?.playbackState == Player.STATE_READY) {
            _isReady.value = true
            _duration.value = controller?.duration ?: 0L
        }
        _isPlaying.value = controller?.isPlaying == true
        if (controller?.isPlaying == true) startPositionUpdates()
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            var ticks = 0
            while (true) {
                _currentPositionMs.value = controller?.currentPosition ?: 0L
                delay(500)
                if (++ticks >= 60) {
                    savePosition()
                    ticks = 0
                }
            }
        }
    }

    fun playPause() {
        if (controller?.isPlaying == true) controller?.pause()
        else controller?.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        savePosition(positionMs)
    }

    fun skipForward(seconds: Int) {
        val target = (_currentPositionMs.value + seconds * 1000L).coerceAtMost(_duration.value)
        seekTo(target)
    }

    fun skipBack(seconds: Int) {
        val target = (_currentPositionMs.value - seconds * 1000L).coerceAtLeast(0L)
        seekTo(target)
    }

    fun resetProgress(book: Audiobook) {
        viewModelScope.launch {
            bookDao.updatePosition(book.uri, 0L)
            bookDao.updateStatus(book.uri, BookStatus.IN_PROGRESS)
        }
        if (book.uri == currentBookUri) {
            controller?.seekTo(0L)
            _currentPositionMs.value = 0L
        } else {
            currentBookUri = null
        }
    }

    fun savePosition(position: Long? = null) {
        val uri = currentBookUri ?: return
        val pos = position ?: controller?.currentPosition ?: return
        viewModelScope.launch {
            bookDao.updatePosition(uri, pos)
        }
    }

    override fun onCleared() {
        savePosition()
        positionUpdateJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}