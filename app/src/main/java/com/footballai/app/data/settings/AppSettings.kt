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
    val livePollingEnabled: Boolean = true,
    val backgroundSyncEnabled: Boolean = true,
    val autoPredictEnabled: Boolean = true,
    val dataSaverEnabled: Boolean = false,
    val lastHistoricalImportEpochMs: Long? = null,
    val lastSyncEpochMs: Long? = null,
    val onboarded: Boolean = false,
)
