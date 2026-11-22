package com.footballai.app.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers the last uncaught exception so a defect can be reported without a cable
 * and without a developer console.
 *
 * The app has no crash reporter: everything runs locally, and no user data leaves the
 * device. That is a feature, but it also means a crash reaches the user as "the app
 * closed" and reaches the developer as nothing at all. This writes the stack trace to a
 * private file, which the next launch shows on the settings screen.
 *
 * What is stored is the exception type, message and stack frames — code locations, not
 * personal data. No token, URL or query is part of it.
 */
object CrashLog {

    private const val DIRECTORY = "diagnostics"
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 16_000

    @Volatile
    private var installed = false

    /** Chains onto whatever handler is already present, so the system dialog still works. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("time: $stamp")
            appendLine("thread: ${thread.name}")
            appendLine("device: android ${android.os.Build.VERSION.SDK_INT}")
            appendLine("app: ${context.packageName}")
            appendLine()
            appendLine(error.stackTraceToString())
            // The cause chain is what usually explains the top frame.
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 4) {
                appendLine()
                appendLine("caused by: ${cause::class.java.name}: ${cause.message}")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
        }.take(MAX_CHARS)
        File(directory, FILE_NAME).writeText(text)
    }

    /** The last recorded crash, or `null` when the previous run ended normally. */
    fun last(context: Context): String? {
        val file = File(File(context.filesDir, DIRECTORY), FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { File(File(context.filesDir, DIRECTORY), FILE_NAME).delete() }
    }
}
