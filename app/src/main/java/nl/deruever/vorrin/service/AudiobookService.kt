// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
package nl.deruever.vorrin.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.deruever.vorrin.MainActivity
import nl.deruever.vorrin.data.BookCache
import nl.deruever.vorrin.data.PreferencesRepository
import nl.deruever.vorrin.data.db.VorrinDatabase

class AudiobookService : MediaSessionService() {

    companion object {
        val SET_SKIP_DURATION = SessionCommand("set_skip_duration", Bundle.EMPTY)
        val SEEK_ABSOLUTE = SessionCommand("seek_absolute", Bundle.EMPTY)

        const val EXTRA_CHAPTER_TITLES = "chapter_titles"
        const val EXTRA_CHAPTER_START_TIMES = "chapter_start_times"
        const val EXTRA_CHAPTER_END_TIMES = "chapter_end_times"
        const val EXTRA_CURRENT_CHAPTER_INDEX = "current_chapter_index"

        private const val SEEK_ABSOLUTE_KEY = "position"

        private const val REWIND_PAUSE_THRESHOLD_MS = 2 * 60_000L
        private const val REWIND_AMOUNT_MS = 10_000L
    }

    private val DEBUG_DISABLE_CACHE = false

    private var mediaSession: MediaSession? = null
    private var underlyingPlayer: ExoPlayer? = null
    private var skipDurationMs: Long = 15_000L

    private var lastChapterIndex: Int = -1
    private var lastBookUri: String? = null

    private var rewindOnResumeEnabled = true

    // Wall-clock time (epoch millis) of the last pause for the current book.
    // Mirrored to the books table so the recap rewind survives process death.
    private var pausedAtWallClockMs: Long? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionSaveJob: Job? = null
    private var chapterWatchJob: Job? = null
    private val bookDao by lazy { VorrinDatabase.getInstance(this).bookDao() }
    private val preferencesRepository by lazy { PreferencesRepository(this) }

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // ---------------------------------------------------------------------
    // Audio focus
    // ---------------------------------------------------------------------

    private enum class FocusState { NONE, PENDING, GRANTED }

    private var focusState = FocusState.NONE
    private var audioFocusRequest: AudioFocusRequest? = null
    private var playOnFocusGain = false
    private var pauseFromFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = mediaSession?.player ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusState = FocusState.GRANTED
                if (playOnFocusGain) {
                    playOnFocusGain = false
                    player.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                playOnFocusGain = false
                pauseFromFocusLoss = true
                player.pause()
                pauseFromFocusLoss = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                playOnFocusGain = player.isPlaying
                pauseFromFocusLoss = true
                player.pause()
                pauseFromFocusLoss = false
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        when (focusState) {
            FocusState.GRANTED -> return true
            FocusState.PENDING -> {
                playOnFocusGain = true
                return false
            }
            FocusState.NONE -> Unit
        }

        val platformAudioAttrs = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(platformAudioAttrs)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(true)
            .build()

        return when (audioManager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusRequest = request
                focusState = FocusState.GRANTED
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                audioFocusRequest = request
                focusState = FocusState.PENDING
                playOnFocusGain = true
                false
            }
            else -> false
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null
        focusState = FocusState.NONE
        playOnFocusGain = false
    }

    // ---------------------------------------------------------------------
    // Rewind on resume
    // ---------------------------------------------------------------------

    private fun applyResumeRewindIfNeeded() {
        if (!rewindOnResumeEnabled) return
        val player = underlyingPlayer ?: return
        val pausedAt = pausedAtWallClockMs ?: return
        pausedAtWallClockMs = null

        if (System.currentTimeMillis() - pausedAt >= REWIND_PAUSE_THRESHOLD_MS) {
            player.seekTo((player.currentPosition - REWIND_AMOUNT_MS).coerceAtLeast(0L))
        }
    }

    private fun setPausedAt(timestamp: Long?) {
        pausedAtWallClockMs = timestamp
        val uri = underlyingPlayer?.currentMediaItem
            ?.localConfiguration?.uri?.toString() ?: return
        serviceScope.launch(Dispatchers.IO) {
            bookDao.updateLastPausedAt(uri, timestamp)
        }
    }

    // ---------------------------------------------------------------------
    // Chapters
    // ---------------------------------------------------------------------

    private data class ServiceChapter(val title: String, val startMs: Long, val endMs: Long)

    private var chapterCacheItem: MediaItem? = null
    private var chapterCache: List<ServiceChapter> = emptyList()

    private fun chaptersFor(item: MediaItem?): List<ServiceChapter> {
        if (item !== chapterCacheItem) {
            chapterCacheItem = item
            chapterCache = chaptersFromMediaItem(item)
        }
        return chapterCache
    }

    private fun chaptersFromMediaItem(item: MediaItem?): List<ServiceChapter> {
        val extras = item?.mediaMetadata?.extras ?: return emptyList()
        val titles = extras.getStringArray(EXTRA_CHAPTER_TITLES) ?: return emptyList()
        val starts = extras.getLongArray(EXTRA_CHAPTER_START_TIMES) ?: return emptyList()
        val ends = extras.getLongArray(EXTRA_CHAPTER_END_TIMES) ?: return emptyList()
        if (titles.size != starts.size || titles.size != ends.size) return emptyList()
        return List(titles.size) { i -> ServiceChapter(titles[i], starts[i], ends[i]) }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Keep the rewind-on-resume setting in sync with DataStore so the
        // toggle works immediately, without a custom session command.
        serviceScope.launch {
            preferencesRepository.rewindOnResume.collect { enabled ->
                rewindOnResumeEnabled = enabled
            }
        }

        val dataSourceFactory = if (DEBUG_DISABLE_CACHE) {
            DefaultDataSource.Factory(this)
        } else {
            CacheDataSource.Factory()
                .setCache(BookCache.get(this))
                .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 60_000, 1_500, 5_000)
                    .build()
            )
            .build()
        underlyingPlayer = player

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    positionSaveJob?.cancel()
                    positionSaveJob = serviceScope.launch {
                        while (isActive) {
                            delay(30_000)
                            saveCurrentPosition()
                        }
                    }
                    startChapterWatch(player)
                } else {
                    positionSaveJob?.cancel()
                    chapterWatchJob?.cancel()
                    if (player.playbackState != Player.STATE_IDLE) {
                        saveCurrentPosition()
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    // The rewind (if any) was already applied; the book is no
                    // longer paused, so drop the persisted timestamp too.
                    setPausedAt(null)
                } else {
                    setPausedAt(System.currentTimeMillis())
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri?.toString()
                if (uri != lastBookUri) {
                    lastBookUri = uri
                    lastChapterIndex = -1
                    pausedAtWallClockMs = null
                    if (uri != null) {
                        // Restore the pause timestamp persisted for this book so
                        // the recap rewind survives the service being killed.
                        serviceScope.launch {
                            val persisted = bookDao.getLastPausedAt(uri)
                            if (uri == lastBookUri &&
                                pausedAtWallClockMs == null &&
                                underlyingPlayer?.playWhenReady != true
                            ) {
                                pausedAtWallClockMs = persisted
                            }
                        }
                    }
                }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    saveCurrentPosition()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    updateChapterMetadata(player, newPosition.positionMs)
                    saveCurrentPosition()

                    if (!player.playWhenReady && pausedAtWallClockMs != null) {
                        setPausedAt(System.currentTimeMillis())
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    abandonAudioFocus()
                    setPausedAt(null)
                }
            }
        })

        // ForwardingPlayer that presents chapter-scoped duration/position to MediaSession.
        // If no chapters are available, overrides fall through to absolute values.
        val wrappedPlayer = object : ForwardingPlayer(player) {

            private fun currentChapter(): ServiceChapter? {
                val chapters = chaptersFor(super.getCurrentMediaItem())
                if (chapters.isEmpty()) return null
                val absPos = super.getCurrentPosition().coerceAtLeast(0L)
                return chapters.lastOrNull { it.startMs <= absPos } ?: chapters.firstOrNull()
            }

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return getAvailableCommands().contains(command)
            }

            override fun getCurrentPosition(): Long {
                val ch = currentChapter() ?: return super.getCurrentPosition()
                return (super.getCurrentPosition() - ch.startMs).coerceAtLeast(0L)
            }

            override fun getDuration(): Long {
                val ch = currentChapter() ?: return super.getDuration()
                return ch.endMs - ch.startMs
            }

            override fun getBufferedPosition(): Long {
                val ch = currentChapter() ?: return super.getBufferedPosition()
                return (super.getBufferedPosition() - ch.startMs)
                    .coerceAtLeast(0L)
                    .coerceAtMost(ch.endMs - ch.startMs)
            }

            override fun getContentPosition(): Long = getCurrentPosition()
            override fun getContentDuration(): Long = getDuration()
            override fun getContentBufferedPosition(): Long = getBufferedPosition()

            override fun seekTo(positionMs: Long) {
                val ch = currentChapter()
                if (ch == null) {
                    super.seekTo(positionMs)
                } else {
                    val absolute = (ch.startMs + positionMs)
                        .coerceIn(ch.startMs, (ch.endMs - 1).coerceAtLeast(ch.startMs))
                    super.seekTo(absolute)
                }
            }

            override fun seekToNext() = seekForward()
            override fun seekToPrevious() = seekBack()
            override fun getSeekBackIncrement() = skipDurationMs
            override fun getSeekForwardIncrement() = skipDurationMs

            override fun seekBack() {
                val absolute = (super.getCurrentPosition() - skipDurationMs).coerceAtLeast(0L)
                super.seekTo(absolute)
            }

            override fun seekForward() {
                val absolute = (super.getCurrentPosition() + skipDurationMs)
                    .coerceAtMost(super.getDuration().coerceAtLeast(0L))
                super.seekTo(absolute)
            }

            override fun play() {
                if (requestAudioFocus()) {
                    applyResumeRewindIfNeeded()
                    super.play()
                }
            }

            override fun pause() {
                if (!pauseFromFocusLoss) {
                    playOnFocusGain = false
                    abandonAudioFocus()
                }
                super.pause()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) {
                    if (requestAudioFocus()) {
                        applyResumeRewindIfNeeded()
                        super.setPlayWhenReady(true)
                    }
                } else {
                    if (!pauseFromFocusLoss) {
                        playOnFocusGain = false
                        abandonAudioFocus()
                    }
                    super.setPlayWhenReady(false)
                }
            }
        }

        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_player", true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, sessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, wrappedPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(SET_SKIP_DURATION)
                        .add(SEEK_ABSOLUTE)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        SET_SKIP_DURATION.customAction -> {
                            skipDurationMs = args.getInt("seconds", 15).toLong() * 1_000L
                        }
                        SEEK_ABSOLUTE.customAction -> {
                            val absoluteMs = args.getLong(SEEK_ABSOLUTE_KEY, 0L)
                            underlyingPlayer?.seekTo(absoluteMs.coerceAtLeast(0L))
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setMediaButtonPreferences(
                ImmutableList.of(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                        .setDisplayName("Skip back")
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_PLAY)
                        .setDisplayName("Play / Pause")
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                        .setDisplayName("Skip forward")
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .build()
                )
            )
            .build()
    }

    /**
     * Compute the chapter for the given absolute position and, if it differs
     * from the last published one, push it into the MediaItem's subtitle and
     * extras via replaceMediaItem. This fires onMediaMetadataChanged on any
     * connected MediaController so the ViewModel can stay in sync.
     *
     * Called from the 500ms watch loop (natural chapter crossings during
     * playback) and from onPositionDiscontinuity (seeks, including while
     * paused).
     */
    @OptIn(UnstableApi::class)
    private fun updateChapterMetadata(
        player: Player,
        absolutePositionMs: Long = player.currentPosition
    ) {
        val currentItem = player.currentMediaItem ?: return
        val chapters = chaptersFor(currentItem)
        if (chapters.isEmpty()) return

        val pos = absolutePositionMs.coerceAtLeast(0L)
        val idx = chapters.indexOfLast { it.startMs <= pos }
        if (idx < 0 || idx == lastChapterIndex) return

        lastChapterIndex = idx
        val newChapterTitle = chapters[idx].title

        val existing = currentItem.mediaMetadata.extras ?: Bundle.EMPTY
        val newExtras = Bundle(existing).apply {
            putInt(EXTRA_CURRENT_CHAPTER_INDEX, idx)
        }

        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setSubtitle(newChapterTitle)
            .setExtras(newExtras)
            .build()

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
    }

    /**
     * Watch the underlying (absolute) position while playing so natural
     * chapter crossings update the metadata. Seek-driven crossings are
     * handled immediately by onPositionDiscontinuity instead.
     */
    private fun startChapterWatch(player: Player) {
        chapterWatchJob?.cancel()
        chapterWatchJob = serviceScope.launch {
            while (isActive) {
                updateChapterMetadata(player)
                delay(500)
            }
        }
    }

    private fun saveCurrentPosition() {
        val player = underlyingPlayer ?: return
        if (player.playbackState == Player.STATE_IDLE) return

        val mediaItem = player.currentMediaItem ?: return
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)

        serviceScope.launch(Dispatchers.IO) {
            bookDao.updatePosition(uri, pos)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.pause()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        positionSaveJob?.cancel()
        chapterWatchJob?.cancel()
        saveCurrentPosition()
        abandonAudioFocus()
        serviceScope.cancel()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        underlyingPlayer = null
        super.onDestroy()
    }
}