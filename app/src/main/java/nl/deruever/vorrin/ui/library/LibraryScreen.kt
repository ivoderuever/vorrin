package nl.deruever.vorrin.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import nl.deruever.vorrin.data.Audiobook
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.deruever.vorrin.ui.components.FolderPickButton

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(onBookClick: (Audiobook) -> Unit) {
    val viewModel: LibraryViewModel = viewModel()
    val books by viewModel.books.collectAsState()
    val folderUri by viewModel.folderUri.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val activeBook by viewModel.activeBook.collectAsState()
    val layoutDirection = LocalLayoutDirection.current
    var isPlaying by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (folderUri != null && books.isNotEmpty()) {
                TopAppBar(title = { Text("Vorrin") })
            }
        },
        bottomBar = {
            val bookToShow = activeBook ?: books.firstOrNull { !it.isFinished }
            if (bookToShow != null) {
                MiniPlayer(
                    book = bookToShow,
                    isPlaying = isPlaying,
                    onPlayPause = { isPlaying = !isPlaying },
                    onClick = { onBookClick(bookToShow) }
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading -> LoadingIndicator()
                folderUri == null -> IntroApp(onFolderPicked = { viewModel.onFolderPicked(it) })
                books.isEmpty() -> NoBooks(onFolderPicked = { viewModel.onFolderPicked(it) })
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(books) { book ->
                        BookItem(book = book, onClick = { onBookClick(book) })
                    }
                }
            }
        }
    }
}

@Composable
fun BookItem(book: Audiobook, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cover art placeholder
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {}

            // Book info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
//                    overflow = TextOverflow.Ellipsis
                    modifier = Modifier.basicMarquee()

                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (book.progressPercent != 0 && !book.isFinished) {
                Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { book.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            // Badge
            if (book.isFinished) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialShapes.Sunny.toShape())
                        .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Finished",
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else if (book.progressPercent != 0) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${book.progressPercent}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    book: Audiobook,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 58.dp, top = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cover art
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
            ) {}

            // Title and author
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Play/pause
            FilledTonalIconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}

@Composable
private fun IntroApp(onFolderPicked: (Uri) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Welcome to Vorrin",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Your minimal audiobook player.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Surface(
                shape = MaterialTheme.shapes.large, // Use standard M3 shapes instead of hardcoded dp
                color = MaterialTheme.colorScheme.surfaceContainerHigh // or secondaryContainer
            ) {
                Text(
                    text = "Select the folder where you keep your .m4b files to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant // or onSecondaryContainer
                )
            }

            FolderPickButton(
                onFolderPicked = onFolderPicked,
                buttonText = "Select Audiobook Folder"
            )
        }
    }
}

@Composable
private fun NoBooks(onFolderPicked: (Uri) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "No .m4b files found",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Make sure your folder contains .m4b audiobook files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FolderPickButton(onFolderPicked = onFolderPicked, buttonText = "Change audiobook folder")
        }
    }
}