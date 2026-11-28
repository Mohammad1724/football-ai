package com.footballai.app.v2.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.footballai.app.v2.V2Leagues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Display theme of the rewrite: the settings screen's روز / شب / خودکار. */
enum class V2ThemeMode { DAY, NIGHT, AUTO }

data class V2Settings(
    val themeMode: V2ThemeMode = V2ThemeMode.AUTO,
    /** Slugs of leagues the user switched off; everything else is visible. */
    val hiddenLeagues: Set<String> = emptySet(),
    /** League slugs in the user's display order (drives home grouping). */
    val leagueOrder: List<String> = V2Leagues.DEFAULT_ORDER.map { it.slug },
    /** When the bundles were last successfully re-fetched from the mirror. */
    val lastRefreshEpochMs: Long? = null,
)

private val Context.v2SettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "football_ai_v2_settings",
)

/**
 * Preferences of the rewritten app, in their own DataStore file so the old
 * app's settings (providers, API keys, quotas) stay untouched until the old
 * layer is removed in a later slice.
 */
class V2SettingsRepository private constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<V2Settings> = dataStore.data.map { preferences ->
        V2Settings(
            themeMode = runCatching {
                V2ThemeMode.valueOf(preferences[KEY_THEME] ?: V2ThemeMode.AUTO.name)
            }.getOrDefault(V2ThemeMode.AUTO),
            hiddenLeagues = preferences[KEY_HIDDEN_LEAGUES] ?: emptySet(),
            leagueOrder = preferences[KEY_LEAGUE_ORDER]
                ?.split(ORDER_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: V2Leagues.DEFAULT_ORDER.map { it.slug },
            lastRefreshEpochMs = preferences[KEY_LAST_REFRESH],
        )
    }

    suspend fun setThemeMode(mode: V2ThemeMode) = edit { it[KEY_THEME] = mode.name }

    suspend fun setLeagueVisible(slug: String, visible: Boolean) = edit { preferences ->
        val current = (preferences[KEY_HIDDEN_LEAGUES] ?: emptySet()).toMutableSet()
        if (visible) current.remove(slug) else current.add(slug)
        preferences[KEY_HIDDEN_LEAGUES] = current
    }

    suspend fun setLeagueOrder(slugs: List<String>) = edit {
        it[KEY_LEAGUE_ORDER] = slugs.joinToString(ORDER_SEPARATOR)
    }

    suspend fun setLastRefresh(epochMs: Long) = edit { it[KEY_LAST_REFRESH] = epochMs }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        fun create(context: Context): V2SettingsRepository =
            V2SettingsRepository(context.applicationContext.v2SettingsDataStore)

        private val KEY_THEME = stringPreferencesKey("v2_theme_mode")
        private val KEY_HIDDEN_LEAGUES = stringSetPreferencesKey("v2_hidden_leagues")
        private val KEY_LEAGUE_ORDER = stringPreferencesKey("v2_league_order")
        private val KEY_LAST_REFRESH = longPreferencesKey("v2_last_refresh")

        /** Slugs never contain a newline, so it is a safe separator. */
        private const val ORDER_SEPARATOR = "\n"
    }
}
