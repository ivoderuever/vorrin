package nl.deruever.vorrin.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

private fun Float.formatSpeed(): String {
    val whole = if (this % 1f == 0f) toInt().toString() else toString()
    return "${whole}×"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedSheet(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val listState = rememberLazyListState()

    LaunchedEffect(currentSpeed) {
        val index = SPEED_OPTIONS.indexOf(currentSpeed).coerceAtLeast(0)
        listState.scrollToItem(index)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = "Playback Speed",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(SPEED_OPTIONS) { speed ->
                val isSelected = speed == currentSpeed
                ListItem(
                    headlineContent = {
                        Text(
                            text = speed.formatSpeed(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingContent = if (isSelected) ({
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }) else null,
                    modifier = Modifier.clickable {
                        onSelect(speed)
                        onDismiss()
                    }
                )
            }
        }
    }
}
