package com.footballai.app.core

/** Error taxonomy shown to the user; every branch has a localised message. */
enum class ErrorCode {
    STALE_DATA,
    INVALID_MATCH_STATE,
    UNKNOWN_MATCH_TIME,
    NETWORK_UNAVAILABLE,
    REQUEST_FAILED,
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    MISSING_API_KEY,
    INVALID_API_KEY,
    ACCESS_DENIED,
    PLAN_RESTRICTED,
    SUBSCRIPTION_INACTIVE,
    PARSE_ERROR,
    EMPTY_RESPONSE,
    NOT_FOUND,
    STORAGE_ERROR,
    UNKNOWN,
}

data class AppError(
    val code: ErrorCode,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
    val detail: String? = null,
    val cause: Throwable? = null,
) {
    override fun toString(): String = "AppError($code, status=$httpStatus, detail=$detail)"
}

/** A tiny result type so failures stay explicit in the domain layer. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (error: AppErrorException) {
    Outcome.Failure(error.error)
} catch (error: Exception) {
    Outcome.Failure(AppError(code = ErrorCode.UNKNOWN, detail = error.message, cause = error))
}

class AppErrorException(val error: AppError) : Exception(error.detail ?: error.code.name)

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(block: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value
