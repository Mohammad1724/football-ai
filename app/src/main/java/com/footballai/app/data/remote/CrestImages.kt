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
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_ENTRIES) {}

    /**
     * Addresses that already failed.
     *
     * Android's [LruCache] cannot hold a null value, so failures get their own small set:
     * without it a missing crest is requested again on every scroll, which is exactly the
     * kind of silent waste that makes a list feel heavy.
     */
    private val failed = LinkedHashSet<String>()
    private val failedLock = Any()

    private fun isKnownBad(url: String): Boolean = synchronized(failedLock) { url in failed }

    private fun rememberBad(url: String) = synchronized(failedLock) {
        if (failed.size >= CACHE_ENTRIES) failed.remove(failed.first())
        failed.add(url)
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

    suspend fun load(url: String): ImageBitmap? {
        val key = url.trim()
        if (key.isEmpty()) return null
        cache.get(key)?.let { cached -> return cached }
        if (isKnownBad(key)) return null
        return withContext(Dispatchers.IO) {
            val bytes = fetch(key) ?: run {
                rememberBad(key)
                return@withContext null
            }
            val options = BitmapFactory.Options().apply { inSampleSize = DECODE_SAMPLE }
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
                .getOrNull()
            if (bitmap == null) {
                lastFailure = "$key · decoded to nothing"
                rememberBad(key)
                return@withContext null
            }
            bitmap.asImageBitmap().also { image -> cache.put(key, image) }
        }
    }

    /**
     * One plain GET, with the status kept.
     *
     * A 404 and a 403 are different problems — one means the address is wrong, the other
     * means the phone was turned away — and the diagnostics row prints which one it was.
     */
    suspend fun probe(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", browserUserAgent)
            .header("Accept", "image/avif,image/webp,image/png,image/*;q=0.8")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val size = response.body?.contentLength() ?: -1L
                val type = response.header("Content-Type").orEmpty()
                "HTTP ${response.code} · $type · ${if (size >= 0) "$size bytes" else "size unknown"}"
            }
        }.getOrElse { error -> "request failed: ${error::class.java.simpleName}" }
    }

    private fun fetch(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", browserUserAgent)
            .header("Accept", "image/avif,image/webp,image/png,image/*;q=0.8")
            .build()
        return runCatching {
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
