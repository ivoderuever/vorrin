package nl.deruever.vorrin.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
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
import nl.deruever.vorrin.data.PreferencesRepository
import nl.deruever.vorrin.data.db.VorrinDatabase
import nl.deruever.vorrin.service.AudiobookService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val bookDao = VorrinDatabase.getInstance(application).bookDao()
    private val preferencesRepository = PreferencesRepository(application)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var currentBookUri: String? = null
    private var chapters: List<Chapter> = emptyList()
    // Tracks which chapter we're in. The controller reports chapter-relative
    // position (because the service's ForwardingPlayer presents chapter-scoped
    // values to MediaSession), so we combine these to get absolute book time.
    // Updated on connect, on seek, and via onMediaMetadataChanged when the
    // service advances chapters naturally.
    private var currentChapterIndex: Int = 0
    private var totalDuration: Long = 0L
    private var positionUpdateJob: Job? = null
    private var startPositionOverride: Pair<String, Long>? = null
    private var isPlaybackActive = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Absolute (whole-book) position, for UI book progress and DB writes.
    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _skipDurationSeconds = MutableStateFlow(15)
    val skipDurationSeconds: StateFlow<Int> = _skipDurationSeconds.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    init {
        viewModelScope.launch {
            _skipDurationSeconds.value = preferencesRepository.getSkipDuration()
            _playbackSpeed.value = preferencesRepository.getPlaybackSpeed()
            controller?.setPlaybackParameters(PlaybackParameters(_playbackSpeed.value))
        }
    }

    fun connect(book: Audiobook) {
        if (book.uri.isBlank() || book.uri == currentBookUri) return

        val overridePos = startPositionOverride?.takeIf { it.first == book.uri }?.second
        startPositionOverride = null
        val effectiveBook = if (overridePos != null) book.copy(lastPosition = overridePos) else book

        currentBookUri = effectiveBook.uri
        chapters = effectiveBook.chapters
        currentChapterIndex = chapterIndexFor(effectiveBook.lastPosition)
        totalDuration = effectiveBook.duration
        positionUpdateJob?.cancel()

        _isReady.value = false
        _isPlaying.value = false
        isPlaybackActive = false
        _currentPositionMs.value = effectiveBook.lastPosition
        _duration.value = effectiveBook.duration

        if (book.status == BookStatus.UNREAD) {
            viewModelScope.launch { bookDao.updateStatus(book.uri, BookStatus.IN_PROGRESS) }
        }

        val context = getApplication<Application>()

        if (controller != null) {
            syncFromExistingSession(effectiveBook)
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
            syncFromExistingSession(effectiveBook)
            loadBookIntoService(context, effectiveBook)
        }, MoreExecutors.directExecutor())
    }

    private fun chapterIndexFor(absoluteMs: Long): Int =
        if (chapters.isEmpty()) 0
        else chapters.indexOfLast { it.startTimeMs <= absoluteMs }.coerceAtLeast(0)

    /**
     * If the service is already running this book (e.g., app reopened mid car
     * trip after the ViewModel was cleared), pull the current chapter index
     * out of the live MediaItem's extras so we don't trust the stale
     * lastPosition from the DB.
     */
    private fun syncFromExistingSession(book: Audiobook) {
        val ctrl = controller ?: return
        val mediaItem = ctrl.currentMediaItem ?: return
        val sessionUri = mediaItem.localConfiguration?.uri?.toString() ?: return
        if (sessionUri != book.uri) return

        val idx = mediaItem.mediaMetadata.extras
            ?.getInt(AudiobookService.EXTRA_CURRENT_CHAPTER_INDEX, -1) ?: -1
        if (idx >= 0 && idx in chapters.indices) {
            currentChapterIndex = idx
        }
    }

    /**
     * Combines our tracked chapter index with the controller's chapter-relative
     * position to produce absolute book time.
     */
    private fun absolutePosition(): Long {
        val chapterRel = (controller?.currentPosition ?: 0L).coerceAtLeast(0L)
        return if (chapters.isNotEmpty() && currentChapterIndex in chapters.indices) {
            chapters[currentChapterIndex].startTimeMs + chapterRel
        } else {
            chapterRel
        }
    }

    private fun loadBookIntoService(context: Context, book: Audiobook) {
        val ctrl = controller ?: return

        val loadedUri = ctrl.currentMediaItem?.localConfiguration?.uri?.toString()
        val state = ctrl.playbackState
        val alreadyLoaded = loadedUri == book.uri && state != Player.STATE_IDLE

        if (alreadyLoaded) {
            _isReady.value = state == Player.STATE_READY
            _duration.value = totalDuration
            _currentPositionMs.value = absolutePosition().takeIf { it > 0 } ?: book.lastPosition
            _isPlaying.value = ctrl.isPlaying
            isPlaybackActive = ctrl.isPlaying
            if (ctrl.isPlaying) startPositionUpdates()
            return
        }

        context.startService(Intent(context, AudiobookService::class.java))

        val bookUri = android.net.Uri.parse(book.uri)

        // Chapter data lives in MediaItem extras so it survives ViewModel death
        val extras = Bundle().apply {
            if (book.chapters.isNotEmpty()) {
                putStringArray(AudiobookService.EXTRA_CHAPTER_TITLES, book.chapters.map { it.title }.toTypedArray())
                putLongArray(AudiobookService.EXTRA_CHAPTER_START_TIMES, book.chapters.map { it.startTimeMs }.toLongArray())
                putLongArray(AudiobookService.EXTRA_CHAPTER_END_TIMES, book.chapters.map { it.endTimeMs }.toLongArray())
                putInt(AudiobookService.EXTRA_CURRENT_CHAPTER_INDEX, currentChapterIndex)
            }
        }

        val initialChapterTitle = book.chapters.getOrNull(currentChapterIndex)?.title

        val metadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .apply {
                if (initialChapterTitle != null) setSubtitle(initialChapterTitle)
            }
            .setArtworkData(book.coverArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setExtras(extras)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(bookUri)
            .setMediaMetadata(metadata)
            .build()

        ctrl.setMediaItem(mediaItem, book.lastPosition)
        ctrl.prepare()
        ctrl.playWhenReady = false
    }

    private fun setupListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    _isPlaying.value = true
                    isPlaybackActive = true
                    startPositionUpdates()
                } else {
                    if (controller?.playWhenReady != true) {
                        _isPlaying.value = false
                    }
                    _currentPositionMs.value = absolutePosition()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    _isReady.value = true
                    _duration.value = totalDuration
                    _currentPositionMs.value = absolutePosition()
                }
                if (state == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    val uri = currentBookUri ?: return
                    viewModelScope.launch { bookDao.updateStatus(uri, BookStatus.FINISHED) }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentPositionMs.value = absolutePosition()
            }

            // The service writes EXTRA_CURRENT_CHAPTER_INDEX into extras when
            // a chapter boundary is crossed. That triggers this callback here
            // (even though no visible metadata changed), giving us a clean
            // synchronization signal.
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val idx = mediaMetadata.extras
                    ?.getInt(AudiobookService.EXTRA_CURRENT_CHAPTER_INDEX, -1)
                    ?: -1
                if (idx >= 0 && idx in chapters.indices && idx != currentChapterIndex) {
                    currentChapterIndex = idx
                    _currentPositionMs.value = absolutePosition()
                }
            }
        })

        controller?.setPlaybackParameters(PlaybackParameters(_playbackSpeed.value))

        if (controller?.playbackState == Player.STATE_READY) {
            _isReady.value = true
            _duration.value = totalDuration
        }
        _isPlaying.value = controller?.isPlaying == true
        isPlaybackActive = controller?.isPlaying == true
        if (controller?.isPlaying == true) startPositionUpdates()
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (true) {
                _currentPositionMs.value = absolutePosition()
                delay(1000)
            }
        }
    }

    fun playPause() {
        if (controller?.isPlaying == true) controller?.pause()
        else controller?.play()
    }

    /**
     * Always use this for ViewModel-initiated seeks. Goes through the
     * SEEK_ABSOLUTE custom command so the service bypasses the chapter-scoped
     * ForwardingPlayer.seekTo and seeks the underlying player directly.
     */
    fun seekTo(absoluteMs: Long) {
        val target = absoluteMs.coerceAtLeast(0L).let {
            if (totalDuration > 0L) it.coerceAtMost(totalDuration) else it
        }
        if (chapters.isNotEmpty()) {
            currentChapterIndex = chapterIndexFor(target)
        }
        val args = Bundle().apply { putLong("position", target) }
        controller?.sendCustomCommand(AudiobookService.SEEK_ABSOLUTE, args)
        _currentPositionMs.value = target
        savePosition(target)
    }

    fun skipForward(seconds: Int) {
        val target = (absolutePosition() + seconds * 1000L).coerceAtMost(totalDuration)
        seekTo(target)
    }

    fun skipBack(seconds: Int) {
        val target = (absolutePosition() - seconds * 1000L).coerceAtLeast(0L)
        seekTo(target)
    }

    fun chapterBack() {
        if (chapters.isEmpty()) return
        val chapterRel = (controller?.currentPosition ?: 0L).coerceAtLeast(0L)
        val targetIdx = if (chapterRel > 2_000L) {
            currentChapterIndex
        } else {
            (currentChapterIndex - 1).coerceAtLeast(0)
        }
        seekTo(chapters[targetIdx].startTimeMs)
    }

    fun chapterForward() {
        if (chapters.isEmpty()) return
        val nextChapter = chapters.getOrNull(currentChapterIndex + 1) ?: return
        seekTo(nextChapter.startTimeMs)
    }

    fun setSkipDuration(seconds: Int) {
        _skipDurationSeconds.value = seconds
        val args = Bundle().apply { putInt("seconds", seconds) }
        controller?.sendCustomCommand(AudiobookService.SET_SKIP_DURATION, args)
        viewModelScope.launch { preferencesRepository.saveSkipDuration(seconds) }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        viewModelScope.launch { preferencesRepository.savePlaybackSpeed(speed) }
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
            currentChapterIndex = 0
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