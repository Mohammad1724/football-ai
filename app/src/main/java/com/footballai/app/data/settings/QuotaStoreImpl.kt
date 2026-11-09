package com.footballai.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.footballai.app.data.remote.QuotaStore
import kotlinx.coroutines.flow.first

/** Persists the daily provider request counters so restarts respect the quota. */
class DataStoreQuotaStore(private val dataStore: DataStore<Preferences>) : QuotaStore {

    override suspend fun readCounter(providerId: String): Pair<Long, Int> {
        val preferences = dataStore.data.first()
        return (preferences[dayKey(providerId)] ?: 0L) to (preferences[countKey(providerId)] ?: 0)
    }

    override suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int) {
        dataStore.edit { preferences ->
            preferences[dayKey(providerId)] = dayEpochMs
            preferences[countKey(providerId)] = count
        }
    }

    private fun dayKey(providerId: String) = longPreferencesKey("quota_day_$providerId")

    private fun countKey(providerId: String) = intPreferencesKey("quota_count_$providerId")
}
