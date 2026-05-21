// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
package nl.deruever.vorrin.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.deruever.vorrin.data.Chapter
import nl.deruever.vorrin.utils.formatDuration

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerProgress(
    currentChapter: Chapter?,
    currentPositionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit
) {
    var draggingProgress by remember { mutableStateOf<Float?>(null) }

    val chapterStart = currentChapter?.startTimeMs ?: 0L
    val chapterDuration = currentChapter?.durationMs ?: 0L

    var lastUpdateMs by remember { mutableLongStateOf(currentPositionMs) }
    var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(currentPositionMs) {
        lastUpdateMs = currentPositionMs
        lastUpdateTime = System.currentTimeMillis()
    }

    var smoothProgress by remember { mutableFloatStateOf(0f) }

    var isWavy by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            isWavy = true
        } else {
            kotlinx.coroutines.delay(300)
            isWavy = false
        }
    }

    LaunchedEffect(isPlaying, lastUpdateMs, draggingProgress, chapterStart, chapterDuration) {
        if (draggingProgress != null) {
            smoothProgress = draggingProgress!!
        } else {
            if (isPlaying) {
                // Reset the time anchor so accumulated pause duration isn't added to the position.
                lastUpdateTime = System.currentTimeMillis()
                while (true) {
                    val timeSinceUpdate = System.currentTimeMillis() - lastUpdateTime
                    val estimatedPosition = lastUpdateMs + timeSinceUpdate
                    smoothProgress = if (chapterDuration > 0) {
                        ((estimatedPosition - chapterStart).toFloat() / chapterDuration).coerceIn(0f, 1f)
                    } else 0f
                    kotlinx.coroutines.delay(50)
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
                        if (isWavy) WavyProgressIndicatorDefaults.indicatorAmplitude(progress) else 0f
                    }
                )
            }
        )
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