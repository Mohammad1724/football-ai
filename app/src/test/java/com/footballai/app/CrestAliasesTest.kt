package com.footballai.app

import com.footballai.app.data.remote.CrestSearchAliases
import com.footballai.app.domain.catalog.TeamNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrestAliasesTest {
    @Test fun `only the specified league and fixture source can expand a short name`() {
        assertEquals("Airbus UK Broughton", CrestSearchAliases.lookup("football-data-co-uk", listOf("WAL1"), "Airbus"))
        assertEquals("The New Saints", CrestSearchAliases.lookup("football-data-co-uk", listOf("WAL1"), "TNS"))
        assertEquals("Puszcza Niepolomice", CrestSearchAliases.lookup("football-data-co-uk", listOf("POL2"), "Puszcza"))
        assertEquals("Miedz Legnica", CrestSearchAliases.lookup("football-data-co-uk", listOf("POL2"), "Legnica"))
        assertNull(CrestSearchAliases.lookup("football-data-co-uk", listOf("WAL3"), "Airbus"))
        assertNull(CrestSearchAliases.lookup("espn-open", listOf("WAL1"), "Airbus"))
        assertNull(CrestSearchAliases.lookup("football-data-co-uk", listOf("WAL1"), "unknown"))
    }

    @Test fun `scandinavian and polish letters are compared not removed`() {
        assertEquals(TeamNames.normalizeNameKey("Bodo/Glimt"), TeamNames.normalizeNameKey("Bodø/Glimt"))
        assertEquals(TeamNames.normalizeNameKey("LKS Lodz"), TeamNames.normalizeNameKey("ŁKS Łódź"))
        assertEquals(TeamNames.normalizeNameKey("Chrobry Glogow"), TeamNames.normalizeNameKey("Chrobry Głogów"))
    }
}
