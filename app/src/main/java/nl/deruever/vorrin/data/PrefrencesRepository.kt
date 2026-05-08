package nl.deruever.vorrin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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