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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.deruever.vorrin.MainActivity
import nl.deruever.vorrin.data.BookCache
import nl.deruever.vorrin.data.BookStatus
import nl.deruever.vorrin.data.PreferencesRepository
import nl.deruever.vorrin.data.db.BookEntity
import nl.deruever.vorrin.data.db.ChapterEntity
import nl.deruever.vorrin.data.db.VorrinDatabase

class AudiobookService : MediaLibraryService() {

    companion object {
        val SET_SKIP_DURATION = SessionCommand("set_skip_duration", Bundle.EMPTY)
        val SEEK_ABSOLUTE = SessionCommand("seek_absolute", Bundle.EMPTY)
        // Controllers request saves; the service is the single writer of progress
        val SAVE_POSITION = SessionCommand("save_position", Bundle.EMPTY)
        // Chapter jumps for the car; primary prev/next stay time skips
        val CHAPTER_PREV = SessionCommand("chapter_prev", Bundle.EMPTY)
        val CHAPTER_NEXT = SessionCommand("chapter_next", Bundle.EMPTY)

        // Android Auto browse tree: a flat list of the active book's chapters
        private const val ROOT_MEDIA_ID = "root"
        private const val BOOK_MEDIA_ID = "book"
        private const val CHAPTER_MEDIA_ID_PREFIX = "chapter:"

        const val EXTRA_CHAPTER_TITLES = "chapter_titles"
        const val EXTRA_CHAPTER_START_TIMES = "chapter_start_times"
        const val EXTRA_CHAPTER_END_TIMES = "chapter_end_times"
        const val EXTRA_CURRENT_CHAPTER_INDEX = "current_chapter_index"

        private const val SEEK_ABSOLUTE_KEY = "position"

        private const val REWIND_PAUSE_THRESHOLD_MS = 2 * 60_000L
        private const val REWIND_AMOUNT_MS = 10_000L
    }

    private val DEBUG_DISABLE_CACHE = false

    private var mediaSession: MediaLibrarySession? = null
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

        // Keep the rewind-on-resume setting in sync with DataStore
        serviceScope.launch {
            preferencesRepository.rewindOnResume.collect { enabled ->
                rewindOnResumeEnabled = enabled
            }
        }

        // Cold starts (Android Auto) need the configured skip duration too
        serviceScope.launch {
            skipDurationMs = preferencesRepository.getSkipDuration() * 1_000L
        }

        val dataSourceFactory = if (DEBUG_DISABLE_CACHE) {
            DefaultDataSource.Factory(this)
        } else {
            // Read-only: serves the prewarmed head/tail from cache but does not
            // copy entire books into it while playing.
            CacheDataSource.Factory()
                .setCache(BookCache.get(this))
                .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))
                .setCacheWriteDataSinkFactory(null)
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
                    // Rewind already applied; the book is no longer paused
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
                        // Restore the persisted pause timestamp for this book
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
                    // The car browse list shows the active book's chapters
                    mediaSession?.notifyChildrenChanged(
                        ROOT_MEDIA_ID, chaptersFor(mediaItem).size.coerceAtLeast(1), null
                    )
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
                    // Mark finished here (not in the ViewModel) so it also
                    // happens when the book ends with the app closed.
                    val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (uri != null) {
                        serviceScope.launch(Dispatchers.IO) {
                            bookDao.updateStatus(uri, BookStatus.FINISHED)
                        }
                    }
                }
            }
        })

        // Presents chapter-scoped duration/position to the MediaSession;
        // without chapters the overrides fall through to absolute values.
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

        mediaSession = MediaLibrarySession.Builder(this, wrappedPlayer, LibrarySessionCallback())
            .setSessionActivity(pendingIntent)
            .setMediaButtonPreferences(
                ImmutableList.of(
                    // [ch prev][skip back][play][skip fwd][ch next]; chapter buttons
                    // fall back to overflow (Android Auto's secondary strip)
                    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setDisplayName("Previous chapter")
                        .setSessionCommand(CHAPTER_PREV)
                        .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                        .setDisplayName("Skip back")
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_PLAY)
                        .setDisplayName("Play / Pause")
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .setSlots(CommandButton.SLOT_CENTRAL)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                        .setDisplayName("Skip forward")
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setDisplayName("Next chapter")
                        .setSessionCommand(CHAPTER_NEXT)
                        .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                        .build()
                )
            )
            .build()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SET_SKIP_DURATION)
                .add(SEEK_ABSOLUTE)
                .add(SAVE_POSITION)
                .add(CHAPTER_PREV)
                .add(CHAPTER_NEXT)
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
                SAVE_POSITION.customAction -> saveCurrentPosition()
                CHAPTER_PREV.customAction -> seekToChapterPrev()
                CHAPTER_NEXT.customAction -> seekToChapterNext()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_MEDIA_ID) {
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val all = browseChildren()
                val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
                val items = all.subList(from, (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt())
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == ROOT_MEDIA_ID) {
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            }
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                val item = browseChildren().firstOrNull { it.mediaId == mediaId }
                future.set(
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            }
            return future
        }

        // Car browse ids get rebuilt into the full book item; phone items
        // (default mediaId) fall through to the default behavior.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requestedId = mediaItems.singleOrNull()?.mediaId ?: ""
            if (requestedId == BOOK_MEDIA_ID || requestedId.startsWith(CHAPTER_MEDIA_ID_PREFIX)) {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                serviceScope.launch {
                    val resolved = resolveBrowseSelection(requestedId)
                    if (resolved != null) future.set(resolved)
                    else future.setException(UnsupportedOperationException("No active book"))
                }
                return future
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        // Cold play with an empty player: resume the persisted active book
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val resolved = resolveResumption(seedPauseState = isForPlayback)
                if (resolved != null) future.set(resolved)
                else future.setException(UnsupportedOperationException("No book to resume"))
            }
            return future
        }
    }

    // ---------------------------------------------------------------------
    // Android Auto browse tree (flat chapter list of the active book)
    // ---------------------------------------------------------------------

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_MEDIA_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Chapters")
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build()
        )
        .build()

    @OptIn(UnstableApi::class)
    private fun playableRow(
        mediaId: String,
        title: String,
        subtitle: String?,
        durationMs: Long?,
        cover: ByteArray?,
        mediaType: Int,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkData(cover, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .setDurationMs(durationMs?.takeIf { it > 0 })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(mediaType)
                .build()
        )
        .build()

    // Rows for the live book, else the persisted active book; never the library
    private suspend fun browseChildren(): List<MediaItem> {
        val liveItem = underlyingPlayer?.currentMediaItem
        if (liveItem?.localConfiguration != null) {
            val chapters = chaptersFor(liveItem)
            val bookTitle = liveItem.mediaMetadata.title?.toString()
            val cover = liveItem.mediaMetadata.artworkData
            if (chapters.isEmpty()) {
                val duration = underlyingPlayer?.duration?.takeIf { it != C.TIME_UNSET }
                return listOf(
                    playableRow(
                        BOOK_MEDIA_ID, bookTitle ?: "", liveItem.mediaMetadata.artist?.toString(),
                        duration, cover, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
                    )
                )
            }
            return chapters.mapIndexed { index, ch ->
                playableRow(
                    "$CHAPTER_MEDIA_ID_PREFIX$index", ch.title, bookTitle,
                    ch.endMs - ch.startMs, cover, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
                )
            }
        }

        val (book, chapters) = activeBookFromDb() ?: return emptyList()
        if (chapters.isEmpty()) {
            return listOf(
                playableRow(
                    BOOK_MEDIA_ID, book.title, book.author,
                    book.duration, book.coverArt, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
                )
            )
        }
        return chapters.map { ch ->
            playableRow(
                "$CHAPTER_MEDIA_ID_PREFIX${ch.index}", ch.title, book.title,
                ch.endTimeMs - ch.startTimeMs, book.coverArt, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
            )
        }
    }

    private suspend fun activeBookFromDb(): Pair<BookEntity, List<ChapterEntity>>? {
        val uri = preferencesRepository.activeBookUri.first() ?: return null
        val book = bookDao.getBookByUri(uri) ?: return null
        return book to bookDao.getChaptersForBook(book.id)
    }

    private fun chapterIndexAt(chapters: List<ChapterEntity>, positionMs: Long): Int =
        chapters.indexOfLast { it.startTimeMs <= positionMs }.coerceAtLeast(0)

    // Seed book state before media3 applies resolved items; the immediate
    // play() would otherwise race the async pause-timestamp restore.
    private fun primeBookState(uri: String, pausedAt: Long?) {
        if (uri != lastBookUri) {
            lastBookUri = uri
            lastChapterIndex = -1
        }
        pausedAtWallClockMs = pausedAt
    }

    // A row tapped in the car → the full book item at the chapter start
    private suspend fun resolveBrowseSelection(mediaId: String): MediaSession.MediaItemsWithStartPosition? {
        val liveItem = underlyingPlayer?.currentMediaItem
        val liveConfig = liveItem?.localConfiguration
        if (liveItem != null && liveConfig != null) {
            val chapters = chaptersFor(liveItem)
            val startMs: Long
            if (mediaId == BOOK_MEDIA_ID || chapters.isEmpty()) {
                // Continue the live book; keep any pending recap-rewind state
                startMs = underlyingPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
            } else {
                val index = mediaId.removePrefix(CHAPTER_MEDIA_ID_PREFIX).toIntOrNull() ?: return null
                startMs = chapters.getOrNull(index)?.startMs ?: return null
                // Explicit chapter choice: no recap rewind on the play that follows
                primeBookState(liveConfig.uri.toString(), pausedAt = null)
            }
            return MediaSession.MediaItemsWithStartPosition(listOf(liveItem), 0, startMs)
        }

        val (book, chapters) = activeBookFromDb() ?: return null
        val chapterIndex: Int
        val startMs: Long
        val pausedAt: Long?
        if (mediaId == BOOK_MEDIA_ID || chapters.isEmpty()) {
            // The whole-book row means "continue": recap rewind applies
            chapterIndex = chapterIndexAt(chapters, book.lastPosition)
            startMs = book.lastPosition
            pausedAt = book.lastPausedAt
        } else {
            val index = mediaId.removePrefix(CHAPTER_MEDIA_ID_PREFIX).toIntOrNull() ?: return null
            val chapter = chapters.firstOrNull { it.index == index } ?: return null
            chapterIndex = index
            startMs = chapter.startTimeMs
            pausedAt = null
        }
        val item = BookMediaItem.from(book, chapters, chapterIndex)
        primeBookState(book.uri, pausedAt)
        return MediaSession.MediaItemsWithStartPosition(listOf(item), 0, startMs)
    }

    private suspend fun resolveResumption(seedPauseState: Boolean): MediaSession.MediaItemsWithStartPosition? {
        val (book, chapters) = activeBookFromDb() ?: return null
        val item = BookMediaItem.from(book, chapters, chapterIndexAt(chapters, book.lastPosition))
        if (seedPauseState) {
            // Recap rewind applies exactly like a phone resume
            primeBookState(book.uri, book.lastPausedAt)
        }
        return MediaSession.MediaItemsWithStartPosition(listOf(item), 0, book.lastPosition)
    }

    // Mirrors the phone's chapterBack/chapterForward
    private fun seekToChapterPrev() {
        val player = underlyingPlayer ?: return
        val chapters = chaptersFor(player.currentMediaItem)
        if (chapters.isEmpty()) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val idx = chapters.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
        // Well into a chapter, "previous" first returns to its start
        val target = if (pos - chapters[idx].startMs > 2_000L) idx else (idx - 1).coerceAtLeast(0)
        player.seekTo(chapters[target].startMs)
    }

    private fun seekToChapterNext() {
        val player = underlyingPlayer ?: return
        val chapters = chaptersFor(player.currentMediaItem)
        if (chapters.isEmpty()) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val idx = chapters.indexOfLast { it.startMs <= pos }
        val next = chapters.getOrNull(idx + 1) ?: return
        player.seekTo(next.startMs)
    }

    // Publishes the chapter for the given position into the MediaItem's
    // subtitle/extras via replaceMediaItem, which notifies connected controllers.
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

    // Catches natural chapter crossings during playback; seek-driven crossings
    // are handled by onPositionDiscontinuity.
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
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