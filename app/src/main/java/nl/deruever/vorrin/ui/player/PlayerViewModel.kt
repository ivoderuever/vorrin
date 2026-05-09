package nl.deruever.vorrin.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
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
    private var currentBookUri: String? = null
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
        initialSeekPerformed = false

        val context = getApplication<Application>()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AudiobookService::class.java)
        )

        controllerFuture?.let { MediaController.releaseFuture(it) }
        positionUpdateJob?.cancel()
        _isReady.value = false

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        _isReady.value = true
                        _duration.value = controller?.duration ?: 0L
                        if (!initialSeekPerformed && book.lastPosition > 0) {
                            initialSeekPerformed = true
                            controller?.seekTo(book.lastPosition)
                        }
                    }
                }
            })

            controller?.playWhenReady = false
            controller?.setMediaItem(MediaItem.fromUri(Uri.parse(book.uri)))
            controller?.prepare()

            positionUpdateJob = viewModelScope.launch {
                while (true) {
                    _currentPositionMs.value = controller?.currentPosition ?: 0L
                    delay(500)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    fun playPause() {
        if (controller?.isPlaying == true) {
            savePosition()
            controller?.pause()
        } else {
            controller?.play()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        savePosition(positionMs)
    }

    fun skipForward(seconds: Int) {
        val current = controller?.currentPosition ?: 0L
        val target = (current + seconds * 1000L).coerceAtMost(controller?.duration ?: 0L)
        controller?.seekTo(target)
        _currentPositionMs.value = target
        savePosition(target)
    }

    fun skipBack(seconds: Int) {
        val current = controller?.currentPosition ?: 0L
        val target = (current - seconds * 1000L).coerceAtLeast(0L)
        controller?.seekTo(target)
        _currentPositionMs.value = target
        savePosition(target)
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}