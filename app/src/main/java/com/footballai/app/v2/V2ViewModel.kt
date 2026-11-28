package com.footballai.app.v2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.v2.data.V2DataRepository
import com.footballai.app.v2.data.V2DataState
import com.footballai.app.v2.data.V2Settings
import com.footballai.app.v2.data.V2SettingsRepository
import com.footballai.app.v2.data.V2ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder of the rewritten shell. Owns the v2 settings and the mirror
 * data repository; the old app's repositories are not touched here.
 */
class V2ViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = V2SettingsRepository.create(application)

    private val dataRepository = V2DataRepository(
        context = application,
        settingsRepository = settingsRepository,
        scope = viewModelScope,
    )

    val settings: StateFlow<V2Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, V2Settings())

    val data: StateFlow<V2DataState> = dataRepository.state

    init {
        dataRepository.loadInitial()
    }

    /** Called from MainActivity.onStart: refresh only when the data is stale. */
    fun onAppStart() {
        dataRepository.refreshIfStale(settings.value.lastRefreshEpochMs)
    }

    fun refreshNow() = dataRepository.refresh()

    fun setThemeMode(mode: V2ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setLeagueVisible(slug: String, visible: Boolean) {
        viewModelScope.launch { settingsRepository.setLeagueVisible(slug, visible) }
    }

    fun setLeagueOrder(slugs: List<String>) {
        viewModelScope.launch { settingsRepository.setLeagueOrder(slugs) }
    }
}
