package com.footballai.app.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Injectable clock: every prediction stores the instant it was produced. */
interface TimeProvider {
    fun nowEpochMs(): Long

    companion object {
        val System = object : TimeProvider {
            override fun nowEpochMs(): Long = java.lang.System.currentTimeMillis()
        }
    }
}

data class DispatchersProvider(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)

object TimeConstants {
    const val SECOND_MS = 1_000L
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
}
