package com.footballai.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.footballai.app.di.AppContainer

/** The single dependency container, provided once at the activity root. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer was not provided")
}

/**
 * The shell's search button, wired to the matches feed.
 *
 * In the light-glass shell the search action leaves the matches top bar and becomes
 * the floating circle beside the navigation pill — but the pill belongs to the app
 * shell and the search state belongs to the matches screen, two subtrees that only
 * meet at the navigation root. This counter is the meeting point: the shell counts
 * a request, the feed watches the count and opens its search. A count rather than a
 * flag, so a second tap while search is already closed is a new event, not a state
 * the shell has to remember to clear.
 */
class MatchesSearchSignal {
    var requests by mutableStateOf(0)
        private set

    fun request() {
        requests += 1
    }
}

val LocalMatchesSearchSignal = staticCompositionLocalOf { MatchesSearchSignal() }
