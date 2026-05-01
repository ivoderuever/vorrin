package nl.deruever.vorrin.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.FakeData
import nl.deruever.vorrin.utils.formatDuration
import androidx.compose.animation.core.animateFloatAsState
import nl.deruever.vorrin.data.Chapter

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(
    book: Audiobook = FakeData.books[0],
    onBackClick: () -> Unit = {}
) {
    // Fake state for now
    var isPlaying by remember { mutableStateOf(false) }
    var isChapterSheetOpen by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(book.lastPosition) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var skipDurationSeconds by remember { mutableIntStateOf(30) }

    val currentChapter = book.chapters.lastOrNull { it.startTimeMs <= currentPositionMs }
        ?: book.chapters.firstOrNull()

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
            PlayerTopBar(onBackClick = onBackClick)
            Spacer(modifier = Modifier.height(24.dp))
            PlayerCover()
            Spacer(modifier = Modifier.height(30.dp))
            PlayerBookInfo(book = book)
            Spacer(modifier = Modifier.height(32.dp))
            PlayerProgress(
                currentChapter = currentChapter,
                currentPositionMs = currentPositionMs,
                isPlaying = isPlaying
            )
        }
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
private fun PlayerCover() {
    Surface(
        modifier = Modifier.size(280.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {}
}

@Composable
private fun PlayerBookInfo(book: Audiobook) {
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
        text = "${book.progressPercent}% - ${formatDuration(book.timeLeft)} remaining",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerProgress(
    currentChapter: Chapter?,
    currentPositionMs: Long,
    isPlaying: Boolean
) {
    val chapterProgress = if (currentChapter != null && currentChapter.durationMs > 0) {
        ((currentPositionMs - currentChapter.startTimeMs).toFloat() / currentChapter.durationMs).coerceIn(0f, 1f)
    } else 0f

    val chapterElapsed = if (currentChapter != null) currentPositionMs - currentChapter.startTimeMs else 0L
    val chapterRemaining = if (currentChapter != null) currentChapter.endTimeMs - currentPositionMs else 0L

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        label = "waveAmplitude"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        LinearWavyProgressIndicator(
            progress = { chapterProgress },
            modifier = Modifier.fillMaxWidth(),
            amplitude = { animatedAmplitude },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
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