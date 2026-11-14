package com.footballai.app.data.settings

enum class AppLanguage(val tag: String) {
    SYSTEM("system"),
    PERSIAN("fa"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromTag(tag: String): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val activeProviderId: String = "",
    val followedCompetitionKeys: Set<String> = emptySet(),
    val dailyAnalysisEnabled: Boolean = true,
    val livePollingEnabled: Boolean = false,
    val backgroundSyncEnabled: Boolean = true,
    /** Refresh by itself when the app is opened and what is stored is not current. */
    val autoRefreshOnOpen: Boolean = true,
    val autoPredictEnabled: Boolean = true,
    val dataSaverEnabled: Boolean = false,
    val lastHistoricalImportEpochMs: Long? = null,
    val lastSyncEpochMs: Long? = null,
    val onboarded: Boolean = false,
)
