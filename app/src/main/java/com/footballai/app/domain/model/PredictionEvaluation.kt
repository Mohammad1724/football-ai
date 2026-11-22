package com.footballai.app.domain.model

/** Exact prerequisites used by the automatic grader; never infer an unknown score scope. */
internal enum class EvaluationBlock {
    MATCH_NOT_FINISHED, UNVERIFIED_REGULATION, OLD_MODEL, LATE_PRE_MATCH, MISSING_SCORE, NONE
}

internal fun evaluationBlock(
    status: MatchStatus, scoreScope: String?, modelVersion: String, currentModelVersion: String,
    kind: PredictionKind, createdAt: Long, kickoff: Long, homeGoals: Int?, awayGoals: Int?,
): EvaluationBlock = when {
    !status.isFinished -> EvaluationBlock.MATCH_NOT_FINISHED
    scoreScope != "REGULATION" -> EvaluationBlock.UNVERIFIED_REGULATION
    modelVersion != currentModelVersion -> EvaluationBlock.OLD_MODEL
    kind == PredictionKind.PRE_MATCH && createdAt >= kickoff -> EvaluationBlock.LATE_PRE_MATCH
    homeGoals == null || awayGoals == null -> EvaluationBlock.MISSING_SCORE
    else -> EvaluationBlock.NONE
}
