package com.footballai.app

import com.footballai.app.data.local.Serialized
import com.footballai.app.domain.model.CalibrationView
import com.footballai.app.domain.model.ConfidenceView
import com.footballai.app.domain.model.ExplanationView
import com.footballai.app.domain.model.LiveSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stored predictions must survive "unknown" numbers.
 *
 * The engine uses `Double.NaN` for "not measured" — no calibration samples yet, no
 * measured calibration error, no expected goals. Kotlin's JSON encoder rejects NaN by
 * default, so saving such a prediction threw, and because the save happens inside a
 * screen coroutine the exception closed the app. These tests pin the behaviour that the
 * store can always write and read its own values back.
 */
class SerializedPredictionTest {

    @Test
    fun `a confidence with unmeasured values is stored instead of throwing`() {
        val confidence = ConfidenceView(
            level = "LOW",
            gaps = listOf("NOT_CALIBRATED", "SMALL_TEAM_SAMPLE"),
            uncertainty = 0.87,
            topGap = Double.NaN,
            effectiveSample = 3,
            calibrationSamples = 0,
            calibrationError = Double.NaN,
            dataCompleteness = Double.NaN,
            freshnessMinutes = 12,
        )
        val encoded = Serialized.encodeConfidence(confidence, null)
        assertNotNull(encoded)
        // NaN must not appear as a JSON literal; it is written as an absent value.
        assertTrue(!encoded.contains("NaN"), "encoded JSON kept a NaN literal: $encoded")

        val (decoded, calibration) = Serialized.decodeConfidence(encoded)
        assertNotNull(decoded)
        assertEquals("LOW", decoded.level)
        assertEquals(0.87, decoded.uncertainty)
        assertTrue(decoded.topGap.isNaN())
        assertTrue(decoded.calibrationError.isNaN())
        assertEquals(3, decoded.effectiveSample)
        assertEquals(12L, decoded.freshnessMinutes)
        assertNull(calibration)
    }

    @Test
    fun `calibration that was never applied round trips without inventing a number`() {
        val confidence = ConfidenceView(level = "INSUFFICIENT", uncertainty = 0.5)
        val calibration = CalibrationView(
            method = "TEMPERATURE",
            temperature = 1.7,
            sampleCount = 0,
            holdoutCount = 0,
            logLossBefore = Double.NaN,
            logLossAfter = Double.NaN,
            rawHomeWin = 0.4,
            rawDraw = Double.NaN,
            rawAwayWin = 0.3,
        )
        val encoded = Serialized.encodeConfidence(confidence, calibration)
        val (_, decodedCalibration) = Serialized.decodeConfidence(encoded)
        assertNotNull(decodedCalibration)
        assertEquals("TEMPERATURE", decodedCalibration.method)
        assertEquals(1.7, decodedCalibration.temperature)
        assertTrue(decodedCalibration.logLossBefore.isNaN())
        assertEquals(0.4, decodedCalibration.rawHomeWin)
    }

    @Test
    fun `infinite values are stored as unknown, not as infinity`() {
        val encoded = Serialized.encodeConfidence(
            ConfidenceView(level = "LOW", uncertainty = Double.POSITIVE_INFINITY),
            CalibrationView(method = "NONE", temperature = Double.NEGATIVE_INFINITY),
        )
        assertNotNull(encoded)
        assertTrue(!encoded.contains("Infinity"), "encoded JSON kept an infinity: $encoded")
        val (decoded, calibration) = Serialized.decodeConfidence(encoded)
        assertNotNull(decoded)
        assertTrue(decoded.uncertainty.isNaN())
        assertNotNull(calibration)
        // A missing temperature falls back to the neutral 1.0 rather than NaN.
        assertEquals(1.0, calibration.temperature)
    }

    @Test
    fun `a live snapshot with a degenerate number still round trips`() {
        val snapshot = LiveSnapshot(
            minute = 63,
            homeGoals = 1,
            awayGoals = 0,
            remainingMinutes = 27,
            remainingHomeExpectedGoals = Double.NaN,
            remainingAwayExpectedGoals = 0.4,
            nextGoalHome = 0.3,
            nextGoalAway = 0.2,
            noMoreGoals = Double.NaN,
            remainingOver = mapOf(1.5 to 0.6),
            adjustments = listOf("RED_CARD"),
        )
        val encoded = Serialized.encodeLiveSnapshot(snapshot)
        assertNotNull(encoded)
        val decoded = Serialized.decodeLiveSnapshot(encoded)
        assertNotNull(decoded)
        assertEquals(63, decoded.minute)
        assertTrue(decoded.remainingHomeExpectedGoals.isNaN())
        assertEquals(0.6, decoded.remainingOver[1.5])
        assertEquals(listOf("RED_CARD"), decoded.adjustments)
    }

    @Test
    fun `an explanation weight that is not a number does not break the list`() {
        val encoded = Serialized.encodeExplanations(
            listOf(
                ExplanationView("FORM_HOME", 0.2, emptyMap()),
                ExplanationView("ELO_GAP", Double.NaN, mapOf("diff" to "40")),
            ),
        )
        val decoded = Serialized.decodeExplanations(encoded)
        assertEquals(2, decoded.size)
        assertEquals("FORM_HOME", decoded[0].code)
        assertEquals(0.2, decoded[0].weight)
        assertTrue(decoded[1].weight.isNaN())
    }
}
