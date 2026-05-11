package nl.deruever.vorrin.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.db.VorrinDatabase
import nl.deruever.vorrin.service.AudiobookService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val bookDao = VorrinDatabase.getInstance(application).bookDao()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerListener: Player.Listener? = null
    private var currentBookUri: String? = null
    private var currentBookDuration: Long = 0L
    private var currentBook: Audiobook? = null
    private var initialSeekPerformed = false
    private var positionUpdateJob: kotlinx.coroutines.Job? = null

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
        currentBookDuration = book.duration
        currentBook = book
        initialSeekPerformed = false
        stopPositionUpdates()

        // Immediately show saved state from DB so the UI isn't blank while buffering.
        _isPlaying.value = false
        _isReady.value = false
        _currentPositionMs.value = book.lastPosition
        _duration.value = book.duration

        if (controller != null) {
            // Reuse the existing controller — avoids the ~10s service unbind/rebind cycle.
            loadBook(book)
        } else {
            // Release any in-progress connection before starting a fresh one.
            controllerFuture?.let { MediaController.releaseFuture(it) }
            val context = getApplication<Application>()
            val sessionToken = SessionToken(
                context,
                ComponentName(context, AudiobookService::class.java)
            )
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener({
                controller = controllerFuture?.get()
                loadBook(book)
            }, MoreExecutors.directExecutor())
        }
    }

    private fun loadBook(book: Audiobook) {
        currentBook = book
        playerListener?.let { controller?.removeListener(it) }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    _currentPositionMs.value = absolutePosition()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Guard against STATE_READY firing for the previous book while the new
                // book's setMediaItems + prepare commands are still in flight.
                val currentId = controller?.currentMediaItem?.mediaId ?: return
                val belongsToThisBook = if (book.chapters.isNotEmpty())
                    currentId.startsWith(book.uri + "#")
                else
                    currentId == book.uri
                if (!belongsToThisBook) return

                if (state == Player.STATE_READY) {
                    _isReady.value = true
                    // _duration always reflects the full book, not the current chapter.
                    _duration.value = currentBookDuration

                    if (!initialSeekPerformed && book.lastPosition > 0) {
                        initialSeekPerformed = true
                        seekTo(book.lastPosition)
                    }
                }
            }
        }
        playerListener = listener
        controller?.addListener(listener)

        val bookUri = Uri.parse(book.uri)
        if (book.chapters.isNotEmpty()) {
            val lastIndex = book.chapters.lastIndex
            val items = book.chapters.mapIndexed { i, chapter ->
                MediaItem.Builder()
                    .setUri(bookUri)
                    // Unique ID per chapter so the guard above can identify the book.
                    .setMediaId("${book.uri}#${chapter.index}")
                    .setClippingConfiguration(
                        ClippingConfiguration.Builder()
                            .setStartPositionMs(chapter.startTimeMs)
                            // Use TIME_END_OF_SOURCE for the last chapter so a rounding
                            // mismatch between metadata duration and actual file length
                            // doesn't cut the final second.
                            .setEndPositionMs(
                                if (i == lastIndex) C.TIME_END_OF_SOURCE else chapter.endTimeMs
                            )
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(chapter.title)
                            .setArtist(book.author)
                            .build()
                    )
                    .build()
            }
            controller?.setMediaItems(items)
        } else {
            controller?.setMediaItem(
                MediaItem.Builder()
                    .setUri(bookUri)
                    .setMediaId(book.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(book.title)
                            .setArtist(book.author)
                            .build()
                    )
                    .build()
            )
        }
        controller?.playWhenReady = false
        controller?.prepare()
    }

    // Translates the controller's chapter-relative position into an absolute book position.
    private fun absolutePosition(): Long {
        val ctrl = controller ?: return _currentPositionMs.value
        val chapters = currentBook?.chapters
        if (chapters.isNullOrEmpty()) return ctrl.currentPosition
        val chapter = chapters.getOrNull(ctrl.currentMediaItemIndex) ?: return ctrl.currentPosition
        return chapter.startTimeMs + ctrl.currentPosition
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            var ticksSinceSave = 0
            while (true) {
                _currentPositionMs.value = absolutePosition()
                delay(500)
                // Auto-save every 30 s so position is preserved during background play,
                // even if onCleared() is never called (service outlives the ViewModel).
                if (++ticksSinceSave >= 60) {
                    savePosition()
                    ticksSinceSave = 0
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    fun playPause() {
        if (controller?.isPlaying == true) {
            savePosition()
            controller?.pause()
        } else {
            controller?.play()
        }
    }

    fun seekTo(absolutePositionMs: Long) {
        val chapters = currentBook?.chapters
        if (!chapters.isNullOrEmpty()) {
            val chapterIndex = chapters.indexOfLast { it.startTimeMs <= absolutePositionMs }
                .coerceAtLeast(0)
            val posInChapter = (absolutePositionMs - chapters[chapterIndex].startTimeMs)
                .coerceAtLeast(0L)
            controller?.seekTo(chapterIndex, posInChapter)
        } else {
            val shouldResumePlaying = controller?.playWhenReady == true
            controller?.seekTo(absolutePositionMs)
            if (shouldResumePlaying) controller?.play()
        }
        _currentPositionMs.value = absolutePositionMs
        savePosition(absolutePositionMs)
    }

    fun skipForward(seconds: Int) {
        val target = (_currentPositionMs.value + seconds * 1000L).coerceAtMost(currentBookDuration)
        seekTo(target)
    }

    fun skipBack(seconds: Int) {
        val target = (_currentPositionMs.value - seconds * 1000L).coerceAtLeast(0L)
        seekTo(target)
    }

    fun savePosition(position: Long? = null) {
        val uri = currentBookUri ?: return
        val pos = when {
            position != null -> position
            controller != null -> absolutePosition()
            else -> return
        }
        viewModelScope.launch {
            bookDao.updatePosition(uri, pos)
        }
    }

    override fun onCleared() {
        savePosition()
        playerListener?.let { controller?.removeListener(it) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
