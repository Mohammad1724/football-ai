package com.footballai.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.data.settings.AppSettings
import com.footballai.app.data.settings.ThemeMode
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.navigation.FootballAiApp
import com.footballai.app.ui.theme.FootballAiTheme
import java.util.Locale

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

    private fun Context.withLocale(languageTag: String): Context {
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return createConfigurationContext(configuration)
    }
}
