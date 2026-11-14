package com.footballai.app.data.repository

import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.HistoricalDataProvider
import com.footballai.app.data.remote.ApiFootballProvider
import com.footballai.app.data.remote.EspnOpenProvider
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.data.remote.FootballDataOrgProvider
import com.footballai.app.data.settings.CredentialStore
import com.footballai.app.data.settings.SettingsRepository
import com.footballai.app.data.settings.AppSettings
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
    private val espnOpenProvider: EspnOpenProvider,
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Order matters for the keyless default: ESPN comes before the CSV source because
     * it is the only key-free source in the app that publishes national-team and
     * women's fixtures.
     */
    val fixtureProviders: List<FootballDataProvider> =
        listOf(apiFootballProvider, footballDataOrgProvider, espnOpenProvider, historicalProvider)

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
        if (settings.dailyAnalysisEnabled) return historicalProvider
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

    /**
     * The provider that will actually serve fixtures for [settings], without doing any
     * I/O, so the UI can tell the user which source a list came from.
     */
    fun activeInfo(settings: AppSettings): ProviderInfo? {
        if (settings.dailyAnalysisEnabled) return historicalProvider.info
        provider(settings.activeProviderId)?.let { selected ->
            if (!selected.info.requiresApiKey || hasCredentials(selected.info.id)) return selected.info
        }
        fixtureProviders.firstOrNull { hasCredentials(it.info.id) }?.let { return it.info }
        return fixtureProviders.firstOrNull { !it.info.requiresApiKey }?.info
    }
}
