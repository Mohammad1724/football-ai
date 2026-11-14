package com.footballai.app.data.sync

import com.footballai.app.core.AppError
import com.footballai.app.core.AppErrorException
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.NetworkMonitor
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.FootballDataOrgProvider
import com.footballai.app.data.repository.DailyAnalysisReport
import com.footballai.app.data.repository.DailyAnalysisRepository
import com.footballai.app.data.repository.MatchRepository
import com.footballai.app.data.repository.PredictionRepository
import com.footballai.app.data.repository.ProviderRegistry
import com.footballai.app.data.repository.SyncRepository
import com.footballai.app.data.settings.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a sync started. Only a manual press may bypass the "is it needed?" question. */
enum class SyncTrigger { STARTUP, RESUME, MANUAL }

/** What one sync actually did, in numbers rather than adjectives. */
data class SyncRun(
    /** Fixtures the source returned this time. Zero is a legitimate answer. */
    val added: Int,
    /** Per-competition failures; a non-empty list means the result is partial. */
    val warnings: List<String>,
    /** Set when the configured provider answered nothing and a keyless one stepped in. */
    val fallbackProviderId: String?,
    val dailyReport: DailyAnalysisReport?,
    val providerName: String?,
)

/**
 * The single state every refresh in the app reads.
 *
 * Every button, pull gesture and automatic trigger funnels through one coordinator, so
 * "the refresh does nothing" can only have one cause at a time. Two syncs can never run
 * together, and the progress the UI shows is the progress of the sync that is running.
 */
data class AutoSyncState(
    val isSyncing: Boolean = false,
    val trigger: SyncTrigger? = null,
    val startedAtEpochMs: Long? = null,
    val lastAttemptEpochMs: Long? = null,
    val lastSuccessEpochMs: Long? = null,
    val lastAdded: Int = 0,
    val lastWarnings: List<String> = emptyList(),
    val lastFallbackProviderId: String? = null,
    val lastError: AppError? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
) {
    val isManual: Boolean get() = trigger == SyncTrigger.MANUAL
}

/**
 * Decides when the app refreshes itself, and does the refreshing.
 *
 * The rule that makes "everything is up to date without pressing anything" true is
 * [isStale]: a new calendar day, a stale timestamp, or an empty fixture window all mean
 * the data on screen is not current. On top of that the coordinator refuses to run when
 * the phone is offline or when the user turned automatic refreshing off, and it never
 * runs twice at once.
 */
class AutoSyncCoordinator(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val dailyAnalysisRepository: DailyAnalysisRepository,
    private val predictionRepository: PredictionRepository,
    private val providerRegistry: ProviderRegistry,
    private val matchRepository: MatchRepository,
    private val networkMonitor: NetworkMonitor,
    private val clock: TimeProvider,
) {

    private val mutex = Mutex()

    /**
     * Serialises the "is it needed?" decision itself. Without it two triggers that fire
     * together (start-up and the dashboard opening) would both decide the data is stale
     * before either one started, and both would download.
     */
    private val gate = Mutex()

    private val _state = MutableStateFlow(AutoSyncState())
    val state: StateFlow<AutoSyncState> = _state.asStateFlow()

    /**
     * Runs a sync only when there is a reason to.
     *
     * Returns null when the call was skipped, which is the common case: an app opened
     * five times in a minute must not start five downloads.
     */
    suspend fun syncIfStale(trigger: SyncTrigger): Outcome<SyncRun>? {
        return gate.withLock {
            val settings = settingsRepository.current()
            if (!settings.onboarded) return@withLock null
            if (trigger != SyncTrigger.MANUAL && !settings.autoRefreshOnOpen) return@withLock null
            // Daily analysis has its own default source; any other mode needs one chosen.
            if (providerRegistry.activeProviderId() == null && !settings.dailyAnalysisEnabled) {
                return@withLock null
            }
            // An automatic pass must not fight one that is already running, and must not
            // repeat the attempt that just finished seconds ago.
            if (_state.value.isSyncing) return@withLock null
            val lastAttempt = _state.value.lastAttemptEpochMs
            if (trigger != SyncTrigger.MANUAL && lastAttempt != null &&
                clock.nowEpochMs() - lastAttempt < MIN_RETRY_GAP_MS
            ) {
                return@withLock null
            }
            if (!networkMonitor.isOnlineNow()) return@withLock null
            if (!isStale()) return@withLock null
            syncNow(trigger)
        }
    }

    /** Runs a sync unconditionally; used by every manual refresh in the UI. */
    suspend fun syncNow(
        trigger: SyncTrigger,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome<SyncRun> = mutex.withLock {
        val now = clock.nowEpochMs()
        _state.update {
            it.copy(
                isSyncing = true,
                trigger = trigger,
                startedAtEpochMs = now,
                lastAttemptEpochMs = now,
                lastError = null,
                progressDone = 0,
                progressTotal = 0,
            )
        }
        try {
            val previous = _state.value
            val justFinished = previous.lastSuccessEpochMs
            if (trigger == SyncTrigger.MANUAL && justFinished != null &&
                now - justFinished < DUPLICATE_WINDOW_MS
            ) {
                // A sync finished a moment ago; repeating it would download the same
                // answer again and make the button feel slow for no gain.
                return Outcome.Success(
                    SyncRun(
                        added = previous.lastAdded,
                        warnings = previous.lastWarnings,
                        fallbackProviderId = previous.lastFallbackProviderId,
                        dailyReport = null,
                        providerName = null,
                    ),
                )
            }
            if (!networkMonitor.isOnlineNow()) {
                return fail(AppError(ErrorCode.NETWORK_UNAVAILABLE), now)
            }
            val settings = settingsRepository.current()
            val providerId = providerRegistry.activeProviderId()
            val dailyMode = settings.dailyAnalysisEnabled ||
                providerId == FootballDataOrgProvider.PROVIDER_ID
            val progress: (Int, Int) -> Unit = { done, total ->
                _state.update { it.copy(progressDone = done, progressTotal = total) }
                onProgress(done, total)
            }
            if (dailyMode) {
                // The daily analysis path downloads the CSVs, rebuilds the ratings and
                // stores predictions; it *is* the sync for this mode.
                val report = dailyAnalysisRepository.run(progress)
                settingsRepository.setLastSync(clock.nowEpochMs())
                runCatching { predictionRepository.resolveFinishedPredictions() }
                val run = SyncRun(
                    added = report.fixturesReceived,
                    warnings = report.errors,
                    fallbackProviderId = null,
                    dailyReport = report,
                    providerName = providerRegistry.activeInfo(settings)?.displayName,
                )
                succeed(run)
                return Outcome.Success(run)
            }
            val id = providerId ?: return fail(AppError(ErrorCode.MISSING_API_KEY), now)
            return when (
                val result = syncRepository.syncFixtures(
                    providerId = id,
                    daysBack = DAYS_BACK,
                    daysAhead = DAYS_AHEAD,
                    onProgress = progress,
                )
            ) {
                is Outcome.Failure -> fail(result.error, now)
                is Outcome.Success -> {
                    runCatching { predictionRepository.resolveFinishedPredictions() }
                    val run = SyncRun(
                        added = result.value.fixtures,
                        warnings = result.value.errors,
                        fallbackProviderId = result.value.fallbackProviderId,
                        dailyReport = null,
                        providerName = providerRegistry.info(result.value.fallbackProviderId ?: id)?.displayName
                            ?: providerRegistry.activeInfo(settings)?.displayName,
                    )
                    succeed(run)
                    Outcome.Success(run)
                }
            }
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(isSyncing = false, trigger = null, progressDone = 0, progressTotal = 0) }
            throw cancelled
        } catch (error: AppErrorException) {
            return fail(error.error, now)
        } catch (error: Exception) {
            return fail(AppError(ErrorCode.UNKNOWN, detail = error.message, cause = error), now)
        } finally {
            _state.update { it.copy(isSyncing = false, trigger = null, progressDone = 0, progressTotal = 0) }
        }
    }

    /**
     * True when what is stored cannot honestly be called current.
     *
     * - Daily-analysis mode is stale when the last run was on an earlier calendar day.
     * - Otherwise it is stale when the last successful sync is from an earlier day, is
     *   older than [FRESH_FOR_MS], or when the coming two days have no fixtures at all
     *   (which is what a user sees as an empty home screen).
     */
    suspend fun isStale(now: Long = clock.nowEpochMs()): Boolean {
        val settings = settingsRepository.current()
        if (settings.dailyAnalysisEnabled || providerRegistry.activeProviderId() == FootballDataOrgProvider.PROVIDER_ID) {
            return dailyAnalysisRepository.needsRefresh()
        }
        val last = settings.lastSyncEpochMs ?: return true
        if (startOfDay(last) < startOfDay(now)) return true
        if (now - last > FRESH_FOR_MS) return true
        return !matchRepository.hasFixturesBetween(now, now + 2 * TimeConstants.DAY_MS)
    }

    /** Force the next automatic trigger to run, e.g. after the user changed sources. */
    fun forgetAttempt() = _state.update { it.copy(lastAttemptEpochMs = null) }

    private fun succeed(run: SyncRun) {
        _state.update {
            it.copy(
                lastSuccessEpochMs = clock.nowEpochMs(),
                lastAdded = run.added,
                lastWarnings = run.warnings,
                lastFallbackProviderId = run.fallbackProviderId,
                lastError = null,
            )
        }
    }

    private fun fail(error: AppError, now: Long): Outcome<SyncRun> {
        _state.update { it.copy(lastError = error, lastAttemptEpochMs = now) }
        return Outcome.Failure(error)
    }

    private fun startOfDay(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        /** How long stored fixtures are considered current without a new request. */
        val FRESH_FOR_MS: Long = 30 * TimeConstants.MINUTE_MS

        /** Two automatic attempts closer than this would only duplicate work. */
        private const val MIN_RETRY_GAP_MS: Long = 60_000L

        /** A second manual press inside this window reuses the sync that just finished. */
        private const val DUPLICATE_WINDOW_MS: Long = 5_000L

        /** The match list reaches ten days ahead; two days back keeps yesterday correct. */
        const val DAYS_BACK = 2
        const val DAYS_AHEAD = 10
    }
}
