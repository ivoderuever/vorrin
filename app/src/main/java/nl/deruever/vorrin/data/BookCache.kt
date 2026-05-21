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

    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return instance ?: SimpleCache(
            File(context.cacheDir, "book_media_cache"),
            LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { instance = it }
    }

    fun release() {
        instance?.release()
        instance = null
    }
}
