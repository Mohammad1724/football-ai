package com.footballai.app.v2.data

import android.content.Context
import com.footballai.app.v2.V2Leagues
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class V2DataState(
    /** Parsed bundles keyed by league slug; empty until the first load lands. */
    val bundles: Map<String, V2BundleDto> = emptyMap(),
    val live: V2LiveDto? = null,
    /** Newest `generatedAt` across the loaded bundles — the data's own freshness. */
    val dataGeneratedAt: Instant? = null,
    val refreshing: Boolean = false,
    /** True when the last refresh attempt failed; cached/seed data stays on screen. */
    val refreshFailed: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * The rewrite's single data source (spec step 2): the FotMob mirror published
 * as static v2 JSON in the football-ai-data repo.
 *
 * Loading is offline-first: bundles ship as an assets seed, a refresh writes
 * them into an on-device cache, and the cache wins over the seed on the next
 * launch. Opening the app never waits on the network — a refresh only runs
 * when asked (settings button) or when the cached data is stale (on start).
 */
class V2DataRepository(
    context: Context,
    private val settingsRepository: V2SettingsRepository,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient(),
) {

    private val appContext: Context = context.applicationContext
    private val cacheDir: File = File(appContext.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _state = MutableStateFlow(V2DataState())
    val state: StateFlow<V2DataState> = _state.asStateFlow()

    /** Loads seed/cache from disk. Safe to call repeatedly; later calls are no-ops. */
    fun loadInitial() {
        if (_state.value.loaded) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { readAll() }
            _state.update { current ->
                if (current.loaded) current else loaded.copy(loaded = true)
            }
        }
    }

    /**
     * Re-fetches every bundle and the live file from the mirror's raw URLs.
     * A league that fails keeps its previously loaded copy; the refresh only
     * counts as failed when nothing could be fetched at all.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, refreshFailed = false) }
        scope.launch {
            var anySuccess = false
            val fresh = mutableMapOf<String, V2BundleDto>()
            var freshLive: V2LiveDto? = null
            withContext(Dispatchers.IO) {
                for (league in V2Leagues.DEFAULT_ORDER) {
                    val text = fetchAndCache("$RAW_BASE/${league.slug}/bundle.json", "${league.slug}.json")
                    if (text != null) {
                        parseBundle(text)?.let { fresh[league.slug] = it }
                        if (fresh.containsKey(league.slug)) anySuccess = true
                    }
                }
                val liveText = fetchAndCache("$RAW_BASE/live.json", LIVE_FILE_NAME)
                if (liveText != null) {
                    freshLive = parseLive(liveText)
                    if (freshLive != null) anySuccess = true
                }
            }
            if (anySuccess) {
                settingsRepository.setLastRefresh(System.currentTimeMillis())
                _state.update { current ->
                    val merged = current.bundles + fresh
                    current.copy(
                        bundles = merged,
                        live = freshLive ?: current.live,
                        dataGeneratedAt = newestGeneratedAt(merged),
                        refreshing = false,
                        refreshFailed = false,
                        loaded = true,
                    )
                }
            } else {
                _state.update { it.copy(refreshing = false, refreshFailed = true) }
            }
        }
    }

    /** Refresh on start only when the mirror data on disk is older than [STALE_MS]. */
    fun refreshIfStale(lastRefreshEpochMs: Long?) {
        loadInitial()
        val last = lastRefreshEpochMs ?: 0L
        if (System.currentTimeMillis() - last > STALE_MS) refresh()
    }

    private fun readAll(): V2DataState {
        val bundles = mutableMapOf<String, V2BundleDto>()
        for (league in V2Leagues.DEFAULT_ORDER) {
            val text = readCachedOrSeed("${league.slug}.json") ?: continue
            parseBundle(text)?.let { bundles[league.slug] = it }
        }
        val live = readCachedOrSeed(LIVE_FILE_NAME)?.let { parseLive(it) }
        return V2DataState(
            bundles = bundles,
            live = live,
            dataGeneratedAt = newestGeneratedAt(bundles),
        )
    }

    /** Cache wins over the bundled seed: it is the newer copy of the same file. */
    private fun readCachedOrSeed(fileName: String): String? {
        val cached = File(cacheDir, fileName)
        if (cached.isFile) {
            runCatching { return cached.readText() }
        }
        return runCatching {
            appContext.assets.open("$ASSETS_DIR/$fileName").bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun fetchAndCache(url: String, fileName: String): String? = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val text = (if (response.isSuccessful) response.body?.string() else null)
                ?: return@use null
            // Only cache content that actually parses as the expected document,
            // so a captive-portal HTML page can never poison the cache.
            val valid = if (fileName == LIVE_FILE_NAME) {
                parseLive(text) != null
            } else {
                parseBundle(text) != null
            }
            if (!valid) return@use null
            File(cacheDir, fileName).writeText(text)
            text
        }
    }.getOrNull()

    private fun parseBundle(text: String): V2BundleDto? =
        runCatching { json.decodeFromString(V2BundleDto.serializer(), text) }.getOrNull()

    private fun parseLive(text: String): V2LiveDto? =
        runCatching { json.decodeFromString(V2LiveDto.serializer(), text) }.getOrNull()

    private fun newestGeneratedAt(bundles: Map<String, V2BundleDto>): Instant? =
        bundles.values.mapNotNull { runCatching { Instant.parse(it.generatedAt) }.getOrNull() }
            .maxOrNull()

    companion object {
        const val RAW_BASE =
            "https://raw.githubusercontent.com/Mohammad1724/football-ai-data/main/data/v2"
        private const val ASSETS_DIR = "v2data"
        private const val CACHE_DIR_NAME = "v2data"
        private const val LIVE_FILE_NAME = "live.json"

        /** Bundles are regenerated daily; half a day old is already worth a refresh. */
        private const val STALE_MS = 12L * 60L * 60L * 1000L
    }
}
