// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
package nl.deruever.vorrin.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun SpeedAndSkipSheetButtons(
    onSpeedClick: () -> Unit,
    onSkipDurationClick: () -> Unit,
    playbackSpeed: Float,
    skipDurationSeconds: Int,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SpeedSheet(onSpeedClick, playbackSpeed)
        SkipSheet(onSkipDurationClick, skipDurationSeconds)
    }
}

@Composable
private fun RowScope.SpeedSheet(
    onSpeedClick: () -> Unit,
    playbackSpeed: Float,
) {
    AssistChip(
        onClick = onSpeedClick,
        label = {
            Text(
                text = "${if (playbackSpeed % 1f == 0f) playbackSpeed.toInt() else playbackSpeed}×  Speed",
                style = MaterialTheme.typography.labelLarge
            )
        },
    )
}

@Composable
private fun RowScope.SkipSheet(
    onSkipDurationClick: () -> Unit,
    skipDurationSeconds: Int,
) {
    AssistChip(
        onClick = onSkipDurationClick,
        label = {
            Text(
                text = "${skipDurationSeconds}s  Skip",
                style = MaterialTheme.typography.labelLarge
            )
        },
    )
}