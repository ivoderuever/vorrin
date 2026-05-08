package nl.deruever.vorrin.ui.player

import android.app.Application
import android.content.ComponentName
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.Chapter
import nl.deruever.vorrin.service.AudiobookService
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var currentBookUri: String? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val preferencesRepository = nl.deruever.vorrin.data.PreferencesRepository(application)

    private val _currentBookUri = MutableStateFlow<String?>(null)

    suspend fun connect(book: Audiobook) {
        if (book.uri.isBlank() || book.uri == currentBookUri) return
        currentBookUri = book.uri

        val context = getApplication<Application>()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AudiobookService::class.java)
        )

        val savedPosition = preferencesRepository.getBookPosition(book.uri)
        if (savedPosition > 0) {
            controller?.seekTo(savedPosition)
        }

        // Release any existing controller before creating a new one
        controllerFuture?.let { MediaController.releaseFuture(it) }
        _isReady.value = false
        _chapters.value = emptyList()

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

                        // M4B/MP4: extract chapters via MediaExtractor on IO thread
                        viewModelScope.launch {
                            val extracted = extractChaptersFromMp4(Uri.parse(book.uri))
                            if (extracted.isNotEmpty()) {
                                _chapters.value = extracted
                            }
                            if (book.lastPosition > 0) {
                                controller?.seekTo(book.lastPosition)
                            }
                        }
                    }
                }

                @UnstableApi
                override fun onMetadata(metadata: Metadata) {
                    val parsed = parseId3Chapters(metadata)
                    if (parsed.isNotEmpty()) {
                        _chapters.value = parsed
                    }
                }
            })

            controller?.playWhenReady = false
            controller?.setMediaItem(MediaItem.fromUri(Uri.parse(book.uri)))
            controller?.prepare()

            viewModelScope.launch {
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
        savePosition()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    // --- Chapter extraction ---

    // For M4B/MP4: QuickTime chapter track via MediaExtractor.
    // Chapters are stored as a timed text track referenced by moov/trak/tref/chap.
    // Each sample's timestamp is the chapter start time; text content is the title.
    private suspend fun extractChaptersFromMp4(uri: Uri): List<Chapter> =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(getApplication<Application>(), uri, null)

                val chapterTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                    // QuickTime chapter tracks use tx3g or plain text mime types
                    mime == "text/3gpp-tt" || mime == "text/plain" || mime == "application/x-quicktime-text"
                } ?: return@withContext emptyList()

                extractor.selectTrack(chapterTrackIndex)

                val rawChapters = mutableListOf<Pair<Long, String>>() // startMs to title
                val buffer = ByteBuffer.allocate(4096)

                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break

                    val startMs = extractor.sampleTime / 1000L
                    val bytes = ByteArray(size)
                    buffer.rewind()
                    buffer.get(bytes, 0, size)
                    val title = parseQtTextSample(bytes)

                    if (title.isNotBlank()) {
                        rawChapters.add(startMs to title)
                    }
                    extractor.advance()
                }

                val totalDuration = _duration.value

                rawChapters.mapIndexed { index, (startMs, title) ->
                    Chapter(
                        index = index,
                        title = title,
                        startTimeMs = startMs,
                        endTimeMs = rawChapters.getOrNull(index + 1)?.first ?: totalDuration
                    )
                }
            } catch (e: Exception) {
                emptyList()
            } finally {
                extractor.release()
            }
        }

    // QuickTime text samples have a 2-byte big-endian length prefix followed by UTF-8 text
    private fun parseQtTextSample(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val len = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (len <= 0 || bytes.size < 2 + len) return ""
        return String(bytes, 2, len, Charsets.UTF_8)
    }

    // For ID3 chapters (MP3 with embedded chapter frames)
    @UnstableApi
    private fun parseId3Chapters(metadata: Metadata): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            if (entry is ChapterFrame) {
                var title = "Chapter ${chapters.size + 1}"
                for (j in 0 until entry.getSubFrameCount()) {
                    val subFrame = entry.getSubFrame(j)
                    if (subFrame is TextInformationFrame) {
                        title = subFrame.values.firstOrNull() ?: title
                        break
                    }
                }
                chapters.add(
                    Chapter(
                        index = chapters.size,
                        title = title,
                        startTimeMs = entry.startTimeMs.toLong(),
                        endTimeMs = entry.endTimeMs.toLong()
                    )
                )
            }
        }
        return chapters.sortedBy { it.startTimeMs }
    }

    fun savePosition() {
        viewModelScope.launch {
            val uri = currentBookUri ?: return@launch
            val position = controller?.currentPosition ?: return@launch
            preferencesRepository.saveBookPosition(uri, position)
        }
    }
}