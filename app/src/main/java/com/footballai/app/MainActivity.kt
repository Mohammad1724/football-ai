package com.footballai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                AppLanguage.SYSTEM -> Locale.getDefault().language
                AppLanguage.PERSIAN -> "fa"
                AppLanguage.ENGLISH -> "en"
            }
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalLanguageTag provides languageTag,
            ) {
                FootballAiTheme(darkTheme = darkTheme, languageTag = languageTag) {
                    FootballAiApp()
                }
            }
        }
    }
}
