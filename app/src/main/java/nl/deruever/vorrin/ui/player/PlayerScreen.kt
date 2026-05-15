package nl.deruever.vorrin.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import nl.deruever.vorrin.data.Audiobook

@Composable
fun PlayerScreen(
    book: Audiobook,
    playerViewModel: PlayerViewModel,
    onBackClick: () -> Unit = {}
) {
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val currentPositionMs by playerViewModel.currentPositionMs.collectAsState()
    val isReady by playerViewModel.isReady.collectAsState()
    val duration by playerViewModel.duration.collectAsState()

    var isChapterSheetOpen by remember { mutableStateOf(false) }
    var isSkipSheetOpen by remember { mutableStateOf(false) }
    var isSpeedSheetOpen by remember { mutableStateOf(false) }
    val skipDurationSeconds by playerViewModel.skipDurationSeconds.collectAsState()
    val playbackSpeed by playerViewModel.playbackSpeed.collectAsState()

    val currentChapter = book.chapters.lastOrNull { it.startTimeMs <= currentPositionMs }
        ?: book.chapters.firstOrNull()

    val activity = LocalContext.current as? Activity
    LaunchedEffect(book.uri) {
        playerViewModel.connect(book)
    }
    DisposableEffect(book.uri) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(layoutDirection) + 24.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 24.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerTopBar(onBackClick = {
                playerViewModel.savePosition()
                onBackClick()
            })

            Spacer(modifier = Modifier.weight(0.5f))

            PlayerCover(
                title = book.title,
                coverArt = book.coverArt,
                modifier = Modifier
                    .weight(10f, fill = false)
                    .size(280.dp)
                    .aspectRatio(1f)
            )

            Spacer(modifier = Modifier.weight(1f))

            PlayerBookInfo(book = book, currentPositionMs = currentPositionMs, duration = duration)

            Spacer(modifier = Modifier.weight(1.5f))

            PlayerChapterBar(
                currentChapter = currentChapter,
                onClick = { isChapterSheetOpen = true }
            )

            Spacer(modifier = Modifier.height(4.dp))

            PlayerProgress(
                currentChapter = currentChapter,
                currentPositionMs = currentPositionMs,
                isPlaying = isPlaying,
                onSeek = { playerViewModel.seekTo(it) }
            )

            Spacer(modifier = Modifier.weight(1.5f))

            PlayerControls(
                isPlaying = isPlaying,
                isReady = isReady,
                skipDurationSeconds = skipDurationSeconds,
                onPlayPause = { playerViewModel.playPause() },
                onSkipBack = { playerViewModel.skipBack(skipDurationSeconds) },
                onSkipForward = { playerViewModel.skipForward(skipDurationSeconds) },
                onChapterBack = { playerViewModel.chapterBack() },
                onChapterForward = { playerViewModel.chapterForward() }
            )

            Spacer(modifier = Modifier.weight(1f))

            SpeedAndSkipSheetButtons(
                skipDurationSeconds = skipDurationSeconds,
                playbackSpeed = playbackSpeed,
                onSpeedClick = { isSpeedSheetOpen = true },
                onSkipDurationClick = { isSkipSheetOpen = true }
            )
        }
    }

    if (isChapterSheetOpen) {
        ChapterSheet(
            chapters = book.chapters,
            currentChapter = currentChapter,
            onChapterClick = { chapter ->
                playerViewModel.seekTo(chapter.startTimeMs)
                isChapterSheetOpen = false
            },
            onDismiss = { isChapterSheetOpen = false }
        )
    }

    if (isSkipSheetOpen) {
        SkipDurationSheet(
            currentSeconds = skipDurationSeconds,
            onSelect = { playerViewModel.setSkipDuration(it) },
            onDismiss = { isSkipSheetOpen = false }
        )
    }

    if (isSpeedSheetOpen) {
        SpeedSheet(
            currentSpeed = playbackSpeed,
            onSelect = { playerViewModel.setPlaybackSpeed(it) },
            onDismiss = { isSpeedSheetOpen = false }
        )
    }
}