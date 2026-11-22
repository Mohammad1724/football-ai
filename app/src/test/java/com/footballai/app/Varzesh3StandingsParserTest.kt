package com.footballai.app

import com.footballai.app.data.remote.Varzesh3StandingsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class Varzesh3StandingsParserTest {
    @Test
    fun `preserves source order and statistics without recalculating deducted points`() {
        val rows = Varzesh3StandingsParser.parse(
            """{"title":"لیگ آزادگان","teams":[
              {"id":902151,"name":"پارس جنوبی جم","played":10,"wins":6,"losses":0,"draws":4,"points":22,"goalAgainst":4,"goalDifference":8,"goalFor":12,"logo":"https://example.test/a.png"},
              {"id":28,"name":"مس رفسنجان","played":10,"wins":5,"losses":1,"draws":4,"points":18,"goalAgainst":2,"goalDifference":6,"goalFor":8}
            ]}""",
        )
        assertEquals(listOf(1, 2), rows.map { it.rank })
        assertEquals("902151", rows[0].teamKey)
        assertEquals("پارس جنوبی جم", rows[0].teamName)
        assertEquals(10, rows[0].played)
        assertEquals(6, rows[0].wins)
        assertEquals(4, rows[0].draws)
        assertEquals(0, rows[0].losses)
        assertEquals(12, rows[0].goalsFor)
        assertEquals(4, rows[0].goalsAgainst)
        assertEquals(8, rows[0].goalDifference)
        assertEquals(18, rows[1].points)
        assertNull(rows[1].crestUrl)
    }

    @Test
    fun `refuses empty and partial tables`() {
        assertFailsWith<IllegalArgumentException> { Varzesh3StandingsParser.parse("""{"teams":[]}""") }
        assertFailsWith<IllegalArgumentException> {
            Varzesh3StandingsParser.parse("""{"teams":[{"id":1,"name":"X"}]}""")
        }
    }

    @Test
    fun `refuses duplicate team ids`() {
        val team = """{"id":1,"name":"X","played":1,"wins":1,"draws":0,"losses":0,"points":3,"goalFor":1,"goalAgainst":0,"goalDifference":1}"""
        assertFailsWith<IllegalArgumentException> { Varzesh3StandingsParser.parse("""{"teams":[$team,$team]}""") }
    }
}
