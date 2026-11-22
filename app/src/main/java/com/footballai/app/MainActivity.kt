package com.footballai.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.data.settings.AppSettings
import com.footballai.app.data.settings.ThemeMode
import com.footballai.app.data.sync.SyncTrigger
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.navigation.FootballAiApp
import com.footballai.app.ui.theme.FootballAiTheme
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FootballAiApplication).container

        setContent {
            val settings by container.settingsRepository.settings
                .collectAsState(initial = AppSettings())
            val languageTag = when (settings.language) {
                AppLanguage.SYSTEM -> Locale.getDefault().toLanguageTag()
                AppLanguage.PERSIAN -> "fa"
                AppLanguage.ENGLISH -> "en"
            }
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // The clock and the battery in the status bar are drawn by the system, not by
            // this app, so they do not follow the in-app theme on their own. With a
            // transparent status bar and a dark app theme on a light phone the icons stayed
            // dark-on-dark and disappeared; the theme switch has to tell the system which
            // way to draw them.
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

            // Layout direction alone is not enough: stringResource() reads the resources of
            // the local Context, so the in-app language is applied by wrapping the content
            // in a Context with the requested locale. That keeps the activity alive (no
            // recreation) while every resource string switches language.
            val baseContext = LocalContext.current
            val localizedContext = remember(baseContext, languageTag) {
                baseContext.withLocale(languageTag)
            }
            val localizedConfiguration = remember(localizedContext) {
                localizedContext.resources.configuration
            }

            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalLanguageTag provides languageTag,
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration,
            ) {
                FootballAiTheme(darkTheme = darkTheme, languageTag = languageTag) {
                    FootballAiApp()
                }
            }
        }
    }

    /**
     * Refresh on entering the app, but only when there is a reason.
     *
     * onStart covers both a cold launch and a return from the background (a phone in a
     * pocket for two hours must not show yesterday's fixtures). The coordinator decides
     * whether anything is actually stale, so this costs nothing when the data is current,
     * and it is a no-op while another sync is already running.
     */
    override fun onStart() {
        super.onStart()
        val container = (application as FootballAiApplication).container
        lifecycleScope.launch {
            container.autoSyncCoordinator.syncIfStale(SyncTrigger.RESUME)
        }
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
