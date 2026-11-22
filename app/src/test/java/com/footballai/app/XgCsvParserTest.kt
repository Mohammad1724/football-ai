package com.footballai.app

import com.footballai.app.data.remote.XgCsvParser
import com.footballai.app.data.remote.XgHistoryProvider
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XgCsvParserTest {

    private val csv = """
        date,league,home_team,away_team,home_xg,away_xg
        2021-08-13,D1,M'gladbach,Bayern Munich,2.30,2.57
        2021-08-14,D1,Augsburg,Hoffenheim,0.62,3.83
        2021-08-14,D1,Bielefeld,Freiburg,0.58,1.67
    """.trimIndent()

    @Test
    fun `parses every well formed row`() {
        val rows = XgCsvParser.parse(csv)
        assertEquals(3, rows.size)
        val first = rows.first()
        assertEquals(LocalDate.of(2021, 8, 13), first.date)
        assertEquals("M'gladbach", first.homeTeamName)
        assertEquals("Bayern Munich", first.awayTeamName)
        assertEquals(2.30, first.homeXg)
        assertEquals(2.57, first.awayXg)
    }

    @Test
    fun `skips malformed rows instead of failing the file`() {
        val messy = """
            date,league,home_team,away_team,home_xg,away_xg
            2021-08-13,D1,M'gladbach,Bayern Munich,2.30,2.57
            not-a-date,D1,Augsburg,Hoffenheim,0.62,3.83
            2021-08-14,D1,Bielefeld,Freiburg,,
            2021-08-14,D1,Bochum,Mainz,-1.00,0.50
            2021-08-14,D1,Dortmund,Ein Frankfurt,2.10,1.05
        """.trimIndent()
        val rows = XgCsvParser.parse(messy)
        assertEquals(2, rows.size)
        assertEquals("Dortmund", rows.last().homeTeamName)
    }

    @Test
    fun `returns empty for a file without the expected header`() {
        assertTrue(XgCsvParser.parse("a,b,c\n1,2,3").isEmpty())
        assertTrue(XgCsvParser.parse("").isEmpty())
    }

    @Test
    fun `file names follow the mirror layout`() {
        assertEquals("E0-2021-22.csv", XgHistoryProvider.fileName("E0", 2021))
        assertEquals("SP1-2026-27.csv", XgHistoryProvider.fileName("SP1", 2026))
        assertEquals("F1-2009-10.csv", XgHistoryProvider.fileName("F1", 2009))
    }

    @Test
    fun `coverage is exactly the big five`() {
        assertEquals(
            setOf("E0", "SP1", "D1", "I1", "F1"),
            XgHistoryProvider.COVERED_COMPETITIONS,
        )
    }
}
