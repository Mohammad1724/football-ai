package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.data.settings.AppSettings
import com.footballai.app.data.settings.ThemeMode
import com.footballai.app.data.sync.SyncScheduler
import com.footballai.app.di.AppContainer
import com.footballai.app.core.Outcome
import com.footballai.app.data.sync.SyncTrigger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val providerName: String? = null,
    val fixturesCount: Int = 0,
    val historicalMatches: Int = 0,
    val storedPredictions: Int = 0,
    val appVersion: String = "0.4.1",
    val lastError: AppError? = null,
    val isClearing: Boolean = false,
    /** True while the manual sync started from this screen is running. */
    val isSyncing: Boolean = false,
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    val syncWarnings: List<String> = emptyList(),
    val justSynced: Int = 0,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private var syncJob: Job? = null

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        refreshCounts()
    }

    /**
     * The counts and the source name live here now that the home screen is gone: this is
     * the one place that answers "what do I have, and where did it come from".
     */
    private fun refreshCounts() {
        viewModelScope.launch {
            val provider = container.providerRegistry.activeProvider()
            val (fixtures, historical, predictions) = container.matchRepository.countsTriple()
            _state.update {
                it.copy(
                    providerName = provider?.info?.displayName,
                    fixturesCount = fixtures,
                    historicalMatches = historical,
                    storedPredictions = predictions,
                )
            }
        }
    }

    /** The same refresh path the rest of the app uses; no second implementation. */
    fun syncNow() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, lastError = null, syncDone = 0, syncTotal = 0) }
            try {
                val result = container.autoSyncCoordinator.syncNow(SyncTrigger.MANUAL) { done, total ->
                    _state.update { it.copy(syncDone = done, syncTotal = total) }
                }
                when (result) {
                    is Outcome.Failure -> _state.update { it.copy(lastError = result.error) }
                    is Outcome.Success -> _state.update {
                        it.copy(
                            lastError = null,
                            syncWarnings = result.value.warnings,
                            justSynced = result.value.added,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.update {
                    it.copy(lastError = AppError(ErrorCode.UNKNOWN, detail = error.message))
                }
            } finally {
                _state.update { it.copy(isSyncing = false) }
                refreshCounts()
            }
        }
    }

    /** Stops a long sync; free providers space their requests a few seconds apart. */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        _state.update { it.copy(isSyncing = false, syncDone = 0, syncTotal = 0) }
    }

    /** Stops following a team; its matches simply go back to their normal place. */
    fun unfollowTeam(name: String) {
        viewModelScope.launch { container.settingsRepository.toggleFavouriteTeam(name, false) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { container.settingsRepository.setLanguage(language) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    fun setDailyAnalysis(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDailyAnalysis(enabled) }
    }

    fun setLivePolling(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setLivePolling(enabled) }
    }

    /**
     * Turning automatic refreshing back on must take effect without a restart, so the
     * coordinator's last attempt is forgotten; the next screen start syncs again.
     */
    fun setAutoRefreshOnOpen(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAutoRefreshOnOpen(enabled)
            if (enabled) container.autoSyncCoordinator.forgetAttempt()
        }
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

    /** Sends the user back to the setup flow so they can change provider or imports. */
    fun restartOnboarding() {
        viewModelScope.launch { container.settingsRepository.resetOnboarding() }
    }

    fun clearAllData() {
        if (_state.value.isClearing) return
        _state.update { it.copy(isClearing = true, lastError = null) }
        viewModelScope.launch {
            try {
                // Disable future background work before clearing. Room serializes the
                // clear transaction with database writes already in progress.
                container.settingsRepository.setBackgroundSync(false)
                SyncScheduler.cancelPeriodicSync(container.contextForWork())
                container.dailyAnalysisRepository.clearLocalData()
                container.ratingsRepository.invalidate()
                _state.update { it.copy(historicalMatches = 0, storedPredictions = 0) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(lastError = AppError(ErrorCode.STORAGE_ERROR, cause = error)) }
            } finally {
                _state.update { it.copy(isClearing = false) }
            }
        }
    }
}
