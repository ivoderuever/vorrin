// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
package nl.deruever.vorrin.utils

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    val secStr = seconds.toString().padStart(2, '0')
    val minStr = minutes.toString().padStart(2, '0')
    
    return if (hours > 0) {
        "$hours:$minStr:$secStr"
    } else {
        "$minStr:$secStr"
    }
}
