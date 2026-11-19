package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.sync.SyncTrigger
import com.footballai.app.domain.catalog.CompetitionOrder
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Recent results and the next ten days share one date-driven match list. */
private data class MatchSelection(
    val dayOffset: Int = 0,
    val competitionId: String? = null,
    val followedOnly: Boolean = false,
    val liveOnly: Boolean = false,
    val kind: MatchKindFilter = MatchKindFilter.ALL,
    val gender: MatchGenderFilter = MatchGenderFilter.ALL,
)

/** Everything the match-list pipeline needs from settings, resolved once per emission. */
private data class MatchScope(
    val selection: MatchSelection,
    val dailyAnalysis: Boolean,
    val sourceName: String,
    val competitionOrder: List<String>,
    val favouriteTeams: Set<String>,
)

private data class MatchListData(
    val matches: List<MatchSummary>,
    val competitions: List<Competition>,
    val dailyAnalysis: Boolean,
    val sourceName: String,
    /** Matches of the teams the user follows, shown above everything else. */
    val favourites: List<MatchSummary> = emptyList(),
    val availableMatchesCount: Int = 0,
)

private data class SearchInput(
    val query: String,
    val matches: List<MatchSummary>,
    val competitions: List<Competition>,
    val dailyAnalysis: Boolean,
)

/** Club or national-team fixtures. National teams are a first-class scope, not a corner case. */
enum class MatchKindFilter { ALL, CLUBS, NATIONAL }

enum class MatchGenderFilter { ALL, MEN, WOMEN }

enum class MatchSearchFilter { ALL, COMPETITIONS, TEAMS, MATCHES }
enum class MatchEmptyReason { FILTERED, FILTERED_NO_DATA, NO_DATA }

/** All match-list filters, independent from the date and search. */
data class MatchFilters(
    val competitionId: String? = null,
    val followedOnly: Boolean = false,
    val liveOnly: Boolean = false,
    val kind: MatchKindFilter = MatchKindFilter.ALL,
    val gender: MatchGenderFilter = MatchGenderFilter.ALL,
) {
    val activeCount: Int get() = listOf(
        competitionId != null, followedOnly, liveOnly,
        kind != MatchKindFilter.ALL, gender != MatchGenderFilter.ALL,
    ).count { it }

    fun accepts(match: MatchSummary, followedCompetitionIds: Set<String>): Boolean =
        (competitionId == null || match.competitionId == competitionId) &&
            (!followedOnly || match.competitionId in followedCompetitionIds) &&
            (!liveOnly || match.status.isLive) &&
            when (kind) {
                MatchKindFilter.ALL -> true
                MatchKindFilter.CLUBS -> !match.isNational
                MatchKindFilter.NATIONAL -> match.isNational
            } &&
            when (gender) {
                MatchGenderFilter.ALL -> true
                MatchGenderFilter.MEN -> !match.isWomen
                MatchGenderFilter.WOMEN -> match.isWomen
            }
}

data class MatchSearchResults(
    val competitions: List<Competition> = emptyList(),
    val teams: List<TeamRef> = emptyList(),
    val matches: List<MatchSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = competitions.isEmpty() && teams.isEmpty() && matches.isEmpty()
}

data class MatchesUiState(
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val lastError: AppError? = null,
    val selectedDayOffset: Int = 0,
    val competitionId: String? = null,
    val followedOnly: Boolean = false,
    val liveOnly: Boolean = false,
    val kind: MatchKindFilter = MatchKindFilter.ALL,
    val gender: MatchGenderFilter = MatchGenderFilter.ALL,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchFilter: MatchSearchFilter = MatchSearchFilter.ALL,
    val searchResults: MatchSearchResults = MatchSearchResults(),
    val searchLoading: Boolean = false,
    val matches: List<MatchSummary> = emptyList(),
    val competitions: List<Competition> = emptyList(),
    /** Count before filters, for explaining an empty filtered list. */
    val availableMatchesCount: Int = 0,
    /** Daily-analysis mode only ever serves the football-data.co.uk club leagues. */
    val dailyAnalysisMode: Boolean = true,
    /** Which source is actually serving this list, so an empty day can be explained. */
    val sourceName: String = "",
    /** Matches of followed teams, shown above the tournament sections. */
    val favourites: List<MatchSummary> = emptyList(),
    /** True while the app switches to the worldwide source and syncs it. */
    val switchingSource: Boolean = false,
    /** Fixtures returned by the last refresh; null when nothing was refreshed yet. */
    val justSynced: Int? = null,
    /** Per-competition failures of the last refresh, so a partial result is visible. */
    val syncWarnings: List<String> = emptyList(),
    /** False when the phone has no connection: the refresh button explains instead of hanging. */
    val isOnline: Boolean = true,
    /**
     * True while the app refreshes itself. The user did not press anything, so the list
     * keeps showing what it has and only a thin strip changes.
     */
    val autoSyncing: Boolean = false,
    /** When the stored fixtures were last confirmed current, for the status strip. */
    val lastSyncEpochMs: Long? = null,
    /** Live wall clock, so "3 minutes ago" keeps being true without a new sync. */
    val nowEpochMs: Long = System.currentTimeMillis(),
    /** Competitions finished and total, while a sync runs. */
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
) {
    val filters: MatchFilters get() = MatchFilters(competitionId, followedOnly, liveOnly, kind, gender)
    val emptyReason: MatchEmptyReason get() = when {
        filters.activeCount == 0 -> MatchEmptyReason.NO_DATA
        availableMatchesCount > 0 -> MatchEmptyReason.FILTERED
        else -> MatchEmptyReason.FILTERED_NO_DATA
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MatchesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()

    private val selection = MutableStateFlow(MatchSelection())
    private val refreshVersion = MutableStateFlow(0)
    private val searchQuery = MutableStateFlow("")

    init {
        // Repair the date the reader actually opened rather than always the nearest
        // match to today. Cancellation stops old date requests when swiping quickly.
        viewModelScope.launch {
            selection.map { it.dayOffset }.distinctUntilChanged().flatMapLatest { offset ->
                val (from, to) = localDayWindow(container.clock.nowEpochMs(), offset)
                container.matchRepository.observeBetween(from, to).map { matches ->
                    // Identity-only signature: crest updates and live scores must not
                    // cancel their own repair, but newly synced fixtures should restart it.
                    Triple(from, to, matches.map { listOf(it.id, it.competitionId, it.home.id, it.away.id) }
                        .sortedBy { it.joinToString("\u0000") })
                }.distinctUntilChanged()
            }.collectLatest { (from, to, fixtures) ->
                delay(800)
                if (fixtures.isNotEmpty() && container.networkMonitor.isOnlineNow()) {
                    try {
                        container.syncRepository.backfillCrestsForDay(from, to)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Crest repair is optional; it must never crash the match list.
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                selection,
                refreshVersion,
                container.settingsRepository.settings,
            ) { selected, _, settings ->
                MatchScope(
                    selection = selected,
                    dailyAnalysis = settings.dailyAnalysisEnabled,
                    sourceName = container.providerRegistry.activeInfo(settings)?.displayName.orEmpty(),
                    competitionOrder = settings.competitionOrder,
                    favouriteTeams = settings.favouriteTeams,
                )
            }
                .flatMapLatest { scope ->
                    val selected = scope.selection
                    val dailyAnalysis = scope.dailyAnalysis
                    val now = container.clock.nowEpochMs()
                    val (from, to) = localDayWindow(now, selected.dayOffset)
                    combine(
                        container.matchRepository.observeBetween(from, to),
                        container.matchRepository.observeCompetitions(),
                    ) { matches, competitions ->
                        // The user's own order wins over the app's alphabetical guess.
                        val availableCompetitions = CompetitionOrder.sorted(
                            if (dailyAnalysis) {
                                competitions.filter { it.providerId == "football-data-co-uk" }
                            } else {
                                competitions
                            },
                            scope.competitionOrder,
                        )
                        val competitionRanks = availableCompetitions
                            .mapIndexed { index, competition -> competition.id to index }
                            .toMap()
                        val followedIds = availableCompetitions.filter { it.isFollowed }.map { it.id }.toSet()
                        val filters = MatchFilters(selected.competitionId, selected.followedOnly,
                            selected.liveOnly, selected.kind, selected.gender)
                        val visible = matches.filter { match ->
                            (!dailyAnalysis || match.id.startsWith("football-data-co-uk:")) &&
                                filters.accepts(match, followedIds)
                        }.sortedWith(
                            compareBy(
                                { competitionRanks[it.competitionId] ?: Int.MAX_VALUE },
                                { it.kickoffEpochMs },
                            ),
                        )
                        // A followed team is the one thing the app can be sure the user
                        // cares about, so its matches are lifted above the tournament
                        // sections instead of waiting for the user to scroll to them.
                        val favouriteKeys = scope.favouriteTeams
                            .map { NameLocalization.favouriteKey(it) }
                            .toSet()
                        val favouriteMatches = if (favouriteKeys.isEmpty()) {
                            emptyList()
                        } else {
                            visible.filter { match ->
                                favouriteKeys.contains(NameLocalization.favouriteKey(match.home.name, match.home.shortName)) ||
                                    favouriteKeys.contains(NameLocalization.favouriteKey(match.away.name, match.away.shortName))
                            }.sortedBy { it.kickoffEpochMs }
                        }
                        MatchListData(
                            visible,
                            availableCompetitions,
                            dailyAnalysis,
                            scope.sourceName,
                            favouriteMatches,
                            matches.count { !dailyAnalysis || it.id.startsWith("football-data-co-uk:") },
                        )
                    }
                }.collect { data ->
                    val selected = selection.value
                    _state.update {
                        it.copy(
                            matches = data.matches,
                            competitions = data.competitions,
                            availableMatchesCount = data.availableMatchesCount,
                            isLoading = false,
                            selectedDayOffset = selected.dayOffset,
                            dailyAnalysisMode = data.dailyAnalysis,
                            sourceName = data.sourceName,
                            competitionId = selected.competitionId,
                            followedOnly = selected.followedOnly,
                            liveOnly = selected.liveOnly,
                            kind = selected.kind,
                            gender = selected.gender,
                            favourites = data.favourites,
                        )
                    }
                    if (selected.competitionId != null && data.competitions.none { it.id == selected.competitionId }) {
                        selection.update { it.copy(competitionId = null) }
                    }
                }
        }
        viewModelScope.launch {
            container.networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
            }
        }
        viewModelScope.launch {
            container.autoSyncCoordinator.state.collect { sync ->
                _state.update {
                    it.copy(
                        autoSyncing = sync.isSyncing && !it.isSyncing,
                        lastSyncEpochMs = sync.lastSuccessEpochMs ?: it.lastSyncEpochMs,
                        nowEpochMs = container.clock.nowEpochMs(),
                        // Progress is only meaningful while a sync runs; a stale "7 of 40"
                        // left on screen after it finished would be a lie.
                        syncDone = if (sync.isSyncing) sync.progressDone else it.syncDone,
                        syncTotal = if (sync.isSyncing) sync.progressTotal else it.syncTotal,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _state.update { it.copy(lastSyncEpochMs = settings.lastSyncEpochMs ?: it.lastSyncEpochMs) }
            }
        }
        viewModelScope.launch {
            // Keep the relative "last updated" label honest without a sync every minute.
            while (true) {
                delay(30_000L)
                _state.update { it.copy(nowEpochMs = container.clock.nowEpochMs()) }
            }
        }
        viewModelScope.launch {
            combine(
                searchQuery,
                container.matchRepository.observeAll(),
                container.matchRepository.observeCompetitions(),
                container.settingsRepository.settings,
            ) { query, matches, competitions, settings ->
                SearchInput(query, matches, competitions, settings.dailyAnalysisEnabled)
            }.mapLatest { input ->
                input.query to withContext(Dispatchers.Default) {
                    buildSearchResults(input.query, input.matches, input.competitions, input.dailyAnalysis)
                }
            }.collect { (query, results) ->
                if (query == searchQuery.value) {
                    _state.update { it.copy(searchResults = results, searchLoading = false) }
                }
            }
        }
    }

    fun setSearchOpen(open: Boolean) {
        _state.update { it.copy(searchOpen = open) }
    }

    fun updateSearchQuery(query: String) {
        if (query == searchQuery.value) return
        searchQuery.value = query
        _state.update { it.copy(searchQuery = query, searchResults = MatchSearchResults(), searchLoading = query.isNotBlank()) }
    }

    fun setSearchFilter(filter: MatchSearchFilter) {
        _state.update { it.copy(searchFilter = filter) }
    }

    fun selectDate(epochMs: Long) {
        val offset = dayOffsetFor(container.clock.nowEpochMs(), epochMs)
        if (offset in -3650..3650) selection.update { it.copy(dayOffset = offset) }
    }

    fun selectDay(dayOffset: Int) {
        if (dayOffset !in -3650..3650) return
        selection.update { it.copy(dayOffset = dayOffset) }
    }

    /** Apply or reset a filter set in one emission; neither changes the selected day. */
    fun applyFilters(filters: MatchFilters) {
        selection.update { it.copy(
            competitionId = filters.competitionId,
            followedOnly = filters.followedOnly,
            liveOnly = filters.liveOnly,
            kind = filters.kind,
            gender = filters.gender,
        ) }
    }

    fun clearFilters() = applyFilters(MatchFilters())

    fun setCompetition(competitionId: String?) {
        selection.update { it.copy(competitionId = competitionId) }
    }

    fun toggleFollowedOnly() {
        selection.update { it.copy(followedOnly = !it.followedOnly) }
    }

    fun toggleLiveOnly() {
        selection.update { it.copy(liveOnly = !it.liveOnly) }
    }

    /**
     * Switches from the daily-analysis CSV mode to the worldwide keyless source and
     * syncs it, so a user looking at an empty day can see the real fixture list
     * (including national-team football) in one tap instead of hunting in settings.
     */
    fun enableGlobalCoverage() {
        if (_state.value.switchingSource) return
        _state.update { it.copy(switchingSource = true, lastError = null) }
        viewModelScope.launch {
            try {
                container.settingsRepository.setDailyAnalysis(false)
                container.settingsRepository.setActiveProvider(com.footballai.app.data.remote.EspnOpenProvider.PROVIDER_ID)
                container.syncRepository.syncCompetitions(com.footballai.app.data.remote.EspnOpenProvider.PROVIDER_ID)
                // Go through the coordinator so the progress strip, the quota guard and
                // the "never two syncs at once" rule apply here too.
                container.autoSyncCoordinator.syncNow(SyncTrigger.MANUAL)
                selection.update { it.copy(kind = MatchKindFilter.ALL, gender = MatchGenderFilter.ALL, followedOnly = false) }
            } finally {
                _state.update { it.copy(switchingSource = false) }
            }
        }
    }

    fun setKindFilter(kind: MatchKindFilter) {
        selection.update { it.copy(kind = kind) }
    }

    fun setGenderFilter(gender: MatchGenderFilter) {
        selection.update { it.copy(gender = gender) }
    }

    fun refresh() {
        if (_state.value.isSyncing) return
        _state.update { it.copy(isSyncing = true, lastError = null, justSynced = null) }
        viewModelScope.launch {
            try {
                // Every refresh in the app is the same refresh, so a fix here fixes the
                // button, the pull gesture and the automatic pass at once.
                when (val result = container.autoSyncCoordinator.syncNow(SyncTrigger.MANUAL)) {
                    is Outcome.Failure -> _state.update { it.copy(lastError = result.error) }
                    is Outcome.Success -> _state.update {
                        it.copy(
                            lastError = null,
                            justSynced = result.value.added,
                            syncWarnings = result.value.warnings,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(lastError = AppError(ErrorCode.UNKNOWN, cause = error)) }
            } finally {
                refreshVersion.value += 1
                _state.update { it.copy(isSyncing = false) }
            }
        }
    }

    companion object {
        const val OLDEST_DAY_OFFSET = -2
        const val NEWEST_DAY_OFFSET = 10

        internal fun dayOffsetFor(
            nowEpochMs: Long,
            selectedEpochMs: Long,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): Int {
            val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
            val selected = Instant.ofEpochMilli(selectedEpochMs).atZone(zoneId).toLocalDate()
            return ChronoUnit.DAYS.between(today, selected).toInt()
        }

        internal fun buildSearchResults(
            rawQuery: String,
            allMatches: List<MatchSummary>,
            allCompetitions: List<Competition>,
            dailyAnalysis: Boolean,
        ): MatchSearchResults {
            val query = NameLocalization.normalize(rawQuery)
            if (query.isEmpty()) return MatchSearchResults()
            val matches = allMatches.filter { !dailyAnalysis || it.id.startsWith("football-data-co-uk:") }
            fun contains(value: String?) = value != null && NameLocalization.normalize(value).contains(query)
            fun competitionMatches(id: String, name: String) =
                contains(name) || contains(NameLocalization.persianCompetitionName(id, name))
            val competitions = allCompetitions.filter { competition ->
                (!dailyAnalysis || competition.providerId == "football-data-co-uk") &&
                    (competitionMatches(competition.id, competition.name) || contains(competition.country))
            }.take(20)
            val teams = matches.flatMap { listOf(it.home, it.away) }
                .distinctBy { it.id }
                .filter { team -> NameLocalization.matches(rawQuery, team.name, team.shortName) }
                .sortedBy { it.name }
                .take(20)
            // Resolve a translated league name once per id, not once per match row.
            val leagueMatches = mutableMapOf<String, Boolean>()
            val matchingMatches = matches.filter { match ->
                NameLocalization.matches(rawQuery, match.home.name, match.home.shortName) ||
                    NameLocalization.matches(rawQuery, match.away.name, match.away.shortName) ||
                    leagueMatches.getOrPut(match.competitionId) {
                        competitionMatches(match.competitionId, match.competitionName)
                    }
            }.take(50)
            return MatchSearchResults(competitions, teams, matchingMatches)
        }

        /** Calendar-day boundaries, including DST changes, in the device's time zone. */
        internal fun localDayWindow(
            nowEpochMs: Long,
            dayOffset: Int,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): Pair<Long, Long> {
            val date = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate().plusDays(dayOffset.toLong())
            val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return start to end
        }
    }
}
