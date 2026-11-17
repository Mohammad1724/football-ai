package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.data.local.mapper.parseStatus
import com.footballai.app.data.repository.PredictionPolicy
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.MatchDetail
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.domain.model.PredictionView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MatchDetailUiState(
    val isLoading: Boolean = true,
    val detail: MatchDetail? = null,
    val prediction: PredictionView? = null,
    val isPredicting: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLiveTracking: Boolean = false,
    val liveTrackingEnabled: Boolean = true,
    val headToHead: List<MatchSummary> = emptyList(),
    val lastError: AppError? = null,
    /**
     * A failed background refresh, kept apart from [lastError]: the screen still has
     * usable stored data, so this is a note, not the "nothing found" state.
     */
    val refreshWarning: AppError? = null,
    /** False when the source cannot re-read a single fixture, so live polling is pointless. */
    val liveTrackingSupported: Boolean = true,
    /** True while the international archive is being downloaded and stored. */
    val isImportingHistory: Boolean = false,
    /**
     * Why the last attempt could not produce a *new* forecast, while an older one is
     * still on screen. Kept apart from [lastError], which means there is nothing to show.
     */
    val predictionWarning: AppError? = null,
    /** Followed teams, keyed through [NameLocalization.favouriteKey], for the star. */
    val favouriteTeams: Set<String> = emptySet(),
    /** Followed players as stored ("teamId|name"), for the unfollow action. */
    val favouritePlayers: Set<String> = emptySet(),
    /** The same list as comparable keys, so a lineup row knows its own star. */
    val favouritePlayerKeys: Set<String> = emptySet(),
    /** Both teams' recent results, for the compact form panel. */
    val homeForm: TeamFormSnapshot? = null,
    val awayForm: TeamFormSnapshot? = null,
)

/** What a reader wants to know about a team before trusting a percentage. */
data class TeamFormSnapshot(
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val homeWins: Int,
    val homeDraws: Int,
    val homeLosses: Int,
    val awayWins: Int,
    val awayDraws: Int,
    val awayLosses: Int,
    val cleanSheets: Int,
    /** Newest first, for the trend dots. */
    val sequence: List<com.footballai.app.domain.model.ResultOutcome>,
) {
    companion object {
        /** Small, arithmetic, and out of the UI: counts only what the rows actually say. */
        fun of(results: List<com.footballai.app.domain.model.TeamResult>): TeamFormSnapshot {
            val wins = results.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.WIN }
            val draws = results.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.DRAW }
            val losses = results.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.LOSS }
            val atHome = results.filter { it.wasHome }
            val away = results.filterNot { it.wasHome }
            return TeamFormSnapshot(
                played = results.size,
                wins = wins,
                draws = draws,
                losses = losses,
                goalsFor = results.sumOf { it.goalsFor },
                goalsAgainst = results.sumOf { it.goalsAgainst },
                homeWins = atHome.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.WIN },
                homeDraws = atHome.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.DRAW },
                homeLosses = atHome.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.LOSS },
                awayWins = away.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.WIN },
                awayDraws = away.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.DRAW },
                awayLosses = away.count { it.outcome == com.footballai.app.domain.model.ResultOutcome.LOSS },
                cleanSheets = results.count { it.goalsAgainst == 0 },
                sequence = results.sortedByDescending { it.kickoffEpochMs }
                    .map { it.outcome },
            )
        }
    }
}

class MatchDetailViewModel(
    private val container: AppContainer,
    private val matchId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(MatchDetailUiState())
    val state: StateFlow<MatchDetailUiState> = _state.asStateFlow()

    private var freshnessJob: Job? = null
    private var screenVisible = false
    private var trackingJob: Job? = null
    private var lastLivePredictionAt: Long = 0L
    private var lastDetailsSyncAt: Long = 0L

    init {
        load()
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                // Stored names are turned into keys once, so the star compares like with like.
                _state.update {
                    it.copy(
                        favouriteTeams = settings.favouriteTeams.map { name -> NameLocalization.favouriteKey(name) }.toSet(),
                        favouritePlayers = settings.favouritePlayers,
                        favouritePlayerKeys = settings.favouritePlayers.mapNotNull { entry ->
                            val teamId = entry.substringBefore('|', "")
                            val name = NameLocalization.playerNameOf(entry)
                            if (teamId.isBlank() || name.isBlank()) null
                            else NameLocalization.playerFavouriteKey(teamId, name)
                        }.toSet(),
                    )
                }
            }
        }
    }

    fun load(refreshFromNetwork: Boolean = true) {
        viewModelScope.launch {
            guarded {
                val detail = container.matchRepository.matchDetail(matchId)
                _state.update { it.copy(detail = detail, isLoading = false) }
                if (detail != null) {
                    val headToHead = container.matchRepository.headToHead(
                        matchId = matchId,
                        homeTeamId = detail.summary.home.id,
                        awayTeamId = detail.summary.away.id,
                    )
                    _state.update { it.copy(headToHead = headToHead) }
                    _state.update {
                        it.copy(
                            homeForm = formOf(detail.summary.home.id),
                            awayForm = formOf(detail.summary.away.id),
                        )
                    }
                }
                if (refreshFromNetwork) {
                    val entity = container.matchRepository.matchEntity(matchId)
                    if (entity != null) {
                        val refreshed = if (syncFresh()) {
                            container.matchRepository.matchDetail(matchId)
                        } else {
                            null
                        }
                        if (refreshed != null) _state.update { it.copy(detail = refreshed) }
                    }
                }
                // A refresh that failed used to end here, leaving the analysis empty even
                // though the stored data was enough to predict. It is computed either way.
                predict()
                maybeStartTracking()
            }
        }
    }

    fun refresh() {
        // Four taps in a row used to queue four downloads and four predictions for the
        // same fixture. The second tap has nothing new to ask for.
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            guarded {
                _state.update { it.copy(isRefreshing = true) }
                val refreshed = if (syncFresh()) container.matchRepository.matchDetail(matchId) else null
                _state.update { it.copy(detail = refreshed ?: it.detail, isRefreshing = false) }
                (refreshed ?: _state.value.detail)?.let { detail ->
                    _state.update {
                        it.copy(
                            homeForm = formOf(detail.summary.home.id),
                            awayForm = formOf(detail.summary.away.id),
                        )
                    }
                }
                predict()
                maybeStartTracking()
            }
        }
    }

    fun predict(forcePersist: Boolean = false) {
        if (_state.value.isPredicting) return
        _state.update { it.copy(isPredicting = true) }
        viewModelScope.launch {
            guarded {
                val entity = container.matchRepository.matchEntity(matchId)
                val isLive = entity != null && parseStatus(entity.status).isLive
                val now = container.clock.nowEpochMs()
                // Live screens poll every few minutes; only one stored snapshot per 15 minutes
                // keeps the local history readable and the database small.
                val persist = !isLive || PredictionPolicy.shouldStoreLive(now, lastLivePredictionAt, forcePersist)
                when (val result = container.predictionRepository.predict(matchId, persist = persist)) {
                    is Outcome.Failure -> {
                        // The match changed state under the user (it kicked off, it ended,
                        // the source stopped allowing a forecast for it). The forecast they
                        // were reading is still the last thing the app knew, so it stays on
                        // screen with a note, instead of the screen collapsing to "no data"
                        // the moment a refresh lands.
                        val kept = _state.value.prediction
                            ?: container.predictionRepository.latestView(matchId)
                        _state.update {
                            it.copy(
                                isPredicting = false,
                                prediction = kept,
                                predictionWarning = result.error.takeIf { _ -> kept != null },
                                lastError = result.error.takeIf { _ -> kept == null },
                            )
                        }
                    }
                    is Outcome.Success -> {
                        if (isLive && persist) lastLivePredictionAt = now
                        _state.update {
                            it.copy(
                                isPredicting = false,
                                prediction = result.value,
                                predictionWarning = null,
                                lastError = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs a unit of screen work and converts any unexpected exception into an error
     * state. Without this, an exception inside a screen coroutine reaches the default
     * Android handler and the process is killed — the user sees the app close with no
     * message and no way to report what happened.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    isLoading = false,
                    isPredicting = false,
                    isRefreshing = false,
                    lastError = AppError(
                        code = com.footballai.app.core.ErrorCode.UNKNOWN,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                        cause = error,
                    ),
                )
            }
        }
    }

    /**
     * Downloads the international results archive, then retries the prediction.
     *
     * This is the one action that can turn "not enough history" into a real forecast for
     * a national-team fixture, so it lives next to the error it fixes rather than in a
     * settings screen the user has no reason to visit.
     */
    fun importNationalHistory() {
        if (_state.value.isImportingHistory) return
        _state.update { it.copy(isImportingHistory = true, lastError = null) }
        viewModelScope.launch {
            guarded {
                when (val result = container.nationalHistoryRepository.importArchive()) {
                    is Outcome.Failure -> _state.update {
                        it.copy(isImportingHistory = false, lastError = result.error)
                    }
                    is Outcome.Success -> {
                        _state.update { it.copy(isImportingHistory = false) }
                        predict()
                    }
                }
            }
        }
    }

    /**
     * Follows or unfollows a team from its own match. The stored key is the name the
     * source used, and comparison happens through [NameLocalization.favouriteKey], so the
     * same team is recognised however the next source spells it.
     */
    fun toggleFavourite(teamName: String, favourite: Boolean) {
        viewModelScope.launch {
            val stored = container.settingsRepository.current().favouriteTeams
            val key = NameLocalization.favouriteKey(teamName)
            val existing = stored.firstOrNull { NameLocalization.favouriteKey(it) == key }
            // The same team under a different spelling must not become two favourites.
            when {
                favourite && existing == null -> container.settingsRepository.toggleFavouriteTeam(teamName, true)
                !favourite && existing != null -> container.settingsRepository.toggleFavouriteTeam(existing, false)
                else -> Unit
            }
        }
    }

    /**
     * Follows or unfollows a player from a lineup row. The stored entry is "teamId|name";
     * the comparison uses [NameLocalization.playerFavouriteKey], so a differently spelled
     * name — or the same name under another club — never becomes the same favourite.
     */
    fun toggleFavouritePlayer(teamId: String, name: String, favourite: Boolean) {
        viewModelScope.launch {
            val stored = container.settingsRepository.current().favouritePlayers
            val key = NameLocalization.playerFavouriteKey(teamId, name)
            val existing = stored.firstOrNull { entry ->
                NameLocalization.playerFavouriteKey(
                    entry.substringBefore('|', ""),
                    NameLocalization.playerNameOf(entry),
                ) == key
            }
            when {
                favourite && existing == null ->
                    container.settingsRepository.toggleFavouritePlayer(
                        NameLocalization.playerEntry(teamId, name),
                        true,
                    )
                !favourite && existing != null ->
                    container.settingsRepository.toggleFavouritePlayer(existing, false)
                else -> Unit
            }
        }
    }

    /** Null when the app has no finished match for that team: the panel then stays away. */
    private suspend fun formOf(teamId: String): TeamFormSnapshot? {
        val results = container.matchRepository.recentResults(teamId, limit = 10)
        return if (results.isEmpty()) null else TeamFormSnapshot.of(results)
    }

    fun onScreenVisible() {
        screenVisible = true
        if (freshnessJob?.isActive != true) freshnessJob = viewModelScope.launch {
            while (isActive) {
                val match = container.matchRepository.matchEntity(matchId)
                // A source that cannot refresh one fixture will never make the live row
                // fresher, so calling it stale every ten seconds would be a permanent
                // error the user can do nothing about.
                if (match != null && parseStatus(match.status).isLive &&
                    container.syncRepository.supportsMatchRefresh(match.providerId) &&
                    container.clock.nowEpochMs() - match.lastSyncedAt > PredictionPolicy.LIVE_TTL_MS) {
                    _state.update { it.copy(prediction = null, lastError = AppError(com.footballai.app.core.ErrorCode.STALE_DATA)) }
                }
                delay(10_000L)
            }
        }
        viewModelScope.launch { maybeStartTracking() }
    }

    fun onScreenHidden() {
        screenVisible = false
        freshnessJob?.cancel()
        freshnessJob = null
        stopTracking()
    }

    fun dismissError() = _state.update { it.copy(lastError = null) }

    private suspend fun maybeStartTracking() {
        val settings = container.settingsRepository.current()
        val entity = container.matchRepository.matchEntity(matchId) ?: return
        val isLive = parseStatus(entity.status).isLive
        val enabled = screenVisible && _state.value.liveTrackingEnabled && settings.livePollingEnabled &&
            isLive && container.syncRepository.supportsMatchRefresh(entity.providerId)
        if (!enabled) {
            stopTracking()
            return
        }
        if (trackingJob?.isActive == true) return
        trackingJob = viewModelScope.launch {
            _state.update { it.copy(isLiveTracking = true) }
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val current = container.matchRepository.matchEntity(matchId) ?: break
                if (!parseStatus(current.status).isLive) break
                if (!syncFresh()) continue
                val detail = container.matchRepository.matchDetail(matchId)
                _state.update { it.copy(detail = detail) }
                predict()
            }
            _state.update { it.copy(isLiveTracking = false) }
        }
    }

    private suspend fun syncFresh(): Boolean {
        val entity = container.matchRepository.matchEntity(matchId)
        val supported = entity != null && container.syncRepository.supportsMatchRefresh(entity.providerId)
        _state.update { it.copy(liveTrackingSupported = supported) }
        // A source that publishes whole matchdays cannot re-read one fixture. There is
        // nothing to refresh and nothing went wrong: the stored row is the truth until
        // the next matchday sync replaces it.
        if (!supported) {
            _state.update { it.copy(refreshWarning = null) }
            return false
        }
        when (val result = container.syncRepository.refreshMatch(matchId)) {
            is Outcome.Failure -> {
                // Keep the detail and the prediction: the user can still read the
                // analysis of what is stored, and the strip says the refresh failed.
                _state.update { it.copy(refreshWarning = result.error, isLoading = false) }
                return false
            }
            is Outcome.Success -> _state.update { it.copy(refreshWarning = null, lastError = null) }
        }
        val now = container.clock.nowEpochMs()
        if (now - lastDetailsSyncAt >= DETAILS_INTERVAL_MS) {
            // Missing/old statistics are excluded by PredictionRepository. A details
            // failure must not prevent a fresh score-only prediction.
            container.syncRepository.syncMatchDetails(matchId, force = true)
            lastDetailsSyncAt = now
        }
        return true
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _state.update { it.copy(isLiveTracking = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }

    companion object {
        /** One minute; quota guard still enforces provider limits. */
        const val POLL_INTERVAL_MS = TimeConstants.MINUTE_MS
        private const val DETAILS_INTERVAL_MS = 20 * TimeConstants.MINUTE_MS
    }
}

/** Convenience: the kind of prediction that should be shown for a given status. */
fun PredictionView.isLivePrediction(): Boolean = kind == PredictionKind.LIVE
