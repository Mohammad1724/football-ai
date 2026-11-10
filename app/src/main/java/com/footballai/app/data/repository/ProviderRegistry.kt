package com.footballai.app.data.repository

import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.HistoricalDataProvider
import com.footballai.app.data.remote.ApiFootballProvider
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.data.remote.FootballDataOrgProvider
import com.footballai.app.data.settings.CredentialStore
import com.footballai.app.data.settings.SettingsRepository
import com.footballai.app.domain.model.ProviderInfo

/**
 * Knows which providers exist, whether they hold credentials, and which one the
 * user selected. Adding a new source means implementing [FootballDataProvider] and
 * registering it here; no repository or UI code has to change.
 */
class ProviderRegistry(
    private val apiFootballProvider: ApiFootballProvider,
    private val footballDataOrgProvider: FootballDataOrgProvider,
    private val historicalProvider: FootballDataCoUkProvider,
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository,
) {

    val fixtureProviders: List<FootballDataProvider> =
        listOf(apiFootballProvider, footballDataOrgProvider, historicalProvider)

    val historical: HistoricalDataProvider get() = historicalProvider

    fun provider(id: String): FootballDataProvider? = fixtureProviders.firstOrNull { it.info.id == id }

    fun info(id: String): ProviderInfo? = fixtureProviders.firstOrNull { it.info.id == id }?.info

    val allInfos: List<ProviderInfo> = fixtureProviders.map { it.info }

    fun hasCredentials(providerId: String): Boolean = credentialStore.hasKey(providerId)

    fun saveCredentials(providerId: String, apiKey: String) = credentialStore.saveKey(providerId, apiKey.trim())

    fun clearCredentials(providerId: String) = credentialStore.removeKey(providerId)

    /**
     * The provider used for fixtures: the user's explicit choice when it is usable,
     * otherwise the first provider that has a key.
     */
    suspend fun activeProvider(): FootballDataProvider? {
        val settings = settingsRepository.current()
        val selected = provider(settings.activeProviderId)
        if (selected != null && (!selected.info.requiresApiKey || hasCredentials(selected.info.id))) {
            return selected
        }
        fixtureProviders.firstOrNull { hasCredentials(it.info.id) }?.let { return it }
        // Last resort: the keyless CSV source, so a fresh install can still show fixtures.
        return fixtureProviders.firstOrNull { !it.info.requiresApiKey }
    }

    /** The keyless source used as a fallback when an authenticated provider returns nothing. */
    fun keylessProvider(excludeProviderId: String? = null): FootballDataProvider? =
        fixtureProviders.firstOrNull { !it.info.requiresApiKey && it.info.id != excludeProviderId }

    suspend fun activeProviderId(): String? = activeProvider()?.info?.id
}
