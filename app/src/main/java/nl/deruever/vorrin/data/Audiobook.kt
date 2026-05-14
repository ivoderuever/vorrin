package nl.deruever.vorrin.data

data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val uri: String,
    val duration: Long = 0L,
    val coverArt: ByteArray? = null,
    val chapters: List<Chapter> = emptyList(),
    val lastPosition: Long = 0L,
    val totalListened: Long = 0L,
    val status: BookStatus = BookStatus.UNREAD,
) {
    val isFinished: Boolean get() = status == BookStatus.FINISHED
    val progressPercent: Int get() = if (duration > 0) ((lastPosition.toFloat() / duration) * 100).toInt() else 0
    val timeLeft: Long get() = duration - lastPosition
    val timeListened: Long get() = lastPosition
}

data class Chapter(
    val index: Int,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
}