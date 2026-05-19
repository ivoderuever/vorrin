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
    }

    private var mediaSession: MediaSession? = null
    private var skipDurationMs: Long = 15_000L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionSaveJob: Job? = null
    private val bookDao by lazy { VorrinDatabase.getInstance(this).bookDao() }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(BookCache.get(this))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
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

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    positionSaveJob?.cancel()
                    positionSaveJob = serviceScope.launch {
                        while (isActive) {
                            delay(30_000)
                            saveCurrentPosition(player)
                        }
                    }
                } else {
                    positionSaveJob?.cancel()
                    if (player.playbackState != Player.STATE_IDLE) {
                        saveCurrentPosition(player)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    saveCurrentPosition(player)
                }

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    player.play()
                }
            }
        })

        // Redirect seekToNext/seekToPrevious (Bluetooth earbuds) to skip increments
        // instead of jumping to the next chapter item in the playlist.
        // seekBack/seekForward are also overridden so notification buttons use skipDurationMs.
        val wrappedPlayer = object : ForwardingPlayer(player) {
            override fun seekToNext() = seekForward()
            override fun seekToPrevious() = seekBack()
            override fun getSeekBackIncrement() = skipDurationMs
            override fun getSeekForwardIncrement() = skipDurationMs
            override fun seekBack() {
                seekTo((currentPosition - skipDurationMs).coerceAtLeast(0L))
            }
            override fun seekForward() {
                seekTo((currentPosition + skipDurationMs).coerceAtMost(duration.coerceAtLeast(0L)))
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
                    if (customCommand.customAction == SET_SKIP_DURATION.customAction) {
                        skipDurationMs = args.getInt("seconds", 15).toLong() * 1_000L
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

    private fun saveCurrentPosition(player: Player) {
        if (player.playbackState == Player.STATE_IDLE) return

        val mediaItem = player.currentMediaItem ?: return
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return

        val extras = mediaItem.mediaMetadata.extras ?: return
        if (!extras.containsKey("chapter_start_time")) return

        val chapterStart = extras.getLong("chapter_start_time")
        val absolutePos = chapterStart + player.currentPosition.coerceAtLeast(0L)

        serviceScope.launch(Dispatchers.IO) {
            bookDao.updatePosition(uri, absolutePos)
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
        mediaSession?.player?.let { saveCurrentPosition(it) }
        serviceScope.cancel()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}