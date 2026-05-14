package nl.deruever.vorrin.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import com.google.common.collect.ImmutableList
import nl.deruever.vorrin.MainActivity
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.BookCache
import nl.deruever.vorrin.data.Chapter

class AudiobookService : MediaSessionService() {

    companion object {
        var pendingBook: nl.deruever.vorrin.data.Audiobook? = null
    }

    private var mediaSession: MediaSession? = null
    private var currentChapters: List<Chapter> = emptyList()
    private var lastChapterIndex: Int = -1
    private var lastSeekedUri: String? = null

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
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 60_000, 1_500, 5_000)
                    .build()
            )
            .build()

        // Redirect seekToNext/seekToPrevious (sent by Bluetooth earbuds) to seekForward/seekBack
        // so they advance 30 s instead of jumping to the next embedded chapter marker.
        val wrappedPlayer = object : ForwardingPlayer(player) {
            override fun seekToNext() = seekForward()
            override fun seekToPrevious() = seekBack()
        }

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                    player.playbackState == Player.STATE_READY) {
                    val uri = pendingBook?.uri
                    if (uri != null && uri != lastSeekedUri) {
                        lastSeekedUri = uri
                        val saved = pendingBook?.lastPosition ?: 0L
                        if (saved > 0) player.seekTo(saved)
                    }
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                    updateChapterMetadata(player)
                }
            }
        })

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
            .setMediaButtonPreferences(
                ImmutableList.of(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                        .setDisplayName("Skip back 30 seconds")
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_PLAY)
                        .setDisplayName("Play / Pause")
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                        .setDisplayName("Skip forward 30 seconds")
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .build()
                )
            )
            .build()
    }

    fun loadBook(book: Audiobook) {
        val player = mediaSession?.player ?: return
        AudiobookService.pendingBook = book
        currentChapters = book.chapters
        lastChapterIndex = -1
        lastSeekedUri = null

        val metadata = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setArtworkData(
                book.coverArt,
                MediaMetadata.PICTURE_TYPE_FRONT_COVER
            )
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(book.uri))
            .setMediaMetadata(metadata)
            .build()

        player.playWhenReady = false
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    fun seekTo(positionMs: Long) {
        mediaSession?.player?.seekTo(positionMs)
    }

    private fun updateChapterMetadata(player: Player) {
        if (currentChapters.isEmpty()) {
            currentChapters = pendingBook?.chapters ?: emptyList()
        }
        if (currentChapters.isEmpty()) return
        val position = player.currentPosition
        val chapter = currentChapters.lastOrNull { it.startTimeMs <= position }
            ?: currentChapters.firstOrNull()
            ?: return
        if (chapter.index == lastChapterIndex) return
        lastChapterIndex = chapter.index
        val current = player.currentMediaItem ?: return
        val updated = current.buildUpon()
            .setMediaMetadata(
                current.mediaMetadata.buildUpon()
                    .setSubtitle(chapter.title)
                    .build()
            )
            .build()
        player.replaceMediaItem(0, updated)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.pause()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}