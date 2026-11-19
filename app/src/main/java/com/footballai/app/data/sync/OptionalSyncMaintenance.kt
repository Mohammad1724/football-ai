package com.footballai.app.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Optional table and badge work must not delay the fixture/result refresh. */
internal class OptionalSyncMaintenance(
    private val scope: CoroutineScope,
    private val standings: suspend (String, List<String>) -> Unit,
    private val crests: suspend () -> Unit,
) {
    private data class Request(val providerId: String, val keys: List<String>)
    private val lock = Any()
    private var job: Job? = null
    private var active: Request? = null
    private var pending: Request? = null

    fun schedule(providerId: String, keys: List<String>) {
        val request = Request(providerId, keys.distinct().sorted())
        synchronized(lock) {
            if (job?.isActive == true) {
                // Don't build a queue of identical refreshes; preserve a source change.
                pending = if (request == active) null else request
                return
            }
            active = request
            val worker = scope.launch(start = CoroutineStart.LAZY) { run(request) }
            job = worker
            worker.start()
        }
    }

    private suspend fun run(initial: Request) {
        var request = initial
        val currentJob = coroutineContext[Job]
        try {
            while (true) {
                optional { standings(request.providerId, request.keys) }
                optional { crests() }
                synchronized(lock) {
                    val next = pending
                    if (next == null) {
                        active = null
                        job = null
                        return
                    }
                    pending = null
                    active = next
                    request = next
                }
            }
        } finally {
            // A cancelled app scope must not leave this scheduler stuck.
            synchronized(lock) {
                if (job === currentJob) {
                    active = null
                    pending = null
                    job = null
                }
            }
        }
    }

    private suspend fun optional(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional enrichment is independent from the core sync and from each other.
        }
    }
}
