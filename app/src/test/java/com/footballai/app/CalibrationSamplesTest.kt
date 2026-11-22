package com.footballai.app

import com.footballai.app.data.repository.rawCalibrationProbabilities
import com.footballai.app.data.repository.toRawCalibrationSample
import com.footballai.app.data.local.Serialized
import com.footballai.app.data.local.entity.PredictionEntity
import com.footballai.app.domain.model.ConfidenceView
import com.footballai.engine.model.MatchOutcome
import io.mockk.every
import io.mockk.mockk
import com.footballai.app.domain.model.CalibrationView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalibrationSamplesTest {
    private val published = Triple(0.6, 0.2, 0.2)
    private val raw = Triple(0.8, 0.1, 0.1)
    private val info = CalibrationView(method = "TEMPERATURE", rawHomeWin = 0.8, rawDraw = 0.1, rawAwayWin = 0.1)

    @Test fun `trained samples use raw rather than calibrated or market blended outputs`() {
        assertEquals(raw, rawCalibrationProbabilities(published, true, info, true))
        assertEquals(raw, rawCalibrationProbabilities(published, true, info.copy(method = "NONE"), true))
    }

    @Test fun `serialized corrected or market blended forecast contributes its raw triple`() {
        val entity = mockk<PredictionEntity>()
        every { entity.actualHomeGoals } returns 1
        every { entity.actualAwayGoals } returns 2
        every { entity.homeWin } returns 0.6
        every { entity.draw } returns 0.2
        every { entity.awayWin } returns 0.2
        every { entity.oddsHome } returns 2.0
        every { entity.oddsDraw } returns 3.0
        every { entity.oddsAway } returns 4.0
        every { entity.confidenceJson } returns Serialized.encodeConfidence(ConfidenceView(level = "LOW"), info)
        val sample = toRawCalibrationSampleFor(entity)
        assertEquals(raw.first, sample?.homeWin)
        assertEquals(raw.second, sample?.draw)
        assertEquals(raw.third, sample?.awayWin)
        assertEquals(MatchOutcome.AWAY_WIN, sample?.actual)
    }

    private fun toRawCalibrationSampleFor(entity: PredictionEntity) = entity.toRawCalibrationSample()

    @Test fun `legacy samples are accepted only when unaltered and metadata is known`() {
        assertEquals(published, rawCalibrationProbabilities(published, true, null, false))
        assertNull(rawCalibrationProbabilities(published, true, null, true))
        assertNull(rawCalibrationProbabilities(published, false, null, false))
        assertNull(rawCalibrationProbabilities(published, true, info.copy(rawDraw = Double.NaN), false))
        assertNull(rawCalibrationProbabilities(published, true, info.copy(rawDraw = 0.9), false))
    }
}
