// Vorrin — Copyright (C) 2026 Ivo de Ruever — Licensed under GPL-3.0
package nl.deruever.vorrin.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.deruever.vorrin.data.PreferencesRepository

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = PreferencesRepository(application)

    val rewindOnResume: StateFlow<Boolean> = preferencesRepository.rewindOnResume
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setRewindOnResume(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.saveRewindOnResume(enabled)
        }
    }
}