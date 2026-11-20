package com.footballai.app

import com.footballai.app.domain.model.EvaluationBlock
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.domain.model.evaluationBlock
import kotlin.test.Test
import kotlin.test.assertEquals

class PredictionEvaluationTest {
    private fun block(status: MatchStatus = MatchStatus.FINISHED, scope: String? = "REGULATION", version: String = "v2",
        kind: PredictionKind = PredictionKind.PRE_MATCH, created: Long = 99, home: Int? = 0, away: Int? = 1) =
        evaluationBlock(status, scope, version, "v2", kind, created, 100, home, away)

    @Test fun `only the actual grader prerequisites permit grading`() {
        assertEquals(EvaluationBlock.MATCH_NOT_FINISHED, block(status = MatchStatus.LIVE))
        assertEquals(EvaluationBlock.UNVERIFIED_REGULATION, block(scope = null))
        assertEquals(EvaluationBlock.UNVERIFIED_REGULATION, block(scope = "EXTRA_TIME"))
        assertEquals(EvaluationBlock.OLD_MODEL, block(version = "v1"))
        assertEquals(EvaluationBlock.LATE_PRE_MATCH, block(created = 100))
        assertEquals(EvaluationBlock.MISSING_SCORE, block(home = null))
        assertEquals(EvaluationBlock.NONE, block())
        assertEquals(EvaluationBlock.NONE, block(kind = PredictionKind.LIVE, created = 101))
    }
}
