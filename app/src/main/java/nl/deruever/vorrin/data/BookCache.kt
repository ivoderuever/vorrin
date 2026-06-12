// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
package nl.deruever.vorrin.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object BookCache {

    // v2: playback no longer writes whole books into the cache, only the
    // prewarmed head/tail of each file lives here (~4.5MB per book).
    private const val CACHE_DIR = "book_media_cache_v2"
    private const val LEGACY_CACHE_DIR = "book_media_cache"
    private const val MAX_CACHE_BYTES = 200L * 1024 * 1024

    private var instance: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache {
        instance?.let { return it }

        val appContext = context.applicationContext
        val databaseProvider = StandaloneDatabaseProvider(appContext)

        // One-time cleanup of the old cache, which grew to its full 500MB
        // cap because playback used to write entire books into it.
        val legacyDir = File(appContext.cacheDir, LEGACY_CACHE_DIR)
        if (legacyDir.exists()) {
            SimpleCache.delete(legacyDir, databaseProvider)
        }

        return SimpleCache(
            File(appContext.cacheDir, CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            databaseProvider
        ).also { instance = it }
    }

    @Synchronized
    fun release() {
        instance?.release()
        instance = null
    }
}
