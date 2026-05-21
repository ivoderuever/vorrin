package nl.deruever.vorrin.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.utils.formatDuration

@Composable
internal fun PlayerTopBar(onBackClick: () -> Unit) {
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
internal fun PlayerCover(
    bookId: String,
    title: String,
    coverArt: ByteArray? = null,
    modifier: Modifier = Modifier
) {
    val stableCoverArt = remember(bookId) { coverArt }

    AsyncImage(
        model = stableCoverArt, // Pass the stable reference to Coil
        contentDescription = "Cover art for $title",
        modifier = modifier
            .clip(RoundedCornerShape(24.dp)),
        contentScale = ContentScale.Crop,
        fallback = painterResource(android.R.drawable.ic_menu_gallery),
        error = painterResource(android.R.drawable.ic_menu_gallery)
    )
}

@Composable
internal fun PlayerBookInfo(book: Audiobook, currentPositionMs: Long, duration: Long) {
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