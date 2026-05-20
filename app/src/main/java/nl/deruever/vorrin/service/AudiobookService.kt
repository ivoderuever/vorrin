package nl.deruever.vorrin.service

import android.app.PendingIntent
import android.content.Intent
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
import nl.deruever.vorrin.data.db.VorrinDatabase

class AudiobookService : MediaSessionService() {

    companion object {
        val SET_SKIP_DURATION = SessionCommand("set_skip_duration", Bundle.EMPTY)
        val SEEK_ABSOLUTE = SessionCommand("seek_absolute", Bundle.EMPTY)

        // Parallel arrays in MediaItem extras — survive process serialization
        // so the service can do chapter watching with no ViewModel attached.
        const val EXTRA_CHAPTER_TITLES = "chapter_titles"
        const val EXTRA_CHAPTER_START_TIMES = "chapter_start_times"
        const val EXTRA_CHAPTER_END_TIMES = "chapter_end_times"
        // Service writes this on every chapter advance; ViewModel reads it on
        // reconnect to re-sync after being killed.
        const val EXTRA_CURRENT_CHAPTER_INDEX = "current_chapter_index"

        private const val SEEK_ABSOLUTE_KEY = "position"
    }

    private val DEBUG_DISABLE_CACHE = false

    private var mediaSession: MediaSession? = null
    // Direct reference to the underlying ExoPlayer (bypassing the
    // ForwardingPlayer wrapper). Used wherever we need ABSOLUTE position —
    // saving to DB, the SEEK_ABSOLUTE command, the chapter watch.
    private var underlyingPlayer: ExoPlayer? = null
    private var skipDurationMs: Long = 15_000L
    private var lastChapterIndex: Int = -1

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionSaveJob: Job? = null
    private var chapterWatchJob: Job? = null
    private val bookDao by lazy { VorrinDatabase.getInstance(this).bookDao() }

    private data class ServiceChapter(val title: String, val startMs: Long, val endMs: Long)

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
                true
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                lastChapterIndex = -1
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    saveCurrentPosition()
                }
            }
        })

        // ForwardingPlayer that presents chapter-scoped duration/position to
        // MediaSession. The notification, AVRCP, and Android Auto derive their
        // progress display from these values. The underlying ExoPlayer still
        // operates in absolute book time.
        //
        // Convention inside this object:
        //   super.X()   → ForwardingPlayer's impl → underlying = ABSOLUTE
        //   X()         → our chapter-scoped value
        val wrappedPlayer = object : ForwardingPlayer(player) {

            private fun currentChapter(): ServiceChapter? {
                val chapters = chaptersFromMediaItem(super.getCurrentMediaItem())
                if (chapters.isEmpty()) return null
                val absPos = super.getCurrentPosition().coerceAtLeast(0L)
                return chapters.lastOrNull { it.startMs <= absPos } ?: chapters.firstOrNull()
            }

            // --- Chapter-scoped reporting ---
            // If no chapters are available, every override falls through to
            // the absolute values, so books without chapters behave like a
            // normal single-track audio file.

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

            // External seeks (notification seek bar, AVRCP) arrive in our
            // chapter-scoped frame. Translate to absolute.
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

            // Skip increments work in absolute so they cross chapter boundaries.
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
     * Watch the underlying (absolute) position while playing. When we cross
     * into a new chapter, update the MediaItem extras with the new chapter
     * index. This fires onMediaMetadataChanged on any connected MediaController
     * so the ViewModel can stay in sync.
     *
     * The visible MediaMetadata fields (title, artist, album, subtitle) are
     * NOT touched here — only extras. Title stays as the book name throughout.
     */
    @OptIn(UnstableApi::class)
    private fun startChapterWatch(player: Player) {
        chapterWatchJob?.cancel()
        chapterWatchJob = serviceScope.launch {
            while (isActive) {
                val currentItem = player.currentMediaItem
                val chapters = chaptersFromMediaItem(currentItem)
                if (chapters.isNotEmpty() && currentItem != null) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val idx = chapters.indexOfLast { it.startMs <= pos }

                    if (idx >= 0 && idx != lastChapterIndex) {
                        lastChapterIndex = idx
                        val newChapterTitle = chapters[idx].title

                        val existing = currentItem.mediaMetadata.extras ?: Bundle.EMPTY
                        val newExtras = Bundle(existing).apply {
                            putInt(EXTRA_CURRENT_CHAPTER_INDEX, idx)
                        }

                        // Because we use buildUpon(), the Title, Artist, and AlbumTitle
                        // remain perfectly intact. We only overwrite the Subtitle and Extras.
                        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
                            .setSubtitle(newChapterTitle)
                            .setExtras(newExtras)
                            .build()

                        val updatedItem = currentItem.buildUpon()
                            .setMediaMetadata(updatedMetadata)
                            .build()

                        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
                    }
                }
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