package com.footballai.app

import com.footballai.app.data.sync.OptionalSyncMaintenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OptionalSyncMaintenanceTest {
    @Test fun `scheduling does not wait for slow badge searches and coalesces duplicates`() = runTest {
        val hold = CompletableDeferred<Unit>()
        var standings = 0
        var crests = 0
        val maintenance = OptionalSyncMaintenance(this,
            standings = { _, _ -> standings++ },
            crests = { crests++; hold.await() },
        )
        maintenance.schedule("source", listOf("B", "A"))
        runCurrent()
        assertEquals(1, standings)
        assertEquals(1, crests)
        assertFalse(hold.isCompleted)
        maintenance.schedule("source", listOf("A", "B"))
        hold.complete(Unit)
        runCurrent()
        assertEquals(1, standings)
        assertEquals(1, crests)
        maintenance.schedule("source", listOf("A", "B"))
        runCurrent()
        assertEquals(2, standings)
    }

    @Test fun `source change waits for running maintenance and newest request wins`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val sources = mutableListOf<String>()
        val maintenance = OptionalSyncMaintenance(this,
            standings = { source, _ -> sources += source; if (source == "old") hold.await() },
            crests = {},
        )
        maintenance.schedule("old", emptyList())
        runCurrent()
        maintenance.schedule("middle", emptyList())
        maintenance.schedule("new", emptyList())
        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "new"), sources)
    }

    @Test fun `a table failure does not prevent crest work or future runs`() = runTest {
        var badges = 0
        val maintenance = OptionalSyncMaintenance(this,
            standings = { _, _ -> error("network") },
            crests = { badges++ },
        )
        maintenance.schedule("source", emptyList())
        runCurrent()
        maintenance.schedule("source", emptyList())
        runCurrent()
        assertEquals(2, badges)
    }
}
