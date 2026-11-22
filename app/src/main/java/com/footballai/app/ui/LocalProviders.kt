package com.footballai.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.footballai.app.di.AppContainer

/** The single dependency container, provided once at the activity root. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer was not provided")
}
