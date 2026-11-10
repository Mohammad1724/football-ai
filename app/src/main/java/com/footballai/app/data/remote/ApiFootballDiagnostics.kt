package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import kotlinx.serialization.json.*
import java.util.Locale

/** Do not expose provider bodies: they may echo credentials or account details. */
object ApiFootballDiagnostics {
    private val json = Json { ignoreUnknownKeys = true }

    fun error(body: String): AppError? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return AppError(ErrorCode.PARSE_ERROR)
        val errors = root["errors"] ?: return null
        if (errors is JsonNull || errors is JsonObject && errors.isEmpty() || errors is JsonArray && errors.isEmpty()) return null
        val fields = (errors as? JsonObject)?.keys.orEmpty().map { it.lowercase(Locale.ROOT) }
        val text = errors.toString().lowercase(Locale.ROOT)
        val code = when {
            "ratelimit" in fields || "rate limit" in text || "per minute" in text || "too many requests" in text -> ErrorCode.RATE_LIMITED
            "requests" in fields || "daily" in text || "quota" in text -> ErrorCode.QUOTA_EXHAUSTED
            "plan" in fields || "subscription" in fields || "access" in fields || "free plan" in text || "not subscribed" in text || "season" in text && ("access" in text || "available" in text) -> ErrorCode.PLAN_RESTRICTED
            "token" in fields || "key" in fields || "apikey" in fields || "api key" in text || "api-key" in text -> ErrorCode.INVALID_API_KEY
            else -> ErrorCode.REQUEST_FAILED
        }
        return AppError(code)
    }

    /** /status has an OBJECT response, unlike the array fixture endpoints. */
    fun accountError(body: String): AppError? {
        error(body)?.let { return it }
        val response = runCatching { json.parseToJsonElement(body).jsonObject["response"] as? JsonObject }.getOrNull()
            ?: return AppError(ErrorCode.PARSE_ERROR)
        val subscription = response["subscription"] as? JsonObject ?: return AppError(ErrorCode.PARSE_ERROR)
        val active = (subscription["active"] as? JsonPrimitive)?.booleanOrNull
            ?: return AppError(ErrorCode.PARSE_ERROR)
        if (!active) return AppError(ErrorCode.SUBSCRIPTION_INACTIVE)
        val requests = response["requests"] as? JsonObject ?: return AppError(ErrorCode.PARSE_ERROR)
        val used = (requests["current"] as? JsonPrimitive)?.intOrNull ?: return AppError(ErrorCode.PARSE_ERROR)
        val limit = (requests["limit_day"] as? JsonPrimitive)?.intOrNull ?: return AppError(ErrorCode.PARSE_ERROR)
        if (used < 0 || limit < 0) return AppError(ErrorCode.PARSE_ERROR)
        return if (limit > 0 && used >= limit) AppError(ErrorCode.QUOTA_EXHAUSTED) else null
    }
}
