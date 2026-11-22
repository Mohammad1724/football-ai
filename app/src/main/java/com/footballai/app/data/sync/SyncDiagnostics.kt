package com.footballai.app.data.sync

import com.footballai.app.data.local.dao.SyncMetaDao
import com.footballai.app.data.local.entity.SyncMetaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local-only timings. Network and database work within a phase are measured together. */
@Serializable
data class SyncTimingReport(
    val atEpochMs: Long,
    val mode: String,
    val status: String,
    val totalMs: Long,
    val phasesMs: Map<String, Long>,
)

class SyncTimingRecorder(private val nanoTime: () -> Long = System::nanoTime) {
    private val start = nanoTime()
    private val phases = linkedMapOf<String, Long>()

    suspend fun <T> measure(phase: String, block: suspend () -> T): T {
        val begun = nanoTime()
        try {
            return block()
        } finally {
            val elapsed = ((nanoTime() - begun).coerceAtLeast(0L)) / 1_000_000L
            phases[phase] = (phases[phase] ?: 0L) + elapsed
        }
    }

    fun report(atEpochMs: Long, mode: String, status: String) = SyncTimingReport(
        atEpochMs, mode, status,
        ((nanoTime() - start).coerceAtLeast(0L)) / 1_000_000L,
        phases.toMap(),
    )
}

/** Only the latest run is kept on the device; no telemetry is sent anywhere. */
class SyncDiagnosticsStore(private val dao: SyncMetaDao) {
    private val json = Json { ignoreUnknownKeys = true }
    val reports: Flow<SyncTimingReport?> = dao.observeAll().map { entries ->
        entries.firstOrNull { it.key == KEY }?.value?.let {
            runCatching { json.decodeFromString<SyncTimingReport>(it) }.getOrNull()
        }
    }.distinctUntilChanged()
    suspend fun save(report: SyncTimingReport) {
        dao.put(SyncMetaEntity(KEY, json.encodeToString(report), report.atEpochMs))
    }
    companion object { private const val KEY = "sync-timing-v1" }
}
