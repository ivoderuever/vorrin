package nl.deruever.vorrin.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import nl.deruever.vorrin.data.Chapter
import nl.deruever.vorrin.data.db.VorrinDatabase
import nl.deruever.vorrin.service.AudiobookService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val bookDao = VorrinDatabase.getInstance(application).bookDao()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var currentBookUri: String? = null
    private var chapters: List<Chapter> = emptyList()
    private var totalDuration: Long = 0L
    private var positionUpdateJob: Job? = null
    private var startPositionOverride: Pair<String, Long>? = null

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

        val overridePos = startPositionOverride?.takeIf { it.first == book.uri }?.second
        startPositionOverride = null
        val effectiveBook = if (overridePos != null) book.copy(lastPosition = overridePos) else book

        currentBookUri = effectiveBook.uri
        chapters = effectiveBook.chapters
        totalDuration = effectiveBook.duration
        positionUpdateJob?.cancel()

        _isReady.value = false
        _isPlaying.value = false
        _currentPositionMs.value = effectiveBook.lastPosition
        _duration.value = effectiveBook.duration

        if (book.status == BookStatus.UNREAD) {
            viewModelScope.launch { bookDao.updateStatus(book.uri, BookStatus.IN_PROGRESS) }
        }

        val context = getApplication<Application>()

        if (controller != null) {
            loadBookIntoService(context, effectiveBook)
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
            loadBookIntoService(context, effectiveBook)
        }, MoreExecutors.directExecutor())
    }

    // Converts controller position (chapter-relative) to absolute book position.
    private fun absolutePosition(): Long {
        val chapterIndex = controller?.currentMediaItemIndex ?: 0
        val chapterStart = chapters.getOrNull(chapterIndex)?.startTimeMs ?: 0L
        return chapterStart + (controller?.currentPosition ?: 0L).coerceAtLeast(0L)
    }

    private fun loadBookIntoService(context: Context, book: Audiobook) {
        val ctrl = controller ?: return

        val loadedUri = ctrl.currentMediaItem?.localConfiguration?.uri?.toString()
        val state = ctrl.playbackState
        val expectedItemCount = if (book.chapters.isEmpty()) 1 else book.chapters.size
        val alreadyLoaded = loadedUri == book.uri &&
            state != Player.STATE_IDLE &&
            ctrl.mediaItemCount == expectedItemCount

        if (alreadyLoaded) {
            _isReady.value = state == Player.STATE_READY
            _duration.value = totalDuration
            _currentPositionMs.value = absolutePosition().takeIf { it > 0 } ?: book.lastPosition
            _isPlaying.value = ctrl.isPlaying
            if (ctrl.isPlaying) startPositionUpdates()
            return
        }

        context.startService(Intent(context, AudiobookService::class.java))

        val bookUri = android.net.Uri.parse(book.uri)
        val baseMetadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setArtworkData(book.coverArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        if (book.chapters.isEmpty()) {
            ctrl.setMediaItem(
                MediaItem.Builder()
                    .setUri(bookUri)
                    .setMediaMetadata(baseMetadata)
                    .build(),
                book.lastPosition
            )
            ctrl.prepare()
            ctrl.playWhenReady = false
            return
        }

        // One playlist item per chapter, each clipped to its exact time range.
        // ExoPlayer reports position within the current item, so the notification
        // and Bluetooth car display show chapter progress (0–100%) automatically.
        val mediaItems = book.chapters.map { chapter ->
            MediaItem.Builder()
                .setUri(bookUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(chapter.startTimeMs)
                        .setEndPositionMs(chapter.endTimeMs)
                        .build()
                )
                .setMediaMetadata(
                    baseMetadata.buildUpon()
                        .setSubtitle(chapter.title)
                        .setTrackNumber(chapter.index + 1)
                        .setTotalTrackCount(book.chapters.size)
                        .build()
                )
                .build()
        }

        val startIndex = book.chapters
            .indexOfLast { it.startTimeMs <= book.lastPosition }
            .coerceAtLeast(0)
        val startOffset = (book.lastPosition - book.chapters[startIndex].startTimeMs)
            .coerceAtLeast(0L)

        ctrl.setMediaItems(mediaItems, startIndex, startOffset)
        ctrl.prepare()
        ctrl.playWhenReady = false
    }

    private fun setupListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startPositionUpdates()
                else {
                    _currentPositionMs.value = absolutePosition()
                    savePosition()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    _isReady.value = true
                    _duration.value = totalDuration
                    _currentPositionMs.value = absolutePosition()
                }
                if (state == Player.STATE_ENDED) {
                    val uri = currentBookUri ?: return
                    viewModelScope.launch { bookDao.updateStatus(uri, BookStatus.FINISHED) }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentPositionMs.value = absolutePosition()
            }
        })

        // Sync immediately if the service is already in STATE_READY.
        if (controller?.playbackState == Player.STATE_READY) {
            _isReady.value = true
            _duration.value = totalDuration
        }
        _isPlaying.value = controller?.isPlaying == true
        if (controller?.isPlaying == true) startPositionUpdates()
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            var ticks = 0
            while (true) {
                _currentPositionMs.value = absolutePosition()
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

    fun seekTo(absoluteMs: Long) {
        if (chapters.isEmpty()) {
            controller?.seekTo(absoluteMs)
        } else {
            val idx = chapters.indexOfLast { it.startTimeMs <= absoluteMs }.coerceAtLeast(0)
            val offset = (absoluteMs - chapters[idx].startTimeMs).coerceAtLeast(0L)
            controller?.seekTo(idx, offset)
        }
        _currentPositionMs.value = absoluteMs
        savePosition(absoluteMs)
    }

    fun skipForward(seconds: Int) {
        val target = (absolutePosition() + seconds * 1000L).coerceAtMost(totalDuration)
        seekTo(target)
    }

    fun skipBack(seconds: Int) {
        val target = (absolutePosition() - seconds * 1000L).coerceAtLeast(0L)
        seekTo(target)
    }

    fun resetProgress(book: Audiobook) {
        viewModelScope.launch {
            bookDao.updatePosition(book.uri, 0L)
            bookDao.updateStatus(book.uri, BookStatus.IN_PROGRESS)
        }
        if (book.uri == currentBookUri) {
            seekTo(0L)
        } else {
            startPositionOverride = book.uri to 0L
            currentBookUri = null
        }
    }

    fun savePosition(position: Long? = null) {
        val uri = currentBookUri ?: return
        val pos = position ?: absolutePosition()
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
