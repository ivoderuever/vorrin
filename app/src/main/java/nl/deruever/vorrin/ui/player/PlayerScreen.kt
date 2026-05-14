package nl.deruever.vorrin.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.Chapter
import nl.deruever.vorrin.utils.formatDuration


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var skipDurationSeconds by remember { mutableIntStateOf(30) }

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
            Spacer(modifier = Modifier.height(24.dp))
            PlayerCover(book.title, book.coverArt)
            Spacer(modifier = Modifier.height(30.dp))
            PlayerBookInfo(book = book, currentPositionMs = currentPositionMs, duration = duration)
            Spacer(modifier = Modifier.height(32.dp))
            PlayerProgress(
                currentChapter = currentChapter,
                currentPositionMs = currentPositionMs,
                isPlaying = isPlaying,
                onSeek = { playerViewModel.seekTo(it) }
            )
            Spacer(modifier = Modifier.weight(1f))
            PlayerControls(
                isPlaying = isPlaying,
                isReady = isReady,
                skipDurationSeconds = skipDurationSeconds,
                playbackSpeed = playbackSpeed,
                onPlayPause = { playerViewModel.playPause() },
                onSkipBack = { playerViewModel.skipBack(skipDurationSeconds) },
                onSkipForward = { playerViewModel.skipForward(skipDurationSeconds) },
                onSpeedClick = { },
                onSkipDurationClick = { }
            )
            Spacer(modifier = Modifier.height(16.dp))
            PlayerChapterBar(
                currentChapter = currentChapter,
                onClick = { isChapterSheetOpen = true }
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
}

@Composable
private fun PlayerTopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.Rounded.ArrowBackIosNew,
                contentDescription = "Back to library"
            )
        }
    }
}

@Composable
private fun PlayerCover(title: String, coverArt: ByteArray? = null) {
    AsyncImage(
        model = coverArt,
        contentDescription = "Cover art for $title",
        modifier = Modifier
            .size(280.dp)
            .clip(RoundedCornerShape(24.dp)),
        contentScale = ContentScale.Crop,
        fallback = painterResource(android.R.drawable.ic_menu_gallery),
        error = painterResource(android.R.drawable.ic_menu_gallery)
    )
}

@Composable
private fun PlayerBookInfo(book: Audiobook, currentPositionMs: Long, duration: Long) {
    val effectiveDuration = if (duration > 0) duration else book.duration
    val timeLeft = effectiveDuration - currentPositionMs
    val progressPercent = if (effectiveDuration > 0)
        ((currentPositionMs.toFloat() / effectiveDuration) * 100).toInt()
    else 0

    Text(
        text = book.title,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = book.author,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = "$progressPercent% · ${formatDuration(timeLeft)} remaining",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerProgress(
    currentChapter: Chapter?,
    currentPositionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    
    val chapterStart = currentChapter?.startTimeMs ?: 0L
    val chapterDuration = currentChapter?.durationMs ?: 0L
    
    // Smooth progress interpolation logic
    var lastUpdateMs by remember { mutableLongStateOf(currentPositionMs) }
    var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(currentPositionMs) {
        lastUpdateMs = currentPositionMs
        lastUpdateTime = System.currentTimeMillis()
    }

    var smoothProgress by remember { mutableFloatStateOf(0f) }
    
    LaunchedEffect(isPlaying, lastUpdateMs, draggingProgress, chapterStart, chapterDuration) {
        if (draggingProgress != null) {
            smoothProgress = draggingProgress!!
        } else {
            if (isPlaying) {
                while (true) {
                    val timeSinceUpdate = System.currentTimeMillis() - lastUpdateTime
                    val estimatedPosition = lastUpdateMs + timeSinceUpdate
                    smoothProgress = if (chapterDuration > 0) {
                        ((estimatedPosition - chapterStart).toFloat() / chapterDuration).coerceIn(0f, 1f)
                    } else 0f
                    kotlinx.coroutines.delay(16)
                }
            } else {
                smoothProgress = if (chapterDuration > 0) {
                    ((lastUpdateMs - chapterStart).toFloat() / chapterDuration).coerceIn(0f, 1f)
                } else 0f
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Slider(
            value = smoothProgress,
            onValueChange = { draggingProgress = it },
            onValueChangeFinished = {
                draggingProgress?.let {
                    val targetMs = chapterStart + (it * chapterDuration).toLong()
                    onSeek(targetMs)
                    // Optimistically update interpolation anchors to prevent jump-back
                    lastUpdateMs = targetMs
                    lastUpdateTime = System.currentTimeMillis()
                }
                draggingProgress = null
            },
            track = { sliderState ->
                LinearWavyProgressIndicator(
                    progress = { sliderState.value },
                    modifier = Modifier.fillMaxWidth(),
                    amplitude = { progress -> 
                        if (isPlaying) WavyProgressIndicatorDefaults.indicatorAmplitude(progress) else 0f
                    }
                )
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val currentMs = if (draggingProgress != null) {
                chapterStart + (draggingProgress!! * chapterDuration).toLong()
            } else {
                chapterStart + (smoothProgress * chapterDuration).toLong()
            }
            
            val chapterElapsed = (currentMs - chapterStart).coerceAtLeast(0)
            val chapterRemaining = (chapterDuration - chapterElapsed).coerceAtLeast(0)

            Text(
                text = formatDuration(chapterElapsed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "-${formatDuration(chapterRemaining)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    isReady: Boolean,
    skipDurationSeconds: Int,
    playbackSpeed: Float,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedClick: () -> Unit,
    onSkipDurationClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main controls row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Skip back
            FilledTonalIconButton(
                onClick = onSkipBack,
                enabled = isReady,
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = "Skip back",
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "${skipDurationSeconds}s",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Play/pause - wider rounded rectangle like reference
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .height(64.dp)
                    .width(120.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                if (!isReady) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Skip forward
            FilledTonalIconButton(
                onClick = onSkipForward,
                enabled = isReady,
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Forward30,
                        contentDescription = "Skip forward",
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "${skipDurationSeconds}s",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Speed and skip duration pills
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AssistChip(
                onClick = onSpeedClick,
                label = {
                    Text(
                        text = "${playbackSpeed}×  Speed",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                modifier = Modifier.weight(1f)
            )
            AssistChip(
                onClick = onSkipDurationClick,
                label = {
                    Text(
                        text = "${skipDurationSeconds}s  Skip",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PlayerChapterBar(
    currentChapter: Chapter?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Current chapter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = currentChapter?.title ?: "No chapters",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ExpandLess,
                contentDescription = "Browse chapters",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    chapters: List<Chapter>,
    currentChapter: Chapter?,
    onChapterClick: (Chapter) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(chapters) { chapter ->
                val isActive = chapter.index == currentChapter?.index
                ListItem(
                    headlineContent = {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = {
                        Text(
                            text = formatDuration(chapter.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier.clickable { onChapterClick(chapter) }
                )
                HorizontalDivider()
            }
        }
    }
}