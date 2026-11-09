package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.data.settings.AppSettings
import com.footballai.app.data.settings.ThemeMode
import com.footballai.app.data.sync.SyncScheduler
import com.footballai.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val providerName: String? = null,
    val historicalMatches: Int = 0,
    val storedPredictions: Int = 0,
    val appVersion: String = "0.1.0",
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            val provider = container.providerRegistry.activeProvider()
            val (_, historical, predictions) = container.matchRepository.countsTriple()
            _state.update {
                it.copy(
                    providerName = provider?.info?.displayName,
                    historicalMatches = historical,
                    storedPredictions = predictions,
                )
            }
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { container.settingsRepository.setLanguage(language) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    fun setLivePolling(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setLivePolling(enabled) }
    }

    fun setBackgroundSync(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setBackgroundSync(enabled)
            if (enabled) {
                SyncScheduler.schedulePeriodicSync(container.contextForWork())
            } else {
                SyncScheduler.cancelPeriodicSync(container.contextForWork())
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            container.database.clearAllTables()
            container.ratingsRepository.invalidate()
            _state.update { it.copy(historicalMatches = 0, storedPredictions = 0) }
        }
    }
}
