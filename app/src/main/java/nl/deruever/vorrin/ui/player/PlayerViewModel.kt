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
    private var totalDuration: Long = 0L
    private var positionUpdateJob: Job? = null
    private var startPositionOverride: Pair<String, Long>? = null
    private var isPlaybackActive = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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
            isPlaybackActive = ctrl.isPlaying
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
            val extras = Bundle().apply { putLong("chapter_start_time", 0L) }
            ctrl.setMediaItem(
                MediaItem.Builder()
                    .setUri(bookUri)
                    .setMediaMetadata(baseMetadata.buildUpon().setExtras(extras).build())
                    .build(),
                book.lastPosition
            )
            ctrl.prepare()
            ctrl.playWhenReady = false
            return
        }

        val mediaItems = book.chapters.map { chapter ->
            val extras = Bundle().apply { putLong("chapter_start_time", chapter.startTimeMs) }

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
                        .setExtras(extras)
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

    fun seekTo(absoluteMs: Long) {
        if (chapters.isEmpty()) {
            controller?.seekTo(absoluteMs)
        } else {
            val idx = chapters.indexOfLast { it.startTimeMs <= absoluteMs }.coerceAtLeast(0)
            val offset = (absoluteMs - chapters[idx].startTimeMs).coerceAtLeast(0L)
            controller?.seekTo(idx, offset)
        }
        _currentPositionMs.value = absoluteMs
        // We still trigger a manual save via the ViewModel on explicit user seek actions.
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

    fun chapterBack() {
        val currentIndex = controller?.currentMediaItemIndex ?: return
        val targetIndex = (currentIndex - 1).coerceAtLeast(0)
        val targetMs = chapters.getOrNull(targetIndex)?.startTimeMs ?: 0L
        seekTo(targetMs)
    }

    fun chapterForward() {
        val currentIndex = controller?.currentMediaItemIndex ?: return
        val targetMs = chapters.getOrNull(currentIndex + 1)?.startTimeMs ?: return
        seekTo(targetMs)
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