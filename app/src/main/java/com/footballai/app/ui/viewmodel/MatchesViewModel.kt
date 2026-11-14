package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

/** Club or national-team fixtures. National teams are a first-class scope, not a corner case. */
enum class MatchKindFilter { ALL, CLUBS, NATIONAL }

enum class MatchGenderFilter { ALL, MEN, WOMEN }

enum class MatchSearchFilter { ALL, COMPETITIONS, TEAMS, MATCHES }

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
    val matches: List<MatchSummary> = emptyList(),
    val competitions: List<Competition> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MatchesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()

    private val selection = MutableStateFlow(MatchSelection())
    private val refreshVersion = MutableStateFlow(0)
    private val searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                selection,
                refreshVersion,
                container.settingsRepository.settings,
            ) { selected, _, settings -> selected to settings.dailyAnalysisEnabled }
                .flatMapLatest { (selected, dailyAnalysis) ->
                    val now = container.clock.nowEpochMs()
                    val (from, to) = localDayWindow(now, selected.dayOffset)
                    combine(
                        container.matchRepository.observeBetween(from, to),
                        container.matchRepository.observeCompetitions(),
                    ) { matches, competitions ->
                        val availableCompetitions = if (dailyAnalysis) {
                            competitions.filter { it.providerId == "football-data-co-uk" }
                        } else {
                            competitions
                        }
                        val followedIds = availableCompetitions.filter { it.isFollowed }.map { it.id }.toSet()
                        val visible = matches.filter { match ->
                            (!dailyAnalysis || match.id.startsWith("football-data-co-uk:")) &&
                                (selected.competitionId == null || match.competitionId == selected.competitionId) &&
                                (!selected.followedOnly || match.competitionId in followedIds) &&
                                (!selected.liveOnly || match.status.isLive) &&
                                when (selected.kind) {
                                    MatchKindFilter.ALL -> true
                                    MatchKindFilter.CLUBS -> !match.isNational
                                    MatchKindFilter.NATIONAL -> match.isNational
                                } &&
                                when (selected.gender) {
                                    MatchGenderFilter.ALL -> true
                                    MatchGenderFilter.MEN -> !match.isWomen
                                    MatchGenderFilter.WOMEN -> match.isWomen
                                }
                        }.sortedBy { it.kickoffEpochMs }
                        visible to availableCompetitions
                    }
                }.collect { (matches, competitions) ->
                    val selected = selection.value
                    _state.update {
                        it.copy(
                            matches = matches,
                            competitions = competitions,
                            isLoading = false,
                            selectedDayOffset = selected.dayOffset,
                            competitionId = selected.competitionId,
                            followedOnly = selected.followedOnly,
                            liveOnly = selected.liveOnly,
                            kind = selected.kind,
                            gender = selected.gender,
                        )
                    }
                    if (selected.competitionId != null && competitions.none { it.id == selected.competitionId }) {
                        selection.update { it.copy(competitionId = null) }
                    }
                }
        }
        viewModelScope.launch {
            combine(
                searchQuery,
                container.matchRepository.observeAll(),
                container.matchRepository.observeCompetitions(),
                container.settingsRepository.settings,
            ) { query, matches, competitions, settings ->
                buildSearchResults(query, matches, competitions, settings.dailyAnalysisEnabled)
            }.collect { results ->
                _state.update { it.copy(searchResults = results) }
            }
        }
    }

    fun setSearchOpen(open: Boolean) {
        _state.update { it.copy(searchOpen = open) }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
        _state.update { it.copy(searchQuery = query) }
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

    fun setCompetition(competitionId: String?) {
        selection.update { it.copy(competitionId = competitionId) }
    }

    fun toggleFollowedOnly() {
        selection.update { it.copy(followedOnly = !it.followedOnly) }
    }

    fun toggleLiveOnly() {
        selection.update { it.copy(liveOnly = !it.liveOnly) }
    }

    fun setKindFilter(kind: MatchKindFilter) {
        selection.update { it.copy(kind = kind) }
    }

    fun setGenderFilter(gender: MatchGenderFilter) {
        selection.update { it.copy(gender = gender) }
    }

    fun refresh() {
        if (_state.value.isSyncing) return
        _state.update { it.copy(isSyncing = true, lastError = null) }
        viewModelScope.launch {
            try {
                if (container.settingsRepository.current().dailyAnalysisEnabled || container.providerRegistry.activeProviderId() == "football-data-org") {
                    val report = container.dailyAnalysisRepository.run()
                    _state.update {
                        it.copy(lastError = report.errors.firstOrNull()?.let { AppError(ErrorCode.REQUEST_FAILED) })
                    }
                    return@launch
                }
                val providerId = container.providerRegistry.activeProviderId()
                val result = if (providerId == null) {
                    Outcome.Failure(AppError(ErrorCode.NOT_FOUND))
                } else {
                    // Cover every date exposed by the horizontal day selector.
                    container.syncRepository.syncFixtures(
                        providerId = providerId,
                        daysBack = 3,
                        daysAhead = NEWEST_DAY_OFFSET,
                    )
                }
                val error = when (result) {
                    is Outcome.Failure -> result.error
                    is Outcome.Success -> if (result.value.errors.isEmpty()) {
                        null
                    } else {
                        AppError(
                            code = runCatching {
                                ErrorCode.valueOf(result.value.errors.first().substringAfterLast(": "))
                            }.getOrDefault(ErrorCode.REQUEST_FAILED),
                            detail = result.value.errors.joinToString(),
                        )
                    }
                }
                _state.update { it.copy(lastError = error) }
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
            val query = rawQuery.trim().lowercase(Locale.ROOT)
            if (query.isEmpty()) return MatchSearchResults()
            val matches = allMatches.filter { !dailyAnalysis || it.id.startsWith("football-data-co-uk:") }
            val competitions = allCompetitions.filter { competition ->
                (!dailyAnalysis || competition.providerId == "football-data-co-uk") &&
                    (competition.name.lowercase(Locale.ROOT).contains(query) ||
                        competition.country?.lowercase(Locale.ROOT)?.contains(query) == true)
            }.take(20)
            val teams = matches.flatMap { listOf(it.home, it.away) }
                .distinctBy { it.id }
                .filter { team ->
                    team.name.lowercase(Locale.ROOT).contains(query) ||
                        NameLocalization.matches(rawQuery, team.name, team.shortName)
                }
                .sortedBy { it.name }
                .take(20)
            val matchingMatches = matches.filter { match ->
                match.home.name.lowercase(Locale.ROOT).contains(query) ||
                    match.away.name.lowercase(Locale.ROOT).contains(query) ||
                    match.competitionName.lowercase(Locale.ROOT).contains(query) ||
                    NameLocalization.matches(rawQuery, match.home.name, match.home.shortName) ||
                    NameLocalization.matches(rawQuery, match.away.name, match.away.shortName)
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
