package com.footballai.app.data.remote

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Loads a club crest from the address the source published.
 *
 * Why this exists instead of an image library: the badges stayed empty on a real phone
 * while the same address answered normally, and an image library that swallows the reason
 * leaves nothing to look at. This loader is deliberately small and observable — it uses the
 * one HTTP stack the rest of the app already trusts, asks with a normal browser user agent
 * (a CDN is entitled to refuse a library's default one), checks the status code instead of
 * treating every response as an image, and reports the outcome through [probe] so the
 * Settings screen can print the truth instead of the app guessing at it.
 *
 * The decoded bitmap is cached in memory, not on disk: a crest is 10–30 KB on the wire and
 * at most 250 px after decoding, so a handful of badges cost a fraction of one screenshot.
 * A failed load is cached too, so a broken address is not re-requested on every scroll.
 */
object CrestImages {

    /** Wide enough for a badge on a 3x screen, small enough that forty of them are cheap. */
    private const val DECODE_SAMPLE = 2

    private const val CACHE_ENTRIES = 64

    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // A crest is a few kilobytes, but on a slow mobile link a few kilobytes can
            // take longer than a desktop timeout. The timeouts are generous so a slow
            // connection still gets its badge instead of a monogram.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_ENTRIES) {}

    /** Retry transient failures even if the user never scrolls away from the row. */
    const val RETRY_DELAY_MS = 5L * 60L * 1000L
    private val failed = LinkedHashMap<String, Long>()
    private val failedLock = Any()
    private val failedLimit = 128

    fun forgetFailures() = synchronized(failedLock) { failed.clear() }

    private fun isKnownBad(url: String): Boolean = synchronized(failedLock) {
        val until = failed[url] ?: return@synchronized false
        if (android.os.SystemClock.elapsedRealtime() >= until) {
            failed.remove(url)
            false
        } else true
    }

    private fun rememberBad(url: String) = synchronized(failedLock) {
        failed.remove(url)
        if (failed.size >= failedLimit) failed.remove(failed.keys.first())
        failed[url] = android.os.SystemClock.elapsedRealtime() + RETRY_DELAY_MS
    }

    /**
     * One real crest address, asked for by the diagnostics row in Settings.
     *
     * FC Bayern's published crest: a club the app already lists, so the answer describes
     * the request the badges themselves make rather than a made-up test URL.
     */
    val probeAddress: String =
        com.footballai.app.domain.catalog.CrestUrls.forClub(
            providerId = "espn-open",
            providerKey = "132",
            kind = com.footballai.engine.model.TeamKind.CLUB,
        ) ?: "https://a.espncdn.com/i/teamlogos/soccer/500/132.png"

    /** The address that failed, with the reason the phone saw. Used by the diagnostics row. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * The same picture through a public image mirror.
     *
     * A crest address that the phone cannot open is not a wrong address: a feed can be
     * reachable while the image host behind it is not, and that is invisible from inside
     * the app. The mirror receives only the address of a club logo — no account, no
     * device id, nothing about the reader — and is used only after the source itself has
     * refused, never first.
     */
    fun mirrorUrl(url: String): String {
        val host = url.trim().removePrefix("https://").removePrefix("http://")
        // Slashes are kept readable: the mirror takes a plain address, and an encoded
        // "a.espncdn.com%2Fi%2F..." is accepted by some paths and rejected by others.
        val encoded = java.net.URLEncoder.encode(host, "UTF-8").replace("%2F", "/")
        return "https://images.weserv.nl/?url=$encoded&w=120&h=120&fit=contain&output=png"
    }

    suspend fun load(url: String): ImageBitmap? {
        val key = url.trim()
        if (key.isEmpty()) return null
        cache.get(key)?.let { cached -> return cached }
        if (isKnownBad(key)) return null
        return withContext(Dispatchers.IO) {
            // A successful HTTP response may still contain HTML or an unsupported image.
            // Try the mirror after a decode failure as well as after a network failure.
            for (address in listOf(key, mirrorUrl(key))) {
                val bytes = fetch(address) ?: continue
                val options = BitmapFactory.Options().apply { inSampleSize = DECODE_SAMPLE }
                val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
                    .getOrNull()
                if (bitmap != null) {
                    return@withContext bitmap.asImageBitmap().also { cache.put(key, it) }
                }
                lastFailure = "$address · decoded to nothing"
            }
            rememberBad(key)
            null
        }
    }

    /**
     * One plain GET, with the status kept.
     *
     * A 404 and a 403 are different problems — one means the address is wrong, the other
     * means the phone was turned away — and the diagnostics row prints which one it was.
     */
    suspend fun probe(url: String): String = withContext(Dispatchers.IO) {
        val direct = status(url)
        val mirror = status(mirrorUrl(url))
        "source: $direct / mirror: $mirror"
    }

    /** One request, status kept: a 404 is a wrong address, a 403 is a refusal. */
    private fun status(url: String): String {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", browserUserAgent)
                .header("Accept", "image/avif,image/webp,image/png,image/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty().substringBefore(';')
                "HTTP ${response.code} ${type}".trim()
            }
        }.getOrElse { error -> "failed (${error::class.java.simpleName})" }
    }

    private fun fetch(url: String): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", browserUserAgent)
                .header("Accept", "image/avif,image/webp,image/png,image/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastFailure = "$url · HTTP ${response.code}"
                    return null
                }
                val body = response.body ?: run {
                    lastFailure = "$url · empty body"
                    return null
                }
                body.bytes().takeIf { it.isNotEmpty() } ?: run {
                    lastFailure = "$url · zero bytes"
                    null
                }
            }
        }.getOrElse { error ->
            lastFailure = "$url · ${error::class.java.simpleName}"
            null
        }
    }
}
