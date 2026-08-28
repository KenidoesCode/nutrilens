package com.nutrilens.core.model

/**
 * The result of an operation that can fail in a way the UI must handle.
 *
 * Preferred over exceptions across layer boundaries: a repository's failure
 * modes are part of its contract, and making them a return type means the
 * compiler asks every caller what it intends to do about them.
 */
sealed interface Outcome<out T> {

    data class Success<T>(val data: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): AppError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun failure(error: AppError): Outcome<Nothing> = Failure(error)
    }
}

/** Run [action] when this is a success, then return the outcome unchanged. */
inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = also {
    if (it is Outcome.Success) action(it.data)
}

/** Run [action] when this is a failure, then return the outcome unchanged. */
inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> = also {
    if (it is Outcome.Failure) action(it.error)
}

/**
 * A failure the user might see.
 *
 * Every case maps to a specific, actionable message. There is no generic
 * "something went wrong" case that swallows the distinction between "you are
 * offline" and "your session expired" -- those need different user actions.
 */
sealed interface AppError {

    /** No usable connection. The action was queued or can be retried. */
    data object Offline : AppError

    /** The request reached the server but timed out. */
    data object Timeout : AppError

    /** Credentials were rejected. */
    data object InvalidCredentials : AppError

    /** The session expired or was revoked; the user must sign in again. */
    data object SessionExpired : AppError

    /** That email is already registered. */
    data object EmailAlreadyRegistered : AppError

    /** The password does not meet the policy; [reason] explains which rule. */
    data class WeakPassword(val reason: String?) : AppError

    /** The image could not be read or was rejected. */
    data class InvalidImage(val reason: String?) : AppError

    /** The image exceeded the upload limit. */
    data object ImageTooLarge : AppError

    /** Analysis ran but could not produce a usable result. */
    data class AnalysisFailed(val reason: String?) : AppError

    /** Too many requests; retry after [retryAfterSeconds]. */
    data class RateLimited(val retryAfterSeconds: Int) : AppError

    /** The requested record does not exist, or is not this user's. */
    data object NotFound : AppError

    /** The server failed. [code] is its error code, for support and logs. */
    data class ServerError(val code: String?) : AppError

    /** A local failure: database, filesystem, camera. */
    data class DeviceError(val cause: String?) : AppError

    /**
     * Whether retrying the identical request could plausibly succeed.
     *
     * Used by the sync engine to decide between backing off and giving up, so
     * it never burns retries on a request that will always be rejected.
     */
    val isRetryable: Boolean
        get() = when (this) {
            Offline, Timeout, is RateLimited, is ServerError -> true
            else -> false
        }
}
