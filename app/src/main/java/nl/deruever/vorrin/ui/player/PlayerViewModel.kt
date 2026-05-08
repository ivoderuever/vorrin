package nl.deruever.vorrin.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.service.AudiobookService

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    fun connect(book: Audiobook) {
        if (book.uri.isBlank()) return

        val context = getApplication<Application>()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AudiobookService::class.java)
        )

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
                    }
                }
            })

            val mediaItem = MediaItem.fromUri(Uri.parse(book.uri))
            controller?.setMediaItem(mediaItem)
            controller?.prepare()

            // Start polling position
            viewModelScope.launch {
                while (true) {
                    _currentPositionMs.value = controller?.currentPosition ?: 0L
                    delay(500)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    fun playPause() {
        if (controller?.isPlaying == true) controller?.pause()
        else controller?.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun skipForward(seconds: Int) {
        val newPos = (controller?.currentPosition ?: 0L) + seconds * 1000L
        controller?.seekTo(newPos.coerceAtMost(controller?.duration ?: 0L))
    }

    fun skipBack(seconds: Int) {
        val newPos = (controller?.currentPosition ?: 0L) - seconds * 1000L
        controller?.seekTo(newPos.coerceAtLeast(0L))
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture ?: return)
        super.onCleared()
    }
}