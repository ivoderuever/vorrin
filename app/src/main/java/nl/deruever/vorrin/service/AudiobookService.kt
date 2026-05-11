package nl.deruever.vorrin.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import nl.deruever.vorrin.MainActivity
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.session.CommandButton
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import nl.deruever.vorrin.data.Chapter

class AudiobookService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // Skip durations are kept. Because we are using a single file again,
            // Android will natively display these on the lock screen and notification.
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 60_000, 1_500, 5_000)
                    .build()
            )
            .build()

        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_player", true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionPendingIntent = PendingIntent.getActivity(
            this, 0, sessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionPendingIntent)
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

    /**
     * @param splitChapters If false (default), loads the entire book as one track.
     *                      If true, slices the book into a playlist of chapters.
     */
    fun loadAudiobook(
        audioUri: Uri,
        bookTitle: String,
        chapters: List<Chapter>,
        splitChapters: Boolean = false
    ) {
        if (splitChapters && chapters.isNotEmpty()) {
            // OPTIONAL: The clipped playlist approach
            val mediaItems = chapters.mapIndexed { index, chapter ->
                val endTimeMs = if (index < chapters.size - 1) {
                    chapters[index + 1].startTimeMs
                } else {
                    C.TIME_END_OF_SOURCE
                }

                val clippingConfig = ClippingConfiguration.Builder()
                    .setStartPositionMs(chapter.startTimeMs)
                    .setEndPositionMs(endTimeMs)
                    .build()

                val metadata = MediaMetadata.Builder()
                    .setTitle(bookTitle)
                    .setArtist("Chapter ${index + 1}")
                    .build()

                MediaItem.Builder()
                    .setUri(audioUri)
                    .setMediaId("chapter_${chapter.index}")
                    .setClippingConfiguration(clippingConfig)
                    .setMediaMetadata(metadata)
                    .build()
            }
            player.setMediaItems(mediaItems)
        } else {
            // DEFAULT: The entire book as a single unbroken file
            val metadata = MediaMetadata.Builder()
                .setTitle(bookTitle)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(audioUri)
                .setMediaId("full_book")
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem)
        }

        player.prepare()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away from recents. Pause and shut down the service.
        // Position is already persisted by the ViewModel's 30-second auto-save,
        // so at most 30 s of progress is lost.
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