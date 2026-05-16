package nl.deruever.vorrin.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults // Make sure to add this import
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerControls(
    isPlaying: Boolean,
    isReady: Boolean,
    skipDurationSeconds: Int,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onChapterBack: () -> Unit,
    onChapterForward: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val controlHeight = 84.dp

            FilledTonalIconButton(
                onClick = onSkipBack,
                enabled = isReady,
                modifier = Modifier
                    .weight(1f)
                    .height(controlHeight),
                shape = RoundedCornerShape(20.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Replay, "Skip back", Modifier.size(24.dp))
                    Text("${skipDurationSeconds}s", style = MaterialTheme.typography.labelSmall)
                }
            }

            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .weight(1.8f)
                    .height(controlHeight),
                shape = RoundedCornerShape(20.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                if (!isReady) {
                    LoadingIndicator(
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        Modifier.size(32.dp)
                    )
                }
            }

            FilledTonalIconButton(
                onClick = onSkipForward,
                enabled = isReady,
                modifier = Modifier
                    .weight(1f)
                    .height(controlHeight),
                shape = RoundedCornerShape(20.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Replay, "Next chapter",
                        Modifier
                            .size(24.dp)
                            .graphicsLayer(scaleX = -1f)
                    )
                    Text("${skipDurationSeconds}s", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            val controlHeight = 56.dp

            FilledTonalIconButton(
                onClick = onChapterBack,
                enabled = isReady,
                modifier = Modifier
                    .weight(1f)
                    .height(controlHeight),
                shape = RoundedCornerShape(25.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Rounded.SkipPrevious, "Skip back", Modifier.size(24.dp))
            }

            FilledTonalIconButton(
                onClick = onChapterForward,
                enabled = isReady,
                modifier = Modifier
                    .weight(1f)
                    .height(controlHeight),
                shape = RoundedCornerShape(25.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(Icons.Rounded.SkipNext, "Next chapter")
            }
        }
    }
}