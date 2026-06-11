// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
package nl.deruever.vorrin.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.data.db.BookEntity
import nl.deruever.vorrin.data.db.ChapterEntity

// Single builder of the whole-book MediaItem (URI + metadata + chapter extras)
// shared by the ViewModel and the service, so the two cannot drift apart.
object BookMediaItem {

    fun from(book: Audiobook, currentChapterIndex: Int): MediaItem = build(
        uri = book.uri,
        title = book.title,
        author = book.author,
        coverArt = book.coverArt,
        chapterTitles = book.chapters.map { it.title },
        chapterStarts = book.chapters.map { it.startTimeMs },
        chapterEnds = book.chapters.map { it.endTimeMs },
        currentChapterIndex = currentChapterIndex,
    )

    fun from(book: BookEntity, chapters: List<ChapterEntity>, currentChapterIndex: Int): MediaItem = build(
        uri = book.uri,
        title = book.title,
        author = book.author,
        coverArt = book.coverArt,
        chapterTitles = chapters.map { it.title },
        chapterStarts = chapters.map { it.startTimeMs },
        chapterEnds = chapters.map { it.endTimeMs },
        currentChapterIndex = currentChapterIndex,
    )

    private fun build(
        uri: String,
        title: String,
        author: String,
        coverArt: ByteArray?,
        chapterTitles: List<String>,
        chapterStarts: List<Long>,
        chapterEnds: List<Long>,
        currentChapterIndex: Int,
    ): MediaItem {
        // Chapter data lives in MediaItem extras so it survives ViewModel death
        val extras = Bundle().apply {
            if (chapterTitles.isNotEmpty()) {
                putStringArray(AudiobookService.EXTRA_CHAPTER_TITLES, chapterTitles.toTypedArray())
                putLongArray(AudiobookService.EXTRA_CHAPTER_START_TIMES, chapterStarts.toLongArray())
                putLongArray(AudiobookService.EXTRA_CHAPTER_END_TIMES, chapterEnds.toLongArray())
                putInt(AudiobookService.EXTRA_CURRENT_CHAPTER_INDEX, currentChapterIndex)
            }
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(author)
            .apply {
                chapterTitles.getOrNull(currentChapterIndex)?.let { setSubtitle(it) }
            }
            .setArtworkData(coverArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaMetadata(metadata)
            .build()
    }
}
