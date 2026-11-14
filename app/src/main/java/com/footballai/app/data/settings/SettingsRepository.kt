package com.footballai.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** All non-secret user preferences, persisted with DataStore. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            language = AppLanguage.fromTag(preferences[KEY_LANGUAGE] ?: AppLanguage.SYSTEM.tag),
            themeMode = runCatching {
                ThemeMode.valueOf(preferences[KEY_THEME] ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM),
            activeProviderId = preferences[KEY_ACTIVE_PROVIDER].orEmpty(),
            followedCompetitionKeys = preferences[KEY_FOLLOWED] ?: emptySet(),
            dailyAnalysisEnabled = preferences[KEY_DAILY_ANALYSIS] ?: true,
            livePollingEnabled = !(preferences[KEY_DAILY_ANALYSIS] ?: true) && (preferences[KEY_LIVE_POLLING] ?: false),
            backgroundSyncEnabled = preferences[KEY_BACKGROUND_SYNC] ?: true,
            autoRefreshOnOpen = preferences[KEY_AUTO_REFRESH] ?: true,
            autoPredictEnabled = preferences[KEY_AUTO_PREDICT] ?: true,
            dataSaverEnabled = preferences[KEY_DATA_SAVER] ?: false,
            lastHistoricalImportEpochMs = preferences[KEY_LAST_IMPORT],
            lastSyncEpochMs = preferences[KEY_LAST_SYNC],
            onboarded = preferences[KEY_ONBOARDED] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLanguage(language: AppLanguage) = edit { it[KEY_LANGUAGE] = language.tag }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME] = mode.name }

    suspend fun setActiveProvider(providerId: String) = edit { it[KEY_ACTIVE_PROVIDER] = providerId }

    suspend fun setFollowedCompetitions(keys: Set<String>) = edit { it[KEY_FOLLOWED] = keys }

    suspend fun toggleFollowedCompetition(key: String, followed: Boolean) = edit { preferences ->
        val current = preferences[KEY_FOLLOWED]?.toMutableSet() ?: mutableSetOf()
        if (followed) current.add(key) else current.remove(key)
        preferences[KEY_FOLLOWED] = current
    }

    suspend fun setDailyAnalysis(enabled: Boolean) = edit { it[KEY_DAILY_ANALYSIS] = enabled }

    suspend fun setLivePolling(enabled: Boolean) = edit { it[KEY_LIVE_POLLING] = enabled }

    suspend fun setBackgroundSync(enabled: Boolean) = edit { it[KEY_BACKGROUND_SYNC] = enabled }

    suspend fun setAutoRefreshOnOpen(enabled: Boolean) = edit { it[KEY_AUTO_REFRESH] = enabled }

    suspend fun setAutoPredict(enabled: Boolean) = edit { it[KEY_AUTO_PREDICT] = enabled }

    suspend fun setDataSaver(enabled: Boolean) = edit { it[KEY_DATA_SAVER] = enabled }

    suspend fun setLastHistoricalImport(epochMs: Long) = edit { it[KEY_LAST_IMPORT] = epochMs }

    suspend fun setLastSync(epochMs: Long) = edit { it[KEY_LAST_SYNC] = epochMs }

    suspend fun setOnboarded() = edit { it[KEY_ONBOARDED] = true }

    /** Re-opens the first-run flow, e.g. after the user cleared the configured sources. */
    suspend fun resetOnboarding() = edit { it[KEY_ONBOARDED] = false }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        const val PREFERENCES_NAME = "football_ai_settings"

        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        private val KEY_FOLLOWED = stringSetPreferencesKey("followed_competitions")
        private val KEY_DAILY_ANALYSIS = booleanPreferencesKey("daily_analysis")
        private val KEY_LIVE_POLLING = booleanPreferencesKey("live_polling")
        private val KEY_BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        private val KEY_AUTO_REFRESH = booleanPreferencesKey("auto_refresh_on_open")
        private val KEY_AUTO_PREDICT = booleanPreferencesKey("auto_predict")
        private val KEY_DATA_SAVER = booleanPreferencesKey("data_saver")
        private val KEY_LAST_IMPORT = longPreferencesKey("last_historical_import")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync")
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
    }
}
