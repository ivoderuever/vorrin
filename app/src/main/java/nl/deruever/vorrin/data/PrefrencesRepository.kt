package nl.deruever.vorrin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vorrin_prefs")

class PreferencesRepository(private val context: Context) {

    companion object {
        val FOLDER_URI_KEY = stringPreferencesKey("folder_uri")
        val ACTIVE_BOOK_URI_KEY = stringPreferencesKey("active_book_uri")
        val SKIP_DURATION_KEY = intPreferencesKey("skip_duration_seconds")
        val PLAYBACK_SPEED_KEY = floatPreferencesKey("playback_speed")
    }

    val folderUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[FOLDER_URI_KEY]
    }

    val activeBookUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ACTIVE_BOOK_URI_KEY]
    }

    private fun bookPositionKey(uri: String) = stringPreferencesKey("position_${uri.hashCode()}")


    suspend fun saveFolderUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[FOLDER_URI_KEY] = uri
        }
    }

    suspend fun saveActiveBookUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_BOOK_URI_KEY] = uri
        }
    }

    suspend fun clearFolderUri() {
        context.dataStore.edit { prefs ->
            prefs.remove(FOLDER_URI_KEY)
        }
    }

    suspend fun getSkipDuration(): Int {
        return context.dataStore.data.map { prefs ->
            prefs[SKIP_DURATION_KEY] ?: 15
        }.first()
    }

    suspend fun saveSkipDuration(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[SKIP_DURATION_KEY] = seconds
        }
    }

    suspend fun getPlaybackSpeed(): Float {
        return context.dataStore.data.map { prefs ->
            prefs[PLAYBACK_SPEED_KEY] ?: 1.0f
        }.first()
    }

    suspend fun savePlaybackSpeed(speed: Float) {
        context.dataStore.edit { prefs ->
            prefs[PLAYBACK_SPEED_KEY] = speed
        }
    }

    suspend fun saveBookPosition(uri: String, positionMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[bookPositionKey(uri)] = positionMs.toString()
        }
    }

    suspend fun getBookPosition(uri: String): Long {
        return context.dataStore.data.map { prefs ->
            prefs[bookPositionKey(uri)]?.toLongOrNull() ?: 0L
        }.first()
    }
}