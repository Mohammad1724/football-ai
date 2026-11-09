package com.footballai.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.footballai.app.core.DispatchersProvider
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.ApiFootballProvider
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.data.remote.FootballDataOrgProvider
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.repository.MatchRepository
import com.footballai.app.data.repository.PerformanceRepository
import com.footballai.app.data.repository.PredictionRepository
import com.footballai.app.data.repository.ProviderRegistry
import com.footballai.app.data.repository.RatingsRepository
import com.footballai.app.data.repository.SyncRepository
import com.footballai.app.data.settings.CredentialStore
import com.footballai.app.data.settings.DataStoreQuotaStore
import com.footballai.app.data.settings.KeystoreCredentialStore
import com.footballai.app.data.settings.SettingsRepository
import com.footballai.engine.prediction.EngineConfig
import com.footballai.engine.prediction.MatchPredictionEngine

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsRepository.PREFERENCES_NAME,
)

private val Context.quotaDataStore: DataStore<Preferences> by preferencesDataStore(name = "football_ai_quota")

/**
 * Manual dependency container.
 *
 * A hand-written container keeps the build fast and predictable (no annotation
 * processor) while still giving a single place to swap implementations in tests.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val clock: TimeProvider = TimeProvider.System
    val dispatchers: DispatchersProvider = DispatchersProvider()

    val database: FootballDatabase = FootballDatabase.create(appContext)

    val settingsRepository: SettingsRepository = SettingsRepository(appContext.settingsDataStore)

    private val quotaGuard: QuotaGuard = QuotaGuard(DataStoreQuotaStore(appContext.quotaDataStore), clock)

    private val apiClient: ApiClient = ApiClient(quotaGuard)

    val credentialStore: CredentialStore = KeystoreCredentialStore(appContext)

    private val apiFootballProvider: ApiFootballProvider = ApiFootballProvider(apiClient) {
        credentialStore.getKey(ApiFootballProvider.PROVIDER_ID)
    }

    private val footballDataOrgProvider: FootballDataOrgProvider = FootballDataOrgProvider(apiClient) {
        credentialStore.getKey(FootballDataOrgProvider.PROVIDER_ID)
    }

    private val csvProvider: FootballDataCoUkProvider = FootballDataCoUkProvider(apiClient)

    val providerRegistry: ProviderRegistry = ProviderRegistry(
        apiFootballProvider = apiFootballProvider,
        footballDataOrgProvider = footballDataOrgProvider,
        historicalProvider = csvProvider,
        credentialStore = credentialStore,
        settingsRepository = settingsRepository,
    )

    val quotaGuardProvider: QuotaGuard get() = quotaGuard

    val ratingsRepository: RatingsRepository = RatingsRepository(database, clock)

    val engine: MatchPredictionEngine = MatchPredictionEngine(
        EngineConfig(eloBlendWeight = PredictionRepository.ELO_BLEND_WEIGHT),
    )

    val matchRepository: MatchRepository = MatchRepository(database, clock, ratingsRepository)

    val predictionRepository: PredictionRepository = PredictionRepository(
        db = database,
        matchRepository = matchRepository,
        ratingsRepository = ratingsRepository,
        engine = engine,
        clock = clock,
    )

    val syncRepository: SyncRepository = SyncRepository(
        db = database,
        registry = providerRegistry,
        settingsRepository = settingsRepository,
        ratingsRepository = ratingsRepository,
        clock = clock,
    )

    val performanceRepository: PerformanceRepository = PerformanceRepository(
        db = database,
        clock = clock,
        predictionRepository = predictionRepository,
    )
}
