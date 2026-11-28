package com.footballai.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import com.footballai.app.v2.V2ViewModel
import com.footballai.app.v2.data.V2ThemeMode
import com.footballai.app.v2.theme.FootballAiV2Theme
import com.footballai.app.v2.ui.V2App
import java.util.Locale

/**
 * Entry point of the rewritten app (v2 shell). The old UI/data layer still
 * exists in the repo but is no longer wired to anything; it is removed in a
 * later slice.
 *
 * The rewrite is Persian-first: the content context is wrapped in the fa
 * locale and the layout direction is pinned to RTL, independent of the
 * device language.
 */
class MainActivity : ComponentActivity() {

    private val v2ViewModel: V2ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settings by v2ViewModel.settings.collectAsState()
            val darkTheme = when (settings.themeMode) {
                V2ThemeMode.AUTO -> isSystemInDarkTheme()
                V2ThemeMode.DAY -> false
                V2ThemeMode.NIGHT -> true
            }

            // The status-bar icons are drawn by the system, not the app, so
            // the theme has to tell the system which way to draw them.
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    view.context.findActivity()?.window?.let { window ->
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                    }
                }
            }

            val baseContext = LocalContext.current
            val persianContext = remember(baseContext) { baseContext.withLocale("fa") }
            val persianConfiguration = remember(persianContext) {
                persianContext.resources.configuration
            }

            CompositionLocalProvider(
                LocalContext provides persianContext,
                LocalConfiguration provides persianConfiguration,
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                FootballAiV2Theme(darkTheme = darkTheme) {
                    V2App(viewModel = v2ViewModel)
                }
            }
        }
    }

    /**
     * Refresh the mirror data on entering the app, but only when it is
     * stale — the repository decides, so a quick return costs nothing and
     * opening the app never waits on the network.
     */
    override fun onStart() {
        super.onStart()
        v2ViewModel.onAppStart()
    }

    /** The Compose view's context is wrapped once per locale, so the activity is not direct. */
    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun Context.withLocale(languageTag: String): Context {
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return createConfigurationContext(configuration)
    }
}
